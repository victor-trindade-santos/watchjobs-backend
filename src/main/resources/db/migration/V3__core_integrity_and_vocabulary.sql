-- 1. updated_at automático
CREATE OR REPLACE FUNCTION core.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$function$;

CREATE TRIGGER trg_job_source_updated_at BEFORE UPDATE ON core.job_source
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
CREATE TRIGGER trg_saved_job_contact_updated_at BEFORE UPDATE ON core.saved_job_contact
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

-- 2. Vocabulário canônico em saved_job
UPDATE core.saved_job SET work_mode = 'ON_SITE' WHERE work_mode = 'ONSITE';
UPDATE core.saved_job SET location_country_code = upper(location_country_code)
 WHERE location_country_code IS NOT NULL;

ALTER TABLE core.saved_job
    ADD CONSTRAINT saved_job_work_mode_check
        CHECK (work_mode IS NULL OR work_mode IN ('REMOTE', 'HYBRID', 'ON_SITE')),
    ADD CONSTRAINT saved_job_experience_level_check
        CHECK (experience_level IS NULL OR experience_level IN
               ('INTERN', 'TRAINEE', 'JUNIOR', 'MID', 'SENIOR', 'SPECIALIST', 'LEAD')),
    ADD CONSTRAINT saved_job_job_type_check
        CHECK (job_type IS NULL OR job_type IN ('CLT', 'PJ', 'INTERNSHIP', 'FREELANCE', 'TEMPORARY')),
    ADD CONSTRAINT saved_job_source_pair_check
        CHECK ((source_id IS NULL) = (external_id IS NULL)),
    ADD CONSTRAINT saved_job_country_code_check
        CHECK (location_country_code IS NULL OR location_country_code ~ '^[A-Z]{2}$'),
    ADD CONSTRAINT saved_job_salary_currency_check
        CHECK (salary_currency IS NULL OR salary_currency ~ '^[A-Z]{3}$');

-- 3. Candidatura
ALTER TABLE core.application
    ADD CONSTRAINT application_status_check
        CHECK (application_status IN
               ('APPLIED', 'IN_PROCESS', 'OFFER_RECEIVED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')),
    ADD CONSTRAINT application_dates_check
        CHECK (end_date IS NULL OR end_date >= start_date);
CREATE INDEX idx_application_users_id ON core.application (users_id);

-- 4. Preferências
ALTER TABLE core.userpreference
    ALTER COLUMN preferred_roles SET DEFAULT '{}',
    ADD CONSTRAINT userpreference_users_id_key UNIQUE (users_id),
    ADD CONSTRAINT userpreference_work_mode_check
        CHECK (preferred_work_mode IN ('REMOTE', 'HYBRID', 'ON_SITE', 'ANY')),
    ADD CONSTRAINT userpreference_experience_level_check
        CHECK (preferred_experience_level IN
               ('INTERN', 'TRAINEE', 'JUNIOR', 'MID', 'SENIOR', 'SPECIALIST', 'LEAD')),
    ADD CONSTRAINT userpreference_job_type_check
        CHECK (preferred_job_type IS NULL OR preferred_job_type IN
               ('CLT', 'PJ', 'INTERNSHIP', 'FREELANCE', 'TEMPORARY')),
    ADD CONSTRAINT userpreference_salary_range_check
        CHECK (preferred_salary_min IS NULL OR preferred_salary_max IS NULL
               OR preferred_salary_min <= preferred_salary_max),
    ADD CONSTRAINT userpreference_post_date_range_check
        CHECK (job_post_date_min IS NULL OR job_post_date_max IS NULL
               OR job_post_date_min <= job_post_date_max),
    ADD CONSTRAINT userpreference_profile_version_check CHECK (profile_version >= 1);

ALTER TABLE core.skilluserpreference
    ADD CONSTRAINT skilluserpreference_pref_skill_key UNIQUE (users_preference_id, skill_id);
ALTER TABLE core.languageuserpreference
    ADD CONSTRAINT languageuserpreference_pref_language_key UNIQUE (users_preference_id, language_id);
ALTER TABLE core.educationuserpreference
    ADD CONSTRAINT educationuserpreference_pref_education_key UNIQUE (users_preference_id, education_id);
ALTER TABLE core.certificationuserpreference
    ADD CONSTRAINT certificationuserpreference_pref_cert_key UNIQUE (users_preference_id, certification_id);

