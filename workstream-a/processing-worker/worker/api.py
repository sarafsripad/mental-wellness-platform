"""Client for the internal worker API (contracts/openapi/processing-worker.internal.v1.yaml)."""
import hashlib
import time

import httpx

from .config import Settings


class LeaseLost(Exception):
    """409 from the backend: lease expired or invalid, job not running, or consent withdrawn.
    The worker must discard the job's audio and output."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class IntegrityError(Exception):
    """Fetched audio did not match the lease checksum."""


class WorkerApi:
    def __init__(self, settings: Settings, client: httpx.Client | None = None):
        self.s = settings
        self.http = client or httpx.Client(timeout=30.0)
        self._token = None
        self._token_expires = 0.0

    def _bearer(self) -> str:
        if self._token is None or time.monotonic() > self._token_expires - 30:
            r = self.http.post(self.s.token_url, data={"grant_type": "client_credentials",
                                                       "client_id": self.s.client_id,
                                                       "client_secret": self.s.client_secret})
            r.raise_for_status()
            body = r.json()
            self._token, self._token_expires = body["access_token"], time.monotonic() + body.get("expires_in", 60)
        return self._token

    def _headers(self, lease: dict | None = None) -> dict:
        h = {"Authorization": f"Bearer {self._bearer()}", "X-Worker-Id": self.s.worker_id}
        if lease is not None:
            h["X-Lease-Token"] = lease["lease_token"]
            h["X-Correlation-ID"] = lease["correlation_id"]
        return h

    @staticmethod
    def _check(r: httpx.Response) -> httpx.Response:
        if r.status_code == 409:
            raise LeaseLost(r.json().get("code", "LEASE_INVALID"))
        r.raise_for_status()
        return r

    def claim(self, profiles: list[str], worker_version: str) -> dict | None:
        r = self._check(self.http.post(f"{self.s.api_url}/internal/v1/processing-jobs/claim", headers=self._headers(),
                                       json={"supported_profiles": profiles, "worker_version": worker_version}))
        return None if r.status_code == 204 else r.json()

    def audio(self, lease: dict) -> bytes:
        r = self._check(self.http.get(f"{self.s.api_url}/internal/v1/processing-jobs/{lease['job_id']}/audio",
                                      headers=self._headers(lease)))
        content = r.content
        if hashlib.sha256(content).hexdigest() != lease["audio"]["sha256"]:
            raise IntegrityError("checksum mismatch")
        return content

    def result(self, lease: dict, record: dict) -> dict:
        return self._check(self.http.post(f"{self.s.api_url}/internal/v1/processing-jobs/{lease['job_id']}/result",
                                          headers=self._headers(lease), json=record)).json()

    def failure(self, lease: dict, code: str, retryable: bool, quality: dict | None = None) -> dict:
        body = {"code": code, "retryable": retryable}
        if quality is not None:
            body["quality_outcome"] = quality
        return self._check(self.http.post(f"{self.s.api_url}/internal/v1/processing-jobs/{lease['job_id']}/failure",
                                          headers=self._headers(lease), json=body)).json()
