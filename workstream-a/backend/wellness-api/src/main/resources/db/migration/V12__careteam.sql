-- V12: care teams (ADR-016). A participant's record is shared between their ASHA worker (the
-- existing consent.participant_assignment link, unchanged) and the facilities on their care team.
-- Facility staff reach a participant only through a facility on that participant's care team.
-- Coded values only: no addresses, no names of people.

CREATE SCHEMA careteam;

CREATE TABLE careteam.facility (
    facility_code       text        PRIMARY KEY CHECK (facility_code ~ '^[a-z0-9-]{3,40}$'),
    kind                text        NOT NULL CHECK (kind IN ('PHC', 'CHC', 'DISTRICT_HOSPITAL', 'TELE_CENTRE', 'COUNSELLING_CENTRE')),
    facility_label      text        NOT NULL CHECK (char_length(facility_label) BETWEEN 1 AND 80),
    active              boolean     NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE careteam.staff_membership (
    subject             text        NOT NULL CHECK (subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    facility_code       text        NOT NULL REFERENCES careteam.facility,
    care_role           text        NOT NULL CHECK (care_role IN ('CLINICIAN', 'COUNSELLOR', 'HEALTH_STAFF')),
    active              boolean     NOT NULL DEFAULT true,
    updated_by_subject  text        NOT NULL CHECK (updated_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subject, facility_code)
);
CREATE INDEX staff_membership_facility_idx ON careteam.staff_membership (facility_code) WHERE active;

CREATE TABLE careteam.participant_care_team (
    participant_ref     text        NOT NULL CHECK (participant_ref ~ '^ppt_[a-f0-9]{32}$'),
    facility_code       text        NOT NULL REFERENCES careteam.facility,
    reason_code         text        NOT NULL CHECK (reason_code IN ('ASHA_REFERRAL', 'CLINIC_REGISTRATION', 'TRANSFER')),
    added_by_subject    text        NOT NULL CHECK (added_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    added_at            timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (participant_ref, facility_code)
);
CREATE INDEX participant_care_team_facility_idx ON careteam.participant_care_team (facility_code);
