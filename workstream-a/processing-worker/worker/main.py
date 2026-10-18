"""Worker loop: claim, fetch, QC, extract, validate, submit. One job at a time, audio in memory only."""
import logging
import signal
import sys
import time

from . import __version__
from .api import IntegrityError, LeaseLost, WorkerApi
from .audio import DecodeError, assess, decode
from .config import Settings
from .extract import PROFILE, Extractor
from .record import RecordBuilder, RecordInvalid

log = logging.getLogger("worker")


class Worker:
    def __init__(self, settings: Settings, api: WorkerApi | None = None, extractor: Extractor | None = None):
        self.s = settings
        self.api = api or WorkerApi(settings)
        self.extractor = extractor or Extractor()
        self.builder = RecordBuilder(settings.schema_path, self.extractor.config_sha256, settings.pipeline_commit)
        self.running = True

    def run_once(self) -> str:
        """Processes at most one job. Returns an outcome code (for logs and tests)."""
        lease = self.api.claim([PROFILE], __version__)
        if lease is None:
            return "IDLE"
        job = lease["job_id"]
        started = time.monotonic()
        try:
            if lease["data_origin"] not in self.s.allowed_origins:
                # Licence and governance guard (contracts/licences/opensmile.md).
                self.api.failure(lease, "INTERNAL_ERROR", retryable=False)
                return self._done(job, "REFUSED_DATA_ORIGIN", started)
            content = self.api.audio(lease)
            try:
                decoded = decode(content, lease["audio"]["format"])
            except DecodeError:
                self.api.failure(lease, "AUDIO_DECODE_FAILED", retryable=False)
                return self._done(job, "AUDIO_DECODE_FAILED", started)
            finally:
                del content  # audio is never kept beyond the job
            quality = assess(decoded)
            if quality.status == "FAIL":
                self.api.failure(lease, "AUDIO_QUALITY_FAILED", retryable=False,
                                 quality={"status": "FAIL", "flags": quality.flags})
                return self._done(job, "AUDIO_QUALITY_FAILED", started)
            try:
                features = self.extractor.extract(decoded.signal, decoded.sampling_rate_hz)
            except Exception:
                self.api.failure(lease, "EXTRACTION_ERROR", retryable=True)
                return self._done(job, "EXTRACTION_ERROR", started)
            try:
                record = self.builder.build(lease, decoded, quality, features,
                                            int((time.monotonic() - started) * 1000))
            except RecordInvalid:
                self.api.failure(lease, "INTERNAL_ERROR", retryable=False)
                return self._done(job, "RECORD_INVALID", started)
            self.api.result(lease, record)
            return self._done(job, "SUCCEEDED", started)
        except LeaseLost as ex:
            return self._done(job, f"DISCARDED_{ex.code}", started)
        except IntegrityError:
            self.api.failure(lease, "AUDIO_DECODE_FAILED", retryable=True)
            return self._done(job, "AUDIO_INTEGRITY_FAILED", started)

    def _done(self, job: str, outcome: str, started: float) -> str:
        # IDs, outcome codes and durations only: never audio, feature values or paths.
        log.info("job=%s outcome=%s duration_ms=%d", job, outcome, int((time.monotonic() - started) * 1000))
        return outcome

    def run_forever(self) -> None:
        while self.running:
            try:
                if self.run_once() == "IDLE":
                    time.sleep(self.s.poll_seconds)
            except Exception as ex:  # keep polling; report the type only
                log.warning("cycle failed: %s", type(ex).__name__)
                time.sleep(self.s.poll_seconds)


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s", stream=sys.stdout)
    worker = Worker(Settings.from_env())
    log.info("worker started id=%s version=%s profile=%s", worker.s.worker_id, __version__, PROFILE)

    def stop(*_):
        worker.running = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    worker.run_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
