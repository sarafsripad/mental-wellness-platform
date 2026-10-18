-- identity_db V1 (ADR-010, ADR-013). Applied by the identity module's own Flyway run, as
-- identity_user; never part of app_db's migrations. Values are AES-256-GCM ciphertext
-- (IdentityCipher); the database never sees a name or village in clear text.
CREATE TABLE identity.person (
    participant_ref     text        PRIMARY KEY CHECK (participant_ref ~ '^ppt_[a-f0-9]{32}$'),
    consent_event_id    text        NOT NULL CHECK (consent_event_id ~ '^ce_[a-f0-9]{32}$'),
    data_origin         text        NOT NULL CHECK (data_origin IN ('SYNTHETIC', 'PARTICIPANT')),
    display_name_enc    bytea       NOT NULL CHECK (octet_length(display_name_enc) BETWEEN 29 AND 1024),
    village_name_enc    bytea       NOT NULL CHECK (octet_length(village_name_enc) BETWEEN 29 AND 1024),
    key_version         integer     NOT NULL CHECK (key_version > 0),
    stored_by_subject   text        NOT NULL CHECK (stored_by_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
