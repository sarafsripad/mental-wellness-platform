-- V15 (ADR-016): a participant registered at a facility has no ASHA worker yet. The assignment
-- row also anchors the participant's consent events, so the worker becomes optional. An ASHA
-- worker who later records consent for that participant becomes their assigned worker.
ALTER TABLE consent.participant_assignment ALTER COLUMN worker_subject DROP NOT NULL;
