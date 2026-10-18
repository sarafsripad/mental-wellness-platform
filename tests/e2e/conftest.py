"""End-to-end tests of the vertical slice (S10) against a running local stack.

Needs: deploy/local/up.sh, workstream-a/backend/wellness-api/run-local.sh and
workstream-a/processing-worker/run-local.sh. Skips when the API isn't reachable.
Synthetic audio only; every record carries data_origin SYNTHETIC.
"""
import importlib.util
import io
import json
import os
import secrets
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import wave
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
API = "http://localhost:8080"
TOKEN_URL = "http://localhost:8081/realms/wellness-local/protocol/openid-connect/token"
ENV = ROOT / "deploy/local/.env"
SCOPES = ["SCREENING_PARTICIPATION", "AUDIO_RECORDING", "AUDIO_RETENTION", "RESEARCH_FEATURE_REUSE",
          "MODEL_TRAINING", "QUALITY_REVIEW"]


def env():
    return dict(l.split("=", 1) for l in ENV.read_text().splitlines() if "=" in l and not l.startswith("#"))


def api_up():
    try:
        urllib.request.urlopen(API + "/actuator/health", timeout=2)
        return True
    except Exception:
        return False


# Locally the suite skips when the stack isn't running. In CI (E2E_REQUIRED=1) it must fail instead,
# so a broken environment can never produce a silent pass.
_available = ENV.exists() and api_up()
if os.environ.get("E2E_REQUIRED") == "1" and not _available:
    raise RuntimeError("E2E_REQUIRED=1 but the local stack or API is not reachable")
pytestmark_skip = pytest.mark.skipif(not _available, reason="local stack/API not running")


def hexid(prefix):
    return f"{prefix}_{secrets.token_hex(16)}"


def iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Http:
    def __init__(self, token):
        self.token = token

    def call(self, method, path, body=None, headers=None, raw=None, content_type="application/json"):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        h = {"Authorization": f"Bearer {self.token}", **(headers or {})}
        if data is not None:
            h["Content-Type"] = content_type
        if method == "POST" and "Idempotency-Key" not in h:
            h["Idempotency-Key"] = str(uuid.uuid4())
        req = urllib.request.Request(API + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                text = r.read().decode()
                return r.status, json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            return e.code, json.loads(text) if text else None


def token(**form):
    data = urllib.parse.urlencode(form).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=data), timeout=10) as r:
        return json.loads(r.read())["access_token"]


@pytest.fixture(scope="session")
def secrets_env():
    return env()


@pytest.fixture(scope="session")
def asha(secrets_env):
    return Http(token(grant_type="password", client_id="wellness-e2e", client_secret=secrets_env["E2E_CLIENT_SECRET"],
                      username="asha.test1", password=secrets_env["KEYCLOAK_TEST_USER_PASSWORD"]))


