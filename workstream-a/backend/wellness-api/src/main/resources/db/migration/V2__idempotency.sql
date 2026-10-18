-- V2: idempotency keys for create operations (contract: Idempotency-Key).
-- Scoped to (principal, operation, key). Keys must be kept for at least 30 days;
-- a purge job is added before production.
CREATE SCHEMA idempotency;

CREATE TABLE idempotency.request_key (
    principal        text        NOT NULL CHECK (principal ~ '^[A-Za-z0-9_-]{1,64}$'),
    operation        text        NOT NULL CHECK (operation ~ '^[a-zA-Z]{3,64}$'),
    idempotency_key  uuid        NOT NULL,
    request_hash     text        NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    response_body    text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    PRIMARY KEY (principal, operation, idempotency_key)
);

CREATE INDEX request_key_created_idx ON idempotency.request_key (created_at);
