-- V7: normalização de catálogos e regras de classificação compartilhadas por vagas e perfis.

-- O normalized_name é calculado pelo app (NameNormalizer); aqui só se preenchem as linhas antigas.
ALTER TABLE core.organization
    ADD COLUMN normalized_name varchar(150),
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
UPDATE core.organization
   SET normalized_name = lower(btrim(regexp_replace(name, '\s+', ' ', 'g'))),
       organization_type = upper(organization_type);
-- varchar(20) não cabe EDUCATIONAL_INSTITUTION (23 caracteres).
ALTER TABLE core.organization
    ALTER COLUMN organization_type TYPE varchar(30),
    ALTER COLUMN normalized_name SET NOT NULL,
    ADD CONSTRAINT organization_type_check CHECK (organization_type IN
        ('COMPANY', 'EDUCATIONAL_INSTITUTION', 'RECRUITING_AGENCY', 'GOVERNMENT', 'OTHER')),
    ADD CONSTRAINT organization_type_normalized_name_key UNIQUE (organization_type, normalized_name);
COMMENT ON COLUMN core.organization.normalized_name IS
    'Nome em minúsculas, sem acento, sem espaços repetidos e sem sufixos societários. Calculado pelo app.';

UPDATE core."location" SET country_code = upper(country_code);
ALTER TABLE core."location"
    ADD CONSTRAINT location_country_code_check CHECK (country_code ~ '^[A-Z]{2}$');
CREATE UNIQUE INDEX uq_location_natural ON core."location"
    (country_code, lower(COALESCE(state, '')), lower(COALESCE(city, '')), lower(COALESCE(neighborhood, '')));

CREATE UNIQUE INDEX uq_skill_name_lower ON core.skill (lower(skill_name));

CREATE TABLE core.skill_alias (
    skill_alias_id int4 GENERATED ALWAYS AS IDENTITY,
    skill_id int4 NOT NULL,
    alias varchar(100) NOT NULL,
    normalized_alias varchar(100) NOT NULL,
    match_policy varchar(20) NOT NULL DEFAULT 'WORD',
    CONSTRAINT skill_alias_pkey PRIMARY KEY (skill_alias_id),
    CONSTRAINT fk_skill_alias_skill_id FOREIGN KEY (skill_id)
        REFERENCES core.skill (skill_id) ON DELETE CASCADE,
    CONSTRAINT skill_alias_normalized_alias_key UNIQUE (normalized_alias),
    CONSTRAINT skill_alias_match_policy_check
        CHECK (match_policy IN ('WORD', 'EXACT_CASE', 'CONTEXT_REQUIRED', 'DISABLED'))
);
CREATE INDEX idx_skill_alias_skill_id ON core.skill_alias (skill_id);
COMMENT ON TABLE core.skill_alias IS 'Apelidos de skills usados pelo extrator ("nodejs", "node.js" -> Node.js).';
COMMENT ON COLUMN core.skill_alias.match_policy IS
    'WORD = palavra inteira; EXACT_CASE = respeita maiúsculas; CONTEXT_REQUIRED = só com contexto ("Go", "R"); DISABLED = ignorado.';

-- Regras de classificação compartilhadas por vagas e perfis
CREATE TABLE core.role_family (
    code varchar(50) NOT NULL,
    name varchar(100) NOT NULL,
    sort_order smallint NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    CONSTRAINT role_family_pkey PRIMARY KEY (code),
    CONSTRAINT role_family_code_format_check CHECK (code ~ '^[A-Z][A-Z0-9_]{1,49}$')
);
COMMENT ON TABLE core.role_family IS 'Áreas de atuação (BACKEND, FRONTEND...). A lista cresce por INSERT.';

-- NULLS NOT DISTINCT: uma regra sem idioma também é única (permite ON CONFLICT nas sementes).
CREATE TABLE core.classification_rule (
    classification_rule_id int4 GENERATED ALWAYS AS IDENTITY,
    rule_type varchar(20) NOT NULL,
    pattern varchar(150) NOT NULL,
    match_type varchar(10) NOT NULL DEFAULT 'WORD',
    target_value varchar(50) NOT NULL,
    priority smallint NOT NULL DEFAULT 100,
    language_code varchar(5) NULL,
    applies_to varchar(10) NOT NULL DEFAULT 'BOTH',
    is_active boolean NOT NULL DEFAULT true,
    notes text NULL,
    CONSTRAINT classification_rule_pkey PRIMARY KEY (classification_rule_id),
    CONSTRAINT classification_rule_type_check
        CHECK (rule_type IN ('ROLE_FAMILY', 'SENIORITY', 'WORK_MODE', 'JOB_TYPE')),
    CONSTRAINT classification_rule_match_type_check CHECK (match_type IN ('WORD', 'PHRASE', 'REGEX')),
    CONSTRAINT classification_rule_applies_to_check CHECK (applies_to IN ('JOB', 'PROFILE', 'BOTH')),
    CONSTRAINT classification_rule_pattern_lower_check CHECK (pattern = lower(pattern)),
    CONSTRAINT classification_rule_target_check CHECK (
           (rule_type = 'SENIORITY' AND target_value IN
                ('INTERN', 'TRAINEE', 'JUNIOR', 'MID', 'SENIOR', 'SPECIALIST', 'LEAD'))
        OR (rule_type = 'WORK_MODE' AND target_value IN ('REMOTE', 'HYBRID', 'ON_SITE'))
        OR (rule_type = 'JOB_TYPE' AND target_value IN ('CLT', 'PJ', 'INTERNSHIP', 'FREELANCE', 'TEMPORARY'))
        OR rule_type = 'ROLE_FAMILY'),
    CONSTRAINT classification_rule_key UNIQUE NULLS NOT DISTINCT (rule_type, pattern, language_code)
);
CREATE INDEX idx_classification_rule_active ON core.classification_rule (rule_type, priority) WHERE is_active;
COMMENT ON TABLE core.classification_rule IS
    'Palavra ou expressão normalizada -> valor. Menor prioridade vence. Usada nas vagas e no perfil.';
COMMENT ON COLUMN core.classification_rule.match_type IS
    'WORD = palavra solta (confiança 0,6); PHRASE = expressão exata (1,0); REGEX = expressão regular (0,8).';

CREATE TABLE core.seniority_band (
    seniority_band_id int4 GENERATED ALWAYS AS IDENTITY,
    role_family varchar(50) NULL,
    min_years numeric(4, 1) NOT NULL,
    max_years numeric(4, 1) NULL,
    experience_level varchar(30) NOT NULL,
    CONSTRAINT seniority_band_pkey PRIMARY KEY (seniority_band_id),
    CONSTRAINT fk_seniority_band_role_family FOREIGN KEY (role_family)
        REFERENCES core.role_family (code),
    CONSTRAINT seniority_band_years_check
        CHECK (min_years >= 0 AND (max_years IS NULL OR max_years > min_years)),
    CONSTRAINT seniority_band_level_check CHECK (experience_level IN
        ('INTERN', 'TRAINEE', 'JUNIOR', 'MID', 'SENIOR', 'SPECIALIST', 'LEAD'))
);
CREATE UNIQUE INDEX uq_seniority_band_scope ON core.seniority_band (COALESCE(role_family, '*'), min_years);
COMMENT ON TABLE core.seniority_band IS 'Faixas de anos de experiência -> nível, gerais (role_family nulo) ou por área.';
