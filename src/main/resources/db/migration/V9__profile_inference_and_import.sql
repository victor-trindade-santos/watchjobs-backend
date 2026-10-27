-- V9: perfil automático e importação do LinkedIn (F3)

-- 1. Origem dos dados do perfil, importação sem duplicar e classificação das experiências
ALTER TABLE core.experience
    ADD COLUMN source varchar(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN external_ref varchar(64) NULL,
    ADD COLUMN role_family varchar(50) NULL,
    ADD COLUMN inferred_level varchar(30) NULL,
    ADD COLUMN classification_source varchar(10) NULL,
    ADD COLUMN classification_confidence numeric(4, 3) NULL,
    ADD CONSTRAINT experience_source_check CHECK (source IN ('MANUAL', 'LINKEDIN_IMPORT', 'INFERRED')),
    ADD CONSTRAINT fk_experience_role_family FOREIGN KEY (role_family) REFERENCES core.role_family (code),
    ADD CONSTRAINT experience_inferred_level_check CHECK (inferred_level IS NULL OR inferred_level IN
        ('INTERN', 'TRAINEE', 'JUNIOR', 'MID', 'SENIOR', 'SPECIALIST', 'LEAD')),
    ADD CONSTRAINT experience_classification_source_check
        CHECK (classification_source IS NULL OR classification_source IN ('RULES', 'MODEL', 'MANUAL')),
    ADD CONSTRAINT experience_classification_confidence_check
        CHECK (classification_confidence IS NULL OR classification_confidence BETWEEN 0 AND 1);
CREATE UNIQUE INDEX uq_experience_external_ref ON core.experience (users_id, external_ref)
    WHERE external_ref IS NOT NULL;

COMMENT ON COLUMN core.experience.source IS 'De onde veio a experiência: MANUAL (digitada), LINKEDIN_IMPORT ou INFERRED.';
COMMENT ON COLUMN core.experience.external_ref IS 'SHA-256 dos campos-chave da linha importada. Evita duplicar na reimportação.';
COMMENT ON COLUMN core.experience.role_family IS 'Área deduzida do cargo pelas regras de classificação.';
COMMENT ON COLUMN core.experience.inferred_level IS 'Nível deduzido do cargo (palavra-chave), quando houver.';
COMMENT ON COLUMN core.experience.classification_source IS 'Quem classificou a área e o nível: RULES, MODEL ou MANUAL.';
COMMENT ON COLUMN core.experience.classification_confidence IS 'Confiança da classificação, de 0 a 1.';

ALTER TABLE core.usereducation
    ADD COLUMN source varchar(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN external_ref varchar(64) NULL,
    ADD CONSTRAINT usereducation_source_check CHECK (source IN ('MANUAL', 'LINKEDIN_IMPORT', 'INFERRED'));
CREATE UNIQUE INDEX uq_usereducation_external_ref ON core.usereducation (users_id, external_ref)
    WHERE external_ref IS NOT NULL;

COMMENT ON COLUMN core.usereducation.source IS 'De onde veio a formação: MANUAL, LINKEDIN_IMPORT ou INFERRED.';
COMMENT ON COLUMN core.usereducation.external_ref IS 'SHA-256 dos campos-chave da linha importada. Evita duplicar na reimportação.';

ALTER TABLE core.userlanguage
    ADD COLUMN source varchar(20) NOT NULL DEFAULT 'MANUAL',
    ADD CONSTRAINT userlanguage_source_check CHECK (source IN ('MANUAL', 'LINKEDIN_IMPORT', 'INFERRED'));

COMMENT ON COLUMN core.userlanguage.source IS 'De onde veio o idioma: MANUAL, LINKEDIN_IMPORT ou INFERRED.';

ALTER TABLE core.userskill
    ADD COLUMN source varchar(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN years_source varchar(20) NULL,
    ADD CONSTRAINT userskill_source_check CHECK (source IN ('MANUAL', 'LINKEDIN_IMPORT', 'INFERRED')),
    ADD CONSTRAINT userskill_years_source_check
        CHECK (years_source IS NULL OR years_source IN ('MANUAL', 'INFERRED'));

COMMENT ON COLUMN core.userskill.source IS 'De onde veio a skill: MANUAL, LINKEDIN_IMPORT ou INFERRED.';
COMMENT ON COLUMN core.userskill.years_source IS 'Quem informou os anos de experiência: MANUAL (usuário) ou INFERRED (perfil automático).';

-- Preferências: a linha nasce na primeira mudança de perfil (para o profile_version existir),
-- antes de o usuário confirmar; por isso o nível pode ficar vazio até a confirmação.
ALTER TABLE core.userpreference
    ALTER COLUMN preferred_experience_level DROP NOT NULL,
    ADD COLUMN confirmed_at timestamptz NULL;

COMMENT ON COLUMN core.userpreference.confirmed_at IS
    'Quando o usuário salvou as preferências pela primeira vez. Vazio = linha criada só para versionar o perfil.';

-- 2. Perfil deduzido (uma linha por usuário)
CREATE TABLE core.user_profile_summary (
    users_id int4 NOT NULL,
    primary_role_family varchar(50) NULL,
    experience_level varchar(30) NULL,
    level_confidence varchar(10) NULL,
    level_source varchar(10) NOT NULL DEFAULT 'RULES',
    total_years numeric(4, 1) NOT NULL DEFAULT 0,
    internship_years numeric(4, 1) NOT NULL DEFAULT 0,
    years_by_family jsonb NOT NULL DEFAULT '{}'::jsonb,
    years_by_skill jsonb NOT NULL DEFAULT '{}'::jsonb,
    reasons jsonb NOT NULL DEFAULT '[]'::jsonb,
    rules_version varchar(16) NULL,
    profile_version int4 NOT NULL,
    computed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_profile_summary_pkey PRIMARY KEY (users_id),
    CONSTRAINT fk_user_profile_summary_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_profile_summary_role_family FOREIGN KEY (primary_role_family)
        REFERENCES core.role_family (code),
    CONSTRAINT user_profile_summary_level_check CHECK (experience_level IS NULL OR experience_level IN
        ('INTERN', 'TRAINEE', 'JUNIOR', 'MID', 'SENIOR', 'SPECIALIST', 'LEAD')),
    CONSTRAINT user_profile_summary_confidence_check
        CHECK (level_confidence IS NULL OR level_confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT user_profile_summary_level_source_check CHECK (level_source IN ('RULES', 'MODEL', 'MANUAL')),
    CONSTRAINT user_profile_summary_years_check CHECK (total_years >= 0 AND internship_years >= 0),
    CONSTRAINT user_profile_summary_json_check CHECK (
        jsonb_typeof(years_by_family) = 'object' AND jsonb_typeof(years_by_skill) = 'object'
        AND jsonb_typeof(reasons) = 'array')
);

COMMENT ON TABLE core.user_profile_summary IS
    'Perfil profissional deduzido pelo motor (senioridade, área, anos). Recalculado a cada mudança de perfil.';
COMMENT ON COLUMN core.user_profile_summary.level_source IS
    'MANUAL quando o usuário corrigiu a senioridade: o recálculo passa a respeitá-la.';
COMMENT ON COLUMN core.user_profile_summary.reasons IS 'Motivos em texto, para o usuário entender a dedução.';
COMMENT ON COLUMN core.user_profile_summary.profile_version IS 'Versão do perfil (userpreference.profile_version) usada no cálculo.';

-- 3. Prévia de importação (o arquivo nunca é guardado)
CREATE TABLE core.profile_import (
    profile_import_id bigint GENERATED ALWAYS AS IDENTITY,
    users_id int4 NOT NULL,
    import_source varchar(20) NOT NULL DEFAULT 'LINKEDIN_ZIP',
    status varchar(20) NOT NULL DEFAULT 'PREVIEW',
    file_sha256 char(64) NOT NULL,
    preview jsonb NOT NULL,
    items_total int4 NOT NULL DEFAULT 0,
    items_applied int4 NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz NULL,
    expires_at timestamptz NOT NULL DEFAULT now() + interval '7 days',
    CONSTRAINT profile_import_pkey PRIMARY KEY (profile_import_id),
    CONSTRAINT fk_profile_import_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT profile_import_source_check CHECK (import_source IN ('LINKEDIN_ZIP')),
    CONSTRAINT profile_import_status_check CHECK (status IN ('PREVIEW', 'APPLIED', 'DISCARDED')),
    CONSTRAINT profile_import_counters_check
        CHECK (items_total >= 0 AND items_applied >= 0 AND items_applied <= items_total),
    CONSTRAINT profile_import_preview_object_check CHECK (jsonb_typeof(preview) = 'object')
);
CREATE INDEX idx_profile_import_users_time ON core.profile_import (users_id, created_at DESC);

COMMENT ON TABLE core.profile_import IS
    'Prévia de uma importação do LinkedIn, válida por 7 dias. Guarda só o JSON da prévia e o SHA-256 do arquivo.';

-- 4. Limpeza: prévias vencidas somem; importações aplicadas ficam só como registro, sem os dados
CREATE OR REPLACE FUNCTION core.purge_profile_imports()
RETURNS void
LANGUAGE sql
AS $function$
    UPDATE core.profile_import SET preview = '{}'::jsonb
     WHERE status = 'APPLIED' AND preview <> '{}'::jsonb;
    DELETE FROM core.profile_import
     WHERE status <> 'APPLIED' AND expires_at < now();
$function$;
