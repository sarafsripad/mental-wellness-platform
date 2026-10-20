-- LOCAL AND TEST ONLY (loaded via spring.flyway.locations in run-local.sh and tests).
-- Synthetic facilities and staff memberships for the local realm's fixed test users
-- (deploy/local/keycloak/wellness-local-realm.json): doctor.test1, counsellor.test1, nurse.test1.
INSERT INTO careteam.facility (facility_code, kind, facility_label) VALUES
  ('phc-testpur', 'PHC', 'PHC Testpur (synthetic)'),
  ('tele-centre-a', 'TELE_CENTRE', 'Tele-consultation centre A (synthetic)'),
  ('counselling-centre-a', 'COUNSELLING_CENTRE', 'Counselling centre A (synthetic)')
ON CONFLICT (facility_code) DO NOTHING;

INSERT INTO careteam.staff_membership (subject, facility_code, care_role, updated_by_subject) VALUES
  ('5f0d1c2a-6b1e-4c1a-9e00-0000000000d1', 'phc-testpur', 'CLINICIAN', 'local-seed'),
  ('5f0d1c2a-6b1e-4c1a-9e00-0000000000d1', 'tele-centre-a', 'CLINICIAN', 'local-seed'),
  ('5f0d1c2a-6b1e-4c1a-9e00-0000000000c1', 'counselling-centre-a', 'COUNSELLOR', 'local-seed'),
  ('5f0d1c2a-6b1e-4c1a-9e00-0000000000c1', 'tele-centre-a', 'COUNSELLOR', 'local-seed'),
  ('5f0d1c2a-6b1e-4c1a-9e00-0000000000e1', 'phc-testpur', 'HEALTH_STAFF', 'local-seed')
ON CONFLICT (subject, facility_code) DO NOTHING;
