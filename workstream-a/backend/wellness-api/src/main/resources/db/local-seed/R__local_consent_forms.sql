-- LOCAL AND TEST ONLY (loaded via spring.flyway.locations in run-local.sh and tests).
-- Placeholder consent form versions for synthetic data. Real forms need ethics-approved
-- wording and validated translations before they are registered in any other environment.
INSERT INTO consent.consent_form (form_id, form_version, language_tag, offered_scopes) VALUES
  ('screening-consent', '0.1.0', 'en-IN', ARRAY['SCREENING_PARTICIPATION','AUDIO_RECORDING','AUDIO_RETENTION','RESEARCH_FEATURE_REUSE','MODEL_TRAINING','QUALITY_REVIEW']::consent.scope[]),
  ('screening-consent', '0.1.0', 'kn-IN', ARRAY['SCREENING_PARTICIPATION','AUDIO_RECORDING','AUDIO_RETENTION','RESEARCH_FEATURE_REUSE','MODEL_TRAINING','QUALITY_REVIEW']::consent.scope[]),
  ('screening-consent-basic', '0.1.0', 'en-IN', ARRAY['SCREENING_PARTICIPATION','AUDIO_RECORDING']::consent.scope[])
ON CONFLICT (form_id, form_version, language_tag) DO NOTHING;
