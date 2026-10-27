-- V13: importação a partir do currículo (PDF, DOCX ou TXT) além do ZIP do LinkedIn.
ALTER TABLE core.profile_import
    DROP CONSTRAINT profile_import_source_check,
    ADD CONSTRAINT profile_import_source_check CHECK (import_source IN ('LINKEDIN_ZIP', 'RESUME'));

ALTER TABLE core.experience
    DROP CONSTRAINT experience_source_check,
    ADD CONSTRAINT experience_source_check CHECK (source IN ('MANUAL', 'LINKEDIN_IMPORT', 'RESUME_IMPORT', 'INFERRED'));

ALTER TABLE core.usereducation
    DROP CONSTRAINT usereducation_source_check,
    ADD CONSTRAINT usereducation_source_check CHECK (source IN ('MANUAL', 'LINKEDIN_IMPORT', 'RESUME_IMPORT', 'INFERRED'));

ALTER TABLE core.userlanguage
    DROP CONSTRAINT userlanguage_source_check,
    ADD CONSTRAINT userlanguage_source_check CHECK (source IN ('MANUAL', 'LINKEDIN_IMPORT', 'RESUME_IMPORT', 'INFERRED'));

ALTER TABLE core.userskill
    DROP CONSTRAINT userskill_source_check,
    ADD CONSTRAINT userskill_source_check CHECK (source IN ('MANUAL', 'LINKEDIN_IMPORT', 'RESUME_IMPORT', 'INFERRED'));

COMMENT ON COLUMN core.experience.source IS 'De onde veio a experiência: MANUAL (digitada), LINKEDIN_IMPORT, RESUME_IMPORT ou INFERRED.';
COMMENT ON COLUMN core.profile_import.import_source IS 'LINKEDIN_ZIP (exportação de dados) ou RESUME (currículo enviado). O arquivo nunca é guardado.';
