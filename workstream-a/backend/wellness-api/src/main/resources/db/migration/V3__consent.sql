-- V3: consent module (ADR-011, docs/privacy/consent-and-data-lifecycle.md).
-- Events and withdrawals are append-only; effective scopes are computed from history.

CREATE TYPE consent.scope AS ENUM (
    'SCREENING_PARTICIPATION', 'AUDIO_RECORDING', 'AUDIO_RETENTION',
    'RESEARCH_FEATURE_REUSE', 'MODEL_TRAINING', 'QUALITY_REVIEW');

-- Consent form versions and the scopes each offers. Form wording lives outside the
-- database and needs ethics approval; this table only identifies versions.
CREATE TABLE consent.consent_form (
    form_id        text NOT NULL CHECK (form_id ~ '^[a-z0-9-]{3,64}$'),
    form_version   text NOT NULL CHECK (form_version ~ '^\d+\.\d+\.\d+$'),
    language_tag   text NOT NULL CHECK (language_tag IN ('en-IN', 'kn-IN')),
    offered_scopes consent.scope[] NOT NULL CHECK (cardinality(offered_scopes) >= 1),
    PRIMARY KEY (form_id, form_version, language_tag)
);

-- v1 assignment: the worker who records a participant's first consent event.
CREATE TABLE consent.participant_assignment (
    participant_ref  text        PRIMARY KEY CHECK (participant_ref ~ '^ppt_[a-f0-9]{32}$'),
    worker_subject   text        NOT NULL CHECK (worker_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    assigned_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE consent.consent_event (
    consent_event_id         text        PRIMARY KEY CHECK (consent_event_id ~ '^ce_[a-f0-9]{32}$'),
    participant_ref          text        NOT NULL REFERENCES consent.participant_assignment (participant_ref),
    form_id                  text        NOT NULL,
    form_version             text        NOT NULL,
    language_tag             text        NOT NULL,
    capture_method           text        NOT NULL CHECK (capture_method IN ('SIGNED_ON_DEVICE', 'THUMBPRINT_ON_DEVICE', 'VERBAL_WITNESSED')),
    read_aloud               boolean     NOT NULL,
    comprehension_confirmed  boolean     NOT NULL CHECK (comprehension_confirmed),
    witness_present          boolean,
    adult_confirmed          boolean     NOT NULL CHECK (adult_confirmed),
    assisted_consent         boolean,
    captured_by_subject      text        NOT NULL CHECK (captured_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    captured_at              timestamptz NOT NULL,
    received_at              timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (form_id, form_version, language_tag) REFERENCES consent.consent_form
);
CREATE INDEX consent_event_participant_idx ON consent.consent_event (participant_ref, captured_at DESC);

CREATE TABLE consent.scope_decision (
    consent_event_id  text          NOT NULL REFERENCES consent.consent_event,
    scope             consent.scope NOT NULL,
    granted           boolean       NOT NULL,
    PRIMARY KEY (consent_event_id, scope)
);

CREATE TABLE consent.withdrawal (
    withdrawal_id        text        PRIMARY KEY CHECK (withdrawal_id ~ '^cw_[a-f0-9]{32}$'),
    consent_event_id     text        NOT NULL REFERENCES consent.consent_event,
    withdrawn_at         timestamptz NOT NULL,
    channel              text        NOT NULL CHECK (channel IN ('IN_PERSON_ASHA', 'IN_PERSON_CLINICIAN', 'PHONE_TO_PROGRAMME', 'WRITTEN_REQUEST')),
    reason_code          text        CHECK (reason_code IN ('NO_LONGER_WISHES', 'PRIVACY_CONCERN', 'OTHER', 'NOT_STATED')),
    recorded_by_subject  text        NOT NULL CHECK (recorded_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    recorded_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX withdrawal_event_idx ON consent.withdrawal (consent_event_id);

-- Scopes actually withdrawn (after cascading). Empty for a no-op withdrawal.
CREATE TABLE consent.withdrawn_scope (
    withdrawal_id  text          NOT NULL REFERENCES consent.withdrawal,
    scope          consent.scope NOT NULL,
    PRIMARY KEY (withdrawal_id, scope)
);

-- Append-only: consent history is never edited or deleted in place. Deletion for a
-- data-subject request will be a separate, reviewed procedure.
CREATE FUNCTION consent.reject_modification() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER consent_event_append_only BEFORE UPDATE OR DELETE ON consent.consent_event
    FOR EACH ROW EXECUTE FUNCTION consent.reject_modification();
CREATE TRIGGER scope_decision_append_only BEFORE UPDATE OR DELETE ON consent.scope_decision
    FOR EACH ROW EXECUTE FUNCTION consent.reject_modification();
CREATE TRIGGER withdrawal_append_only BEFORE UPDATE OR DELETE ON consent.withdrawal
    FOR EACH ROW EXECUTE FUNCTION consent.reject_modification();
CREATE TRIGGER withdrawn_scope_append_only BEFORE UPDATE OR DELETE ON consent.withdrawn_scope
    FOR EACH ROW EXECUTE FUNCTION consent.reject_modification();
