"""Settings from environment variables. Secrets have no defaults."""
import os
import re
import secrets
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    api_url: str
    token_url: str
    client_id: str
    client_secret: str
    worker_id: str
    poll_seconds: float
    allowed_origins: frozenset
    schema_path: Path
    pipeline_commit: str | None

    @staticmethod
    def from_env(env=os.environ) -> "Settings":
        secret = env.get("WORKER_CLIENT_SECRET", "")
        if not secret:
            raise ValueError("WORKER_CLIENT_SECRET must be set")
        worker_id = env.get("WORKER_ID") or f"wrk_{secrets.token_hex(8)}"
        if not re.fullmatch(r"wrk_[a-f0-9]{16}", worker_id):
            raise ValueError("WORKER_ID must match wrk_ + 16 hex characters")
        commit = env.get("WORKER_GIT_COMMIT") or None
        if commit and not re.fullmatch(r"[a-f0-9]{7,40}", commit):
            commit = None
        return Settings(
            api_url=env.get("WORKER_API_URL", "http://localhost:8080").rstrip("/"),
            token_url=env.get("WORKER_TOKEN_URL",
                              "http://localhost:8081/realms/wellness-local/protocol/openid-connect/token"),
            client_id=env.get("WORKER_CLIENT_ID", "processing-worker"),
            client_secret=secret,
            worker_id=worker_id,
            poll_seconds=float(env.get("WORKER_POLL_SECONDS", "5")),
            allowed_origins=frozenset(
                o.strip() for o in env.get("WORKER_ALLOWED_ORIGINS", "SYNTHETIC,AUTHORIZED_TEST_AUDIO").split(",") if o.strip()),
            schema_path=Path(env.get("WORKER_SCHEMA_PATH", default_schema_path())),
            pipeline_commit=commit,
        )


def default_schema_path() -> str:
    """The contract schema: copied into the image at build, or read from the repository in development."""
    packaged = Path("/app/contracts/feature-record.schema.json")
    if packaged.exists():
        return str(packaged)
    return str(Path(__file__).resolve().parents[3] / "contracts/voice-features/feature-record.schema.json")
