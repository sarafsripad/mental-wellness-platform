-- V1: schema baseline for the wellness-api modular monolith (ADR-004).
-- One schema per module; no module reads another module's tables.
-- Identity data lives in a separate database (identity_db) and is not created here.

CREATE SCHEMA consent;
CREATE SCHEMA voice;
CREATE SCHEMA processing;
CREATE SCHEMA research_export;
CREATE SCHEMA audit;

COMMENT ON SCHEMA consent IS 'Consent events and withdrawals (slice S4)';
COMMENT ON SCHEMA voice IS 'Voice sessions and recording metadata (slice S5)';
COMMENT ON SCHEMA processing IS 'Processing jobs and feature records (slice S6)';
COMMENT ON SCHEMA research_export IS 'Research export batches (slice S8)';
COMMENT ON SCHEMA audit IS 'Append-only audit events';

-- Audit events: codes and pseudonymous IDs only. The check constraints
-- make it impossible to store free text such as names or request bodies.
CREATE TABLE audit.audit_event (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    action          text        NOT NULL CHECK (action ~ '^[A-Z][A-Z_]{2,63}$'),
    outcome         text        NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'REJECTED', 'FAILED')),
    actor_ref       text        NOT NULL CHECK (actor_ref ~ '^[A-Za-z0-9_-]{1,64}$'),
    actor_roles     text        NOT NULL CHECK (actor_roles ~ '^([A-Z][A-Z_]{1,31}(,[A-Z][A-Z_]{1,31})*)?$'),
    resource_type   text        CHECK (resource_type ~ '^[a-z][a-z_]{1,31}$'),
    resource_id     text        CHECK (resource_id ~ '^[a-z]{2,5}_[a-f0-9]{32}$'),
    access_reason   text        CHECK (access_reason IN ('SUPPORT_TICKET', 'DATA_SUBJECT_REQUEST', 'AUDIT', 'INCIDENT_RESPONSE')),
    correlation_id  uuid        NOT NULL
);

CREATE INDEX audit_event_occurred_at_idx ON audit.audit_event (occurred_at);
CREATE INDEX audit_event_correlation_idx ON audit.audit_event (correlation_id);
CREATE INDEX audit_event_resource_idx ON audit.audit_event (resource_id) WHERE resource_id IS NOT NULL;

-- Append-only: reject UPDATE, DELETE and TRUNCATE, even for the table owner.
CREATE FUNCTION audit.reject_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit.audit_event is append-only (% rejected)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit.audit_event
    FOR EACH ROW EXECUTE FUNCTION audit.reject_modification();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit.audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_modification();
