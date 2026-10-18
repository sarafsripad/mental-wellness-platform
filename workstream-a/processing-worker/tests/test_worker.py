import logging

import pytest

from conftest import lease
from worker.api import LeaseLost
from worker.config import Settings
from worker.main import Worker


class FakeApi:
    def __init__(self, job, audio, lose_on=None):
        self.job, self.content, self.lose_on = job, audio, lose_on
        self.results, self.failures = [], []

    def claim(self, profiles, version):
        job, self.job = self.job, None
        return job

    def audio(self, l):
        if self.lose_on == "audio":
            raise LeaseLost("CONSENT_WITHDRAWN")
        return self.content

    def result(self, l, record):
        if self.lose_on == "result":
            raise LeaseLost("CONSENT_WITHDRAWN")
        self.results.append(record)
        return {}

    def failure(self, l, code, retryable, quality=None):
        self.failures.append((code, retryable, quality))
        return {}


@pytest.fixture
def settings(schema_path):
    return Settings.from_env({"WORKER_CLIENT_SECRET": "test-only", "WORKER_SCHEMA_PATH": str(schema_path)})


def run(settings, extractor, job, audio, lose_on=None):
    api = FakeApi(job, audio, lose_on)
    return Worker(settings, api=api, extractor=extractor).run_once(), api


def test_good_audio_produces_one_valid_record(settings, extractor, synth):
    outcome, api = run(settings, extractor, lease(), synth("voice"))
    assert outcome == "SUCCEEDED" and len(api.results) == 1 and not api.failures


def test_failed_qc_reports_failure_and_no_record(settings, extractor, synth):
    outcome, api = run(settings, extractor, lease(), synth("silent"))
    assert outcome == "AUDIO_QUALITY_FAILED" and not api.results
    assert api.failures == [("AUDIO_QUALITY_FAILED", False, {"status": "FAIL", "flags": ["SILENCE"]})]


def test_participant_data_is_refused_by_the_licence_guard(settings, extractor, synth):
    outcome, api = run(settings, extractor, lease(data_origin="PARTICIPANT"), synth("voice"))
    assert outcome == "REFUSED_DATA_ORIGIN" and not api.results
    assert api.failures[0][0] == "INTERNAL_ERROR"


def test_undecodable_audio_is_reported(settings, extractor):
    outcome, api = run(settings, extractor, lease(), b"definitely not a wav file")
    assert outcome == "AUDIO_DECODE_FAILED" and api.failures[0][0] == "AUDIO_DECODE_FAILED"


@pytest.mark.parametrize("stage", ["audio", "result"])
def test_lost_lease_discards_everything(settings, extractor, synth, stage):
    outcome, api = run(settings, extractor, lease(), synth("voice"), lose_on=stage)
    assert outcome == "DISCARDED_CONSENT_WITHDRAWN" and not api.results and not api.failures


def test_idle_when_no_job(settings, extractor):
    assert run(settings, extractor, None, b"")[0] == "IDLE"


def test_logs_carry_ids_and_codes_only(settings, extractor, synth, caplog):
    caplog.set_level(logging.INFO)
    run(settings, extractor, lease(), synth("voice"))
    text = caplog.text
    assert "job=job_" in text and "outcome=SUCCEEDED" in text
    assert "F0semitone" not in text and "RIFF" not in text and "t" * 64 not in text


def test_settings_require_a_secret_and_valid_worker_id():
    with pytest.raises(ValueError):
        Settings.from_env({})
    with pytest.raises(ValueError):
        Settings.from_env({"WORKER_CLIENT_SECRET": "x", "WORKER_ID": "worker-1"})
    s = Settings.from_env({"WORKER_CLIENT_SECRET": "x"})
    assert s.worker_id.startswith("wrk_") and len(s.worker_id) == 20
    assert s.allowed_origins == frozenset({"SYNTHETIC", "AUTHORIZED_TEST_AUDIO"})
