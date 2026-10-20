-- V9: consent scope for sharing a pseudonymous referral with a partner programme (ADR-015).
-- Depends on SCREENING_PARTICIPATION (ConsentScope). Its own migration: a new enum value can't be
-- used in the transaction that adds it.
ALTER TYPE consent.scope ADD VALUE IF NOT EXISTS 'EXTERNAL_REFERRAL_SHARING';