@pytest.fixture(scope="session")
def synth():
    spec = importlib.util.spec_from_file_location("synth", ROOT / "scripts/generate-synthetic-audio.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    def make(variant):
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(module.RATE)
            w.writeframes(b"".join(struct.pack("<h", int(round(x * 32767))) for x in module.samples(variant)))
        return buf.getvalue()
    return make


class Journey:
    """One synthetic participant through consent, session, recording, upload and processing."""

    def __init__(self, http):
        self.http = http
        self.participant = hexid("ppt")
        self.consent_event = hexid("ce")
        self.consented_at = datetime.now(timezone.utc) - timedelta(hours=1)

    def consent(self):
        decisions = [{"scope": s, "granted": True} for s in SCOPES]
        status, body = self.http.call("POST", "/v1/consent-events", {
            "consent_event_id": self.consent_event, "participant_ref": self.participant,
            "consent_form": {"form_id": "screening-consent", "form_version": "0.1.0", "language_tag": "kn-IN"},
            "scope_decisions": decisions,
            "capture": {"captured_at_utc": iso(self.consented_at), "method": "SIGNED_ON_DEVICE", "read_aloud": True,
                        "comprehension_confirmed": True},
            "safeguards": {"adult_confirmed": True}})
        assert status == 201, body
        return self

    def upload(self, audio, origin="SYNTHETIC"):
        import hashlib
        session, recording = hexid("sess"), hexid("rec")
        now = datetime.now(timezone.utc)
        status, body = self.http.call("POST", "/v1/voice-sessions", {
            "session_id": session, "participant_ref": self.participant, "consent_event_id": self.consent_event,
            "language_tag": "kn-IN", "prompt_set": {"prompt_set_id": "baseline-voice-tasks", "prompt_set_version": "0.1.0"},
            "captured_at_utc": iso(now), "device": {"device_class": "ANDROID_PHONE", "app_version": "0.1.0",
                                                    "os_family": "ANDROID"}, "data_origin": origin})
        if status != 201:
            return status, body, None, None
        sha = hashlib.sha256(audio).hexdigest()
        status, body = self.http.call("POST", f"/v1/voice-sessions/{session}/recordings", {
            "recording_id": recording, "prompt_id": "sustained-vowel-a", "format": "WAV_PCM_S16LE",
            "sampling_rate_hz": 16000, "channels": 1, "duration_seconds": max(3.0, round(len(audio) / 32000, 3)),
            "size_bytes": len(audio), "sha256": sha, "recorded_at_utc": iso(now)})
        assert status == 201, body
        status, body = self.http.call("PUT", f"/v1/voice-sessions/{session}/recordings/{recording}/content",
                                      raw=audio, content_type="audio/wav", headers={"X-Content-SHA256": sha})
        assert status == 200, body
        return 201, body, session, recording

    def submit(self, recording):
        return self.http.call("POST", "/v1/processing-jobs",
                              {"recording_id": recording, "pipeline_profile": "EGEMAPS_V02_FUNCTIONALS_V1"})

    def wait(self, job_id, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status, job = self.http.call("GET", f"/v1/processing-jobs/{job_id}")
            assert status == 200, job
            if job["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"):
                return job
            time.sleep(2)
        raise AssertionError(f"job {job_id} did not finish within {timeout}s (is the worker running?)")

    def withdraw(self, scopes):
        return self.http.call("POST", f"/v1/consent-events/{self.consent_event}/withdrawals", {
            "withdrawal_id": hexid("cw"), "scopes": scopes, "withdrawn_at_utc": iso(datetime.now(timezone.utc)),
            "channel": "IN_PERSON_ASHA"})


@pytest.fixture
def journey(asha):
    return Journey(asha).consent()


def psql(secrets_env, sql):
    """Query app_db as app_user (read-only use) through the postgres container."""
    cid = subprocess.run(["docker", "compose", "ps", "-q", "postgres"], cwd=ROOT / "deploy/local",
                         capture_output=True, text=True, check=True).stdout.strip()
    out = subprocess.run(["docker", "exec", "-e", f"PGPASSWORD={secrets_env['APP_DB_PASSWORD']}", cid, "psql", "-h",
                          "localhost", "-U", "app_user", "-d", "app_db", "-tAc", sql],
                         capture_output=True, text=True, check=True, stdin=subprocess.DEVNULL)
    return out.stdout.strip()


def psql_as(secrets_env, user, password_key, db, sql, check=True):
    """Query a database as a given role through the postgres container (password authentication)."""
    cid = subprocess.run(["docker", "compose", "ps", "-q", "postgres"], cwd=ROOT / "deploy/local",
                         capture_output=True, text=True, check=True).stdout.strip()
    out = subprocess.run(["docker", "exec", "-e", f"PGPASSWORD={secrets_env[password_key]}", cid, "psql", "-h",
                          "localhost", "-U", user, "-d", db, "-tAc", sql],
                         capture_output=True, text=True, check=check, stdin=subprocess.DEVNULL)
    return out.stdout.strip() if check else out


def storage_has(secrets_env, key):
    r = subprocess.run(["docker", "run", "--rm", "--network", f"{secrets_env['COMPOSE_PROJECT_NAME']}_data",
                        "-e", f"AWS_ACCESS_KEY_ID={secrets_env['STORAGE_ROOT_ACCESS_KEY']}",
                        "-e", f"AWS_SECRET_ACCESS_KEY={secrets_env['STORAGE_ROOT_SECRET_KEY']}",
                        "-e", "AWS_DEFAULT_REGION=us-east-1", "amazon/aws-cli:2.37.4", "--endpoint-url",
                        "http://storage:8333", "s3api", "head-object", "--bucket", "wellness-audio", "--key", key],
                       capture_output=True, text=True, stdin=subprocess.DEVNULL)
    return r.returncode == 0
