-- V13: consent scope for care by facility staff (ADR-016): clinic encounters, assessments,
-- notes, diagnoses and care plans. Top-level: it doesn't depend on the screening scope.
ALTER TYPE consent.scope ADD VALUE IF NOT EXISTS 'CLINICAL_CARE';