-- 5. Interações com vagas do corpus
DELETE FROM core.usersjobinteraction
 WHERE users_id IS NULL OR discovered_job_id IS NULL OR interaction_type IS NULL;
UPDATE core.usersjobinteraction
   SET created_at = COALESCE(created_at, now()),
       updated_at = COALESCE(updated_at, created_at, now());
DROP INDEX core.idx_usersjobinteraction_user_discovered_job;
ALTER TABLE core.usersjobinteraction
    ALTER COLUMN users_id SET NOT NULL,
    ALTER COLUMN discovered_job_id SET NOT NULL,
    ALTER COLUMN interaction_type TYPE varchar(20),
    ALTER COLUMN interaction_type SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT usersjobinteraction_type_check
        CHECK (interaction_type IN ('VIEWED', 'DISMISSED', 'HIDDEN')),
    ADD CONSTRAINT usersjobinteraction_user_job_type_key
        UNIQUE (users_id, discovered_job_id, interaction_type);
CREATE TRIGGER trg_usersjobinteraction_updated_at BEFORE UPDATE ON core.usersjobinteraction
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

-- 6. Perfil
UPDATE core.experience SET "current" = (end_date IS NULL)
 WHERE "current" IS NULL OR ("current" AND end_date IS NOT NULL);
ALTER TABLE core.experience
    ALTER COLUMN "current" SET DEFAULT false,
    ALTER COLUMN "current" SET NOT NULL,
    ADD CONSTRAINT experience_type_check
        CHECK (experience_type IN ('EMPLOYMENT', 'INTERNSHIP', 'FREELANCE', 'VOLUNTEER', 'OTHER')),
    ADD CONSTRAINT experience_dates_check CHECK (end_date IS NULL OR end_date >= start_date),
    ADD CONSTRAINT experience_current_check CHECK (NOT "current" OR end_date IS NULL);
CREATE INDEX idx_experience_users_id ON core.experience (users_id);

ALTER TABLE core.usereducation
    ALTER COLUMN end_date DROP NOT NULL,
    ADD CONSTRAINT usereducation_dates_check CHECK (end_date IS NULL OR end_date >= start_date),
    ADD CONSTRAINT usereducation_finished_check CHECK ("current" OR end_date IS NOT NULL);
CREATE INDEX idx_usereducation_users_id ON core.usereducation (users_id);

ALTER TABLE core.usercertification
    ALTER COLUMN expiration_date DROP NOT NULL,
    ADD CONSTRAINT usercertification_dates_check
        CHECK (expiration_date IS NULL OR expiration_date >= issue_date),
    ADD CONSTRAINT usercertification_user_cert_key UNIQUE (users_id, certification_id);

ALTER TABLE core.userskill
    ADD CONSTRAINT userskill_user_skill_key UNIQUE (users_id, skill_id),
    ADD CONSTRAINT userskill_level_check
        CHECK (skill_level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'EXPERT')),
    ADD CONSTRAINT userskill_years_check CHECK (years_experience IS NULL OR years_experience >= 0);

ALTER TABLE core.userlanguage
    ALTER COLUMN proficiency_level TYPE varchar(6),
    ADD CONSTRAINT userlanguage_user_language_key UNIQUE (users_id, language_id),
    ADD CONSTRAINT userlanguage_level_check
        CHECK (proficiency_level IN ('A1', 'A2', 'B1', 'B2', 'C1', 'C2', 'NATIVE'));

-- 7. Catálogos
ALTER TABLE core.applicationstage
    ADD COLUMN sort_order smallint,
    ADD CONSTRAINT applicationstage_name_key UNIQUE (name);
UPDATE core.applicationstage SET sort_order = application_stage_id;
ALTER TABLE core.applicationstage ALTER COLUMN sort_order SET NOT NULL;

CREATE UNIQUE INDEX uq_language_name_lower ON core."language" (lower(language_name));
ALTER TABLE core.education
    ADD CONSTRAINT education_level_field_key UNIQUE (education_level, field_of_study);
ALTER TABLE core.certification
    ADD CONSTRAINT certification_name_issuer_key UNIQUE (certification_name, issuer);

-- 8. Cascatas LGPD e nomes padronizados
ALTER TABLE core.userscontact
    DROP CONSTRAINT userscontact_users_id_fkey,
    ADD CONSTRAINT fk_userscontact_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE;

