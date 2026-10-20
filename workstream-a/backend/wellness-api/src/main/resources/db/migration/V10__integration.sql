-- V10: partner referrals (ADR-015). Pseudonymous: no names, places, answers or free text. The
-- outbox makes delivery reliable; inbound events are deduplicated per partner.

CREATE SCHEMA integration;

CREATE TABLE integration.referral (
    referral_id         text        PRIMARY KEY CHECK (referral_id ~ '^ref_[a-f0-9]{32}$'),
    case_id             text        NOT NULL CHECK (case_id ~ '^case_[a-f0-9]{32}$'),
    participant_ref     text        NOT NULL CHECK (participant_ref ~ '^ppt_[a-f0-9]{32}$'),
    consent_event_id    text        NOT NULL CHECK (consent_event_id ~ '^ce_[a-f0-9]{32}$'),
    partner_id          text        NOT NULL CHECK (partner_id ~ '^[a-z0-9-]{3,40}$'),
    review_id           text        NOT NULL CHECK (review_id ~ '^rvw_[a-f0-9]{32}$'),
    decision            text        NOT NULL CHECK (decision IN ('REFER', 'URGENT_REFER')),
    follow_up_within    text        NOT NULL CHECK (follow_up_within IN ('WITHIN_24_HOURS', 'WITHIN_7_DAYS', 'WITHIN_30_DAYS')),
    handoff_code        text        NOT NULL UNIQUE CHECK (handoff_code ~ '^[A-Z0-9]{8}$'),
    status              text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'SENT', 'RECEIVED',
                                    'CONTACT_ATTEMPTED', 'CONTACTED', 'APPOINTMENT_BOOKED', 'CLOSED', 'DECLINED',
                                    'DELIVERY_FAILED', 'REVOKED')),
    status_at           timestamptz NOT NULL DEFAULT now(),
    partner_reference   text        CHECK (partner_reference ~ '^[A-Za-z0-9-]{1,64}$'),
    created_by_subject  text        NOT NULL CHECK (created_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    created_at          timestamptz NOT NULL DEFAULT now()
);
-- At most one live referral per case and partner.
CREATE UNIQUE INDEX referral_one_live_idx ON integration.referral (case_id, partner_id)
    WHERE status NOT IN ('CLOSED', 'DECLINED', 'DELIVERY_FAILED', 'REVOKED');
CREATE INDEX referral_case_idx ON integration.referral (case_id);
CREATE INDEX referral_consent_idx ON integration.referral (consent_event_id);

CREATE TABLE integration.outbound_message (
    message_id          text        PRIMARY KEY CHECK (message_id ~ '^msg_[a-f0-9]{32}$'),
    referral_id         text        NOT NULL REFERENCES integration.referral,
    partner_id          text        NOT NULL CHECK (partner_id ~ '^[a-z0-9-]{3,40}$'),
    kind                text        NOT NULL CHECK (kind IN ('REFERRAL', 'REVOCATION')),
    payload             text        NOT NULL,
    status              text        NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING', 'SENDING', 'DELIVERED', 'FAILED', 'CANCELLED')),
    attempts            integer     NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    next_attempt_at     timestamptz NOT NULL DEFAULT now(),
    lease_expires_at    timestamptz,
    last_error          text        CHECK (last_error ~ '^[A-Z0-9_]{1,40}$'),
    delivered_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbound_due_idx ON integration.outbound_message (next_attempt_at) WHERE status IN ('PENDING', 'SENDING');

CREATE TABLE integration.inbound_event (
    partner_id          text        NOT NULL CHECK (partner_id ~ '^[a-z0-9-]{3,40}$'),
    event_id            text        NOT NULL CHECK (event_id ~ '^[A-Za-z0-9-]{1,64}$'),
    referral_id         text        NOT NULL REFERENCES integration.referral,
    status              text        NOT NULL CHECK (status IN ('RECEIVED', 'CONTACT_ATTEMPTED', 'CONTACTED',
                                    'APPOINTMENT_BOOKED', 'CLOSED', 'DECLINED')),
    occurred_at         timestamptz NOT NULL,
    request_hash        text        NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    applied             boolean     NOT NULL,
    received_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (partner_id, event_id)
);
CREATE INDEX inbound_event_referral_idx ON integration.inbound_event (referral_id, occurred_at);
