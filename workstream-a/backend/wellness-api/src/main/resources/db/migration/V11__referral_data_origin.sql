-- V11: data origin on referrals, so aggregate reports never mix SYNTHETIC and PARTICIPANT
-- (security review of ADR-015). Every referral so far comes from a synthetic case.
ALTER TABLE integration.referral
    ADD COLUMN data_origin text NOT NULL DEFAULT 'SYNTHETIC' CHECK (data_origin IN ('SYNTHETIC', 'PARTICIPANT'));
ALTER TABLE integration.referral ALTER COLUMN data_origin DROP DEFAULT;
