-- V7: ASHA assessment cases (API 1.1, ADR-013) and the wider consent language set.
-- Coded values only: no names, places or free text in this schema. Direct identifiers live in
-- identity_db (identity module, ADR-010).

-- Consent forms may now be registered in the 12 languages of API 1.1. Voice sessions keep
-- en-IN/kn-IN (the feature-record schema's set), so voice.session is unchanged.
ALTER TABLE consent.consent_form DROP CONSTRAINT consent_form_language_tag_check;
ALTER TABLE consent.consent_form ADD CONSTRAINT consent_form_language_tag_check CHECK (language_tag IN
    ('en-IN', 'te-IN', 'hi-IN', 'ta-IN', 'bn-IN', 'mr-IN', 'kn-IN', 'ml-IN', 'gu-IN', 'pa-IN', 'ur-IN', 'or-IN'));

CREATE SCHEMA cases;

CREATE TABLE cases.assessment_case (
    case_id             text        PRIMARY KEY CHECK (case_id ~ '^case_[a-f0-9]{32}$'),
    participant_ref     text        NOT NULL CHECK (participant_ref ~ '^ppt_[a-f0-9]{32}$'),
    consent_event_id    text        NOT NULL CHECK (consent_event_id ~ '^ce_[a-f0-9]{32}$'),
    language_tag        text        NOT NULL CHECK (language_tag IN
        ('en-IN', 'te-IN', 'hi-IN', 'ta-IN', 'bn-IN', 'mr-IN', 'kn-IN', 'ml-IN', 'gu-IN', 'pa-IN', 'ur-IN', 'or-IN')),
    data_origin         text        NOT NULL CHECK (data_origin IN ('SYNTHETIC', 'PARTICIPANT')),
    age_band            text        NOT NULL CHECK (age_band IN ('AGE_18_29', 'AGE_30_44', 'AGE_45_59', 'AGE_60_PLUS')),
    gender              text        NOT NULL CHECK (gender IN ('NOT_STATED', 'FEMALE', 'MALE', 'OTHER')),
    opened_at           timestamptz NOT NULL,
    opened_by_subject   text        NOT NULL CHECK (opened_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    urgent_review_flag  boolean     NOT NULL DEFAULT false,
    follow_up_requested boolean,
    completed_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CHECK ((completed_at IS NULL) = (follow_up_requested IS NULL))
);
CREATE INDEX assessment_case_opened_by_idx ON cases.assessment_case (opened_by_subject, created_at DESC);
CREATE INDEX assessment_case_participant_idx ON cases.assessment_case (participant_ref);

-- One observation set per case (coded catalogue asha-observations).
CREATE TABLE cases.observation_set (
    observation_set_id  text        PRIMARY KEY CHECK (observation_set_id ~ '^obs_[a-f0-9]{32}$'),
    case_id             text        NOT NULL UNIQUE REFERENCES cases.assessment_case,
    catalogue_id        text        NOT NULL CHECK (catalogue_id ~ '^[a-z0-9-]{3,64}$'),
    catalogue_version   text        NOT NULL CHECK (catalogue_version ~ '^\d+\.\d+\.\d+$'),
    observed_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE cases.observation (
    observation_set_id  text        NOT NULL REFERENCES cases.observation_set,
    code                text        NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,40}$'),
    value_code          text        NOT NULL CHECK (value_code ~ '^[A-Z][A-Z0-9_]{1,40}$'),
    PRIMARY KEY (observation_set_id, code, value_code)
);

-- One questionnaire response per case. Answers are stored as coded values and never scored.
CREATE TABLE cases.questionnaire_response (
    response_id         text        PRIMARY KEY CHECK (response_id ~ '^qr_[a-f0-9]{32}$'),
    case_id             text        NOT NULL UNIQUE REFERENCES cases.assessment_case,
    instrument_id       text        NOT NULL CHECK (instrument_id ~ '^[a-z0-9-]{3,64}$'),
    instrument_version  text        NOT NULL CHECK (instrument_version ~ '^\d+\.\d+\.\d+$'),
    language_tag        text        NOT NULL CHECK (language_tag IN
        ('en-IN', 'te-IN', 'hi-IN', 'ta-IN', 'bn-IN', 'mr-IN', 'kn-IN', 'ml-IN', 'gu-IN', 'pa-IN', 'ur-IN', 'or-IN')),
    answered_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE cases.questionnaire_answer (
    response_id         text        NOT NULL REFERENCES cases.questionnaire_response,
    item_id             text        NOT NULL CHECK (item_id ~ '^[A-Z][A-Z0-9_]{1,40}$'),
    answer_code         text        NOT NULL CHECK (answer_code ~ '^[A-Z][A-Z0-9_]{1,40}$'),
    PRIMARY KEY (response_id, item_id)
);
