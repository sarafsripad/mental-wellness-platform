-- V5: processing module (slice S6). Job queue in PostgreSQL (ADR-007): workers pull jobs
-- over the internal API with FOR UPDATE SKIP LOCKED and hold time-limited leases.

CREATE TABLE processing.job (
    job_id               text        PRIMARY KEY CHECK (job_id ~ '^job_[a-f0-9]{32}$'),
    recording_id         text        NOT NULL CHECK (recording_id ~ '^rec_[a-f0-9]{32}$'),
    session_id           text        NOT NULL CHECK (session_id ~ '^sess_[a-f0-9]{32}$'),
    participant_ref      text        NOT NULL CHECK (participant_ref ~ '^ppt_[a-f0-9]{32}$'),
    consent_event_id     text        NOT NULL CHECK (consent_event_id ~ '^ce_[a-f0-9]{32}$'),
    pipeline_profile     text        NOT NULL CHECK (pipeline_profile IN ('EGEMAPS_V02_FUNCTIONALS_V1')),
    status               text        NOT NULL DEFAULT 'QUEUED'
                                     CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempt              integer     NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 3),
    -- Only a SHA-256 of the lease token is stored; the token itself is never persisted or logged.
    lease_token_hash     text        CHECK (lease_token_hash ~ '^[a-f0-9]{64}$'),
    lease_worker_id      text        CHECK (lease_worker_id ~ '^wrk_[a-f0-9]{16}$'),
    lease_expires_at     timestamptz,
    quality_status       text        CHECK (quality_status IN ('PASS', 'DEGRADED', 'FAIL')),
    quality_flags        text[],
    failure_code         text        CHECK (failure_code IN ('AUDIO_QUALITY_FAILED', 'AUDIO_DECODE_FAILED', 'EXTRACTION_ERROR',
                                                             'TIMEOUT', 'CONSENT_WITHDRAWN', 'INTERNAL_ERROR')),
    failure_retryable    boolean,
    feature_record_id    text        CHECK (feature_record_id ~ '^frec_[a-f0-9]{32}$'),
    submitted_by_subject text        NOT NULL CHECK (submitted_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    correlation_id       uuid        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (recording_id, pipeline_profile),
    CHECK (status <> 'RUNNING' OR (lease_token_hash IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_worker_id IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED' OR feature_record_id IS NOT NULL),
    CHECK (status NOT IN ('FAILED', 'CANCELLED') OR failure_code IS NOT NULL)
);
CREATE INDEX job_queue_idx ON processing.job (created_at) WHERE status = 'QUEUED';
CREATE INDEX job_lease_idx ON processing.job (lease_expires_at) WHERE status = 'RUNNING';
CREATE INDEX job_consent_idx ON processing.job (consent_event_id);
CREATE INDEX job_session_idx ON processing.job (session_id);

-- Feature records as produced by the worker and validated against
-- contracts/voice-features/feature-record.schema.json; server-known fields overwritten.
CREATE TABLE processing.feature_record (
    record_id         text        PRIMARY KEY CHECK (record_id ~ '^frec_[a-f0-9]{32}$'),
    job_id            text        NOT NULL UNIQUE REFERENCES processing.job,
    recording_id      text        NOT NULL,
    session_id        text        NOT NULL,
    consent_event_id  text        NOT NULL,
    schema_version    text        NOT NULL CHECK (schema_version = '2.0.0'),
    data_origin       text        NOT NULL CHECK (data_origin IN ('SYNTHETIC', 'AUTHORIZED_TEST_AUDIO', 'PARTICIPANT')),
    record            jsonb       NOT NULL,
    record_hash       text        NOT NULL CHECK (record_hash ~ '^[a-f0-9]{64}$'),
    created_at        timestamptz NOT NULL DEFAULT now(),
    -- Set when AUDIO_RECORDING consent is withdrawn: excluded from all further use and export.
    flagged_at        timestamptz
);
CREATE INDEX feature_record_consent_idx ON processing.feature_record (consent_event_id);
