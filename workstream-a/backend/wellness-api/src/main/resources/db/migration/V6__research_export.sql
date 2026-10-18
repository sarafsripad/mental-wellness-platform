-- V6: research-export module (slice S8). Tracks which (application) record IDs went into which
-- export, so later exports can tell Workstream B which records to withdraw.
CREATE TABLE research_export.export (
    export_id          text        PRIMARY KEY CHECK (export_id ~ '^exp_[a-f0-9]{32}$'),
    created_at         timestamptz NOT NULL DEFAULT now(),
    record_count       integer     NOT NULL CHECK (record_count >= 0),
    withdrawn_count    integer     NOT NULL CHECK (withdrawn_count >= 0),
    records_sha256     text        NOT NULL CHECK (records_sha256 ~ '^[a-f0-9]{64}$'),
    key_id             text        NOT NULL CHECK (key_id ~ '^key_[a-f0-9]{16}$'),
    requested_by       text        NOT NULL CHECK (requested_by ~ '^[A-Za-z0-9_-]{1,64}$')
);

CREATE TABLE research_export.exported_record (
    export_id   text NOT NULL REFERENCES research_export.export,
    record_id   text NOT NULL CHECK (record_id ~ '^frec_[a-f0-9]{32}$'),
    PRIMARY KEY (export_id, record_id)
);
CREATE INDEX exported_record_record_idx ON research_export.exported_record (record_id);

-- Records whose withdrawal has already been announced to Workstream B (announced once).
CREATE TABLE research_export.announced_withdrawal (
    record_id    text        PRIMARY KEY CHECK (record_id ~ '^frec_[a-f0-9]{32}$'),
    export_id    text        NOT NULL REFERENCES research_export.export,
    announced_at timestamptz NOT NULL DEFAULT now()
);
