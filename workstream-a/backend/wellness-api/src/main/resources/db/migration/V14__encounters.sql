-- V14: clinical encounters (ADR-016, phase 3). Recorded by facility staff under CLINICAL_CARE consent.
-- Instrument scores follow each instrument's published rule and are never a diagnosis. Diagnoses
-- are entered by clinicians only. Notes are AES-GCM ciphertext. None of it is ever exported to
-- research.

CREATE SCHEMA encounters;

CREATE TABLE encounters.encounter (
    encounter_id        text        PRIMARY KEY CHECK (encounter_id ~ '^enc_[a-f0-9]{32}$'),
    participant_ref     text        NOT NULL CHECK (participant_ref ~ '^ppt_[a-f0-9]{32}$'),
    consent_event_id    text        NOT NULL CHECK (consent_event_id ~ '^ce_[a-f0-9]{32}$'),
    facility_code       text        NOT NULL CHECK (facility_code ~ '^[a-z0-9-]{3,40}$'),
    encounter_type      text        NOT NULL CHECK (encounter_type IN ('IN_PERSON', 'TELE_PHONE', 'TELE_VIDEO', 'COUNSELLING_SESSION')),
    series_id           text        CHECK (series_id ~ '^ser_[a-f0-9]{32}$'),
    care_role           text        NOT NULL CHECK (care_role IN ('CLINICIAN', 'COUNSELLOR', 'HEALTH_STAFF')),
    data_origin         text        NOT NULL CHECK (data_origin IN ('SYNTHETIC', 'PARTICIPANT')),
    started_at          timestamptz NOT NULL,
    ended_at            timestamptz,
    urgent_review_flag  boolean     NOT NULL DEFAULT false,
    created_by_subject  text        NOT NULL CHECK (created_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE INDEX encounter_participant_idx ON encounters.encounter (participant_ref, started_at);

CREATE TABLE encounters.instrument_response (
    response_id         text        PRIMARY KEY CHECK (response_id ~ '^ir_[a-f0-9]{32}$'),
    encounter_id        text        NOT NULL REFERENCES encounters.encounter,
    instrument_id       text        NOT NULL CHECK (instrument_id ~ '^[a-z0-9-]{3,64}$'),
    instrument_version  text        NOT NULL CHECK (instrument_version ~ '^\d+\.\d+\.\d+$'),
    language_tag        text        NOT NULL,
    answered_at         timestamptz NOT NULL,
    total_score         integer     CHECK (total_score BETWEEN 0 AND 100),
    severity_band       text        CHECK (severity_band ~ '^[A-Z_]{3,40}$'),
    created_by_subject  text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (encounter_id, instrument_id)
);

CREATE TABLE encounters.instrument_answer (
    response_id         text        NOT NULL REFERENCES encounters.instrument_response,
    item_id             text        NOT NULL CHECK (item_id ~ '^[A-Z0-9_]{2,40}$'),
    answer_code         text        NOT NULL CHECK (answer_code ~ '^[A-Z_]{2,40}$'),
    PRIMARY KEY (response_id, item_id)
);

CREATE TABLE encounters.clinical_note (
    note_id             text        PRIMARY KEY CHECK (note_id ~ '^note_[a-f0-9]{32}$'),
    encounter_id        text        NOT NULL REFERENCES encounters.encounter,
    note_enc            bytea       NOT NULL CHECK (octet_length(note_enc) BETWEEN 29 AND 20000),
    key_version         integer     NOT NULL CHECK (key_version > 0),
    author_subject      text        NOT NULL CHECK (author_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    created_at          timestamptz NOT NULL DEFAULT now()
);

-- Append-only: a later entry (for example RULED_OUT) supersedes, never edits, an earlier one.
CREATE TABLE encounters.diagnosis (
    seq                 bigint      GENERATED ALWAYS AS IDENTITY UNIQUE,
    diagnosis_id        text        PRIMARY KEY CHECK (diagnosis_id ~ '^dx_[a-f0-9]{32}$'),
    encounter_id        text        NOT NULL REFERENCES encounters.encounter,
    code_system         text        NOT NULL CHECK (code_system IN ('ICD_11', 'ICD_10')),
    code                text        NOT NULL CHECK (code ~ '^[0-9A-Z.]{3,10}$'),
    certainty           text        NOT NULL CHECK (certainty IN ('PROVISIONAL', 'CONFIRMED', 'RULED_OUT')),
    recorded_by_subject text        NOT NULL CHECK (recorded_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    recorded_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE encounters.care_plan (
    plan_id             text        PRIMARY KEY CHECK (plan_id ~ '^plan_[a-f0-9]{32}$'),
    encounter_id        text        NOT NULL UNIQUE REFERENCES encounters.encounter,
    next_contact_at     timestamptz,
    next_contact_mode   text        CHECK (next_contact_mode IN ('IN_PERSON', 'PHONE', 'VIDEO', 'HOME_VISIT')),
    referral_target     text        NOT NULL CHECK (referral_target IN ('NONE', 'PSYCHIATRY_OPD', 'DISTRICT_MENTAL_HEALTH_PROGRAMME',
                                    'TELE_MANAS', 'COUNSELLING', 'EMERGENCY_SERVICES')),
    created_by_subject  text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE encounters.care_task (
    task_id             text        PRIMARY KEY CHECK (task_id ~ '^task_[a-f0-9]{32}$'),
    plan_id             text        NOT NULL REFERENCES encounters.care_plan,
    participant_ref     text        NOT NULL CHECK (participant_ref ~ '^ppt_[a-f0-9]{32}$'),
    assignee            text        NOT NULL CHECK (assignee IN ('ASHA_WORKER', 'FACILITY')),
    task_type           text        NOT NULL CHECK (task_type IN ('HOME_VISIT', 'CHECK_IN_CALL', 'ACCOMPANY_TO_FACILITY',
                                    'TREATMENT_ADHERENCE_CHECK')),
    due_by              timestamptz NOT NULL,
    status              text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE', 'NOT_REACHABLE', 'DECLINED', 'CANCELLED')),
    completed_at        timestamptz,
    completed_by_subject text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX care_task_participant_idx ON encounters.care_task (participant_ref, status);

CREATE FUNCTION encounters.reject_modification() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER diagnosis_append_only BEFORE UPDATE OR DELETE ON encounters.diagnosis
    FOR EACH ROW EXECUTE FUNCTION encounters.reject_modification();
CREATE TRIGGER clinical_note_append_only BEFORE UPDATE OR DELETE ON encounters.clinical_note
    FOR EACH ROW EXECUTE FUNCTION encounters.reject_modification();