ALTER TABLE core.usersjobinteraction
    DROP CONSTRAINT usersjobinteraction_users_id_fkey,
    ADD CONSTRAINT fk_usersjobinteraction_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE;

ALTER TABLE core.userskill
    DROP CONSTRAINT users_id,
    DROP CONSTRAINT skill_id,
    ADD CONSTRAINT fk_userskill_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_userskill_skill_id FOREIGN KEY (skill_id) REFERENCES core.skill (skill_id);

ALTER TABLE core.experience
    DROP CONSTRAINT users_id,
    DROP CONSTRAINT organization_id,
    ADD CONSTRAINT fk_experience_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_experience_organization_id FOREIGN KEY (organization_id)
        REFERENCES core.organization (organization_id);

ALTER TABLE core.saved_job
    DROP CONSTRAINT fk_saved_job_users_id,
    ADD CONSTRAINT fk_saved_job_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE;

ALTER TABLE core.usercertification
    DROP CONSTRAINT users_id,
    DROP CONSTRAINT certification_id,
    ADD CONSTRAINT fk_usercertification_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_usercertification_certification_id FOREIGN KEY (certification_id)
        REFERENCES core.certification (certification_id);

ALTER TABLE core.usereducation
    DROP CONSTRAINT users_id,
    DROP CONSTRAINT education_id,
    ADD CONSTRAINT fk_usereducation_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_usereducation_education_id FOREIGN KEY (education_id)
        REFERENCES core.education (education_id);

ALTER TABLE core.userlanguage
    DROP CONSTRAINT users_id,
    DROP CONSTRAINT language_id,
    ADD CONSTRAINT fk_userlanguage_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_userlanguage_language_id FOREIGN KEY (language_id)
        REFERENCES core."language" (language_id);

ALTER TABLE core.userpreference
    DROP CONSTRAINT fk_preference_user,
    ADD CONSTRAINT fk_userpreference_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE;

ALTER TABLE core.application
    DROP CONSTRAINT users_id,
    DROP CONSTRAINT application_stage_id,
    ADD CONSTRAINT fk_application_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_application_stage_id FOREIGN KEY (application_stage_id)
        REFERENCES core.applicationstage (application_stage_id);

ALTER TABLE core.coverletter
    DROP CONSTRAINT users_id,
    DROP CONSTRAINT coverletter_saved_job_id_fkey,
    ADD CONSTRAINT fk_coverletter_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_coverletter_saved_job_id FOREIGN KEY (saved_job_id)
        REFERENCES core.saved_job (saved_job_id) ON DELETE CASCADE;

ALTER TABLE core.resume
    DROP CONSTRAINT users_id,
    DROP CONSTRAINT resume_saved_job_id_fkey,
    ADD CONSTRAINT fk_resume_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_resume_saved_job_id FOREIGN KEY (saved_job_id)
        REFERENCES core.saved_job (saved_job_id) ON DELETE CASCADE;

ALTER TABLE core.skilluserpreference
    DROP CONSTRAINT fk_user_preference_skill,
    ADD CONSTRAINT fk_user_preference_skill FOREIGN KEY (users_preference_id)
        REFERENCES core.userpreference (users_preference_id) ON DELETE CASCADE;

ALTER TABLE core.educationuserpreference
    DROP CONSTRAINT fk_user_preference_education,
    ADD CONSTRAINT fk_user_preference_education FOREIGN KEY (users_preference_id)
        REFERENCES core.userpreference (users_preference_id) ON DELETE CASCADE;

ALTER TABLE core.certificationuserpreference
    DROP CONSTRAINT fk_user_preference_certification,
    ADD CONSTRAINT fk_user_preference_certification FOREIGN KEY (users_preference_id)
        REFERENCES core.userpreference (users_preference_id) ON DELETE CASCADE;

ALTER TABLE core.languageuserpreference
    DROP CONSTRAINT users_preference_id,
    DROP CONSTRAINT language_id,
    ADD CONSTRAINT fk_languageuserpreference_preference_id FOREIGN KEY (users_preference_id)
        REFERENCES core.userpreference (users_preference_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_languageuserpreference_language_id FOREIGN KEY (language_id)
        REFERENCES core."language" (language_id);

COMMENT ON COLUMN core.applicationstage.sort_order IS 'Ordem em que a etapa aparece na lista. Não é o identificador.';

