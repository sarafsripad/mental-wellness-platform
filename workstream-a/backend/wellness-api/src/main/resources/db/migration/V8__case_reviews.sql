-- V8: clinician reviews of flagged cases (API 1.2, ADR-014). Coded decisions only, no free text.
-- Append-only: a later review supersedes an earlier one, it never edits it.

CREATE TABLE cases.case_review (
    seq                 bigint      GENERATED ALWAYS AS IDENTITY UNIQUE,
    review_id           text        PRIMARY KEY CHECK (review_id ~ '^rvw_[a-f0-9]{32}$'),
    case_id             text        NOT NULL REFERENCES cases.assessment_case,
    decision            text        NOT NULL CHECK (decision IN ('NO_FURTHER_ACTION', 'MONITOR', 'REFER', 'URGENT_REFER')),
    follow_up_within    text        NOT NULL CHECK (follow_up_within IN ('NONE', 'WITHIN_24_HOURS', 'WITHIN_7_DAYS', 'WITHIN_30_DAYS')),
    reviewed_at         timestamptz NOT NULL,
    reviewer_subject    text        NOT NULL CHECK (reviewer_subject ~ '^[A-Za-z0-9_-]{1,64}$'),
    created_at          timestamptz NOT NULL DEFAULT now(),
    -- Same rule as the API: the timeframe must fit the decision.
    CHECK ((decision = 'NO_FURTHER_ACTION' AND follow_up_within = 'NONE')
        OR (decision = 'MONITOR' AND follow_up_within IN ('WITHIN_7_DAYS', 'WITHIN_30_DAYS'))
        OR (decision = 'REFER' AND follow_up_within IN ('WITHIN_24_HOURS', 'WITHIN_7_DAYS', 'WITHIN_30_DAYS'))
        OR (decision = 'URGENT_REFER' AND follow_up_within = 'WITHIN_24_HOURS'))
);
CREATE INDEX case_review_case_idx ON cases.case_review (case_id, seq);

CREATE FUNCTION cases.reject_review_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'cases.case_review is append-only (% rejected)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER case_review_no_update_delete
    BEFORE UPDATE OR DELETE ON cases.case_review
    FOR EACH ROW EXECUTE FUNCTION cases.reject_review_modification();

CREATE TRIGGER case_review_no_truncate
    BEFORE TRUNCATE ON cases.case_review
    FOR EACH STATEMENT EXECUTE FUNCTION cases.reject_review_modification();
