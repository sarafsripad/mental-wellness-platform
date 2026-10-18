# Audio-processing worker (slice S7). Build from the repository root so the contract schema can be
# copied in:  docker build -f workstream-a/processing-worker/Dockerfile -t wellness-worker .
# openSMILE is included under the audEERING Research License: do not publish this image publicly
# (contracts/licences/opensmile.md).
FROM python:3.12.14-slim-bookworm AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY workstream-a/processing-worker/requirements.lock ./
RUN pip install --no-cache-dir --root-user-action=ignore --default-timeout=120 --retries 10 -r requirements.lock
COPY contracts/voice-features/feature-record.schema.json /app/contracts/feature-record.schema.json
COPY contracts/licences/opensmile.md /app/licences/opensmile.md
COPY workstream-a/processing-worker/worker /app/worker
RUN useradd --system --uid 10001 --no-create-home worker
USER worker
ENTRYPOINT ["python", "-m", "worker.main"]

FROM runtime AS test
USER root
RUN pip install --no-cache-dir --root-user-action=ignore --default-timeout=120 --retries 10 pytest==9.1.1
COPY scripts/generate-synthetic-audio.py /app/scripts/generate-synthetic-audio.py
COPY workstream-a/processing-worker/tests /app/tests
USER worker
ENTRYPOINT ["python", "-m", "pytest", "-q", "-p", "no:cacheprovider", "/app/tests"]
