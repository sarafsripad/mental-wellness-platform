-- LOCAL AND TEST ONLY (loaded via spring.flyway.locations in run-local.sh and tests).
-- Placeholder consent form versions for synthetic data. Real forms need ethics-approved
-- wording and validated translations before they are registered in any other environment.
INSERT INTO consent.consent_form (form_id, form_version, language_tag, offered_scopes) VALUES
  ('screening-consent', '0.1.0', 'en-IN', ARRAY['SCREENING_PARTICIPATION','AUDIO_RECORDING','AUDIO_RETENTION','RESEARCH_FEATURE_REUSE','MODEL_TRAINING','QUALITY_REVIEW']::consent.scope[]),
  ('screening-consent', '0.1.0', 'kn-IN', ARRAY['SCREENING_PARTICIPATION','AUDIO_RECORDING','AUDIO_RETENTION','RESEARCH_FEATURE_REUSE','MODEL_TRAINING','QUALITY_REVIEW']::consent.scope[]),
  ('screening-consent-basic', '0.1.0', 'en-IN', ARRAY['SCREENING_PARTICIPATION','AUDIO_RECORDING']::consent.scope[]),
  -- ASHA web app (ADR-013): participation in the visit questions only, no audio. Every language
  -- except en-IN uses unreviewed prototype translations.
  ('asha-wellness-consent', '0.1.0', 'en-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'te-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'hi-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'ta-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'bn-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'mr-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'kn-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'ml-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'gu-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'pa-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'ur-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[]),
  ('asha-wellness-consent', '0.1.0', 'or-IN', ARRAY['SCREENING_PARTICIPATION']::consent.scope[])
ON CONFLICT (form_id, form_version, language_tag) DO NOTHING;
