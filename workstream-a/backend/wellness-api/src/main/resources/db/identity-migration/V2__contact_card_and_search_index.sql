-- identity_db V2 (ADR-016): optional phone for tele-consultations, a patient card code for finding a
-- patient at the clinic, and a blind search index. The index is an HMAC of the normalised name and
-- village under its own key, so exact matching works without storing or searching plaintext.
ALTER TABLE identity.person
    ADD COLUMN contact_phone_enc  bytea CHECK (contact_phone_enc IS NULL OR octet_length(contact_phone_enc) BETWEEN 29 AND 256),
    ADD COLUMN patient_card_code  text  UNIQUE CHECK (patient_card_code ~ '^[A-Z2-9]{8}$'),
    ADD COLUMN search_index       text  CHECK (search_index ~ '^[a-f0-9]{64}$');
CREATE INDEX person_search_idx ON identity.person (search_index);
