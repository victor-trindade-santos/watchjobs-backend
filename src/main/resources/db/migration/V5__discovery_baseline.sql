-- V5: schema discovery (corpus de vagas descobertas), criado do zero pelo Flyway.
-- Base: schema_discovery do Neon, limpo (sem AUTHORIZATION, sem sequências duplicadas,
-- sem partições de datas fixas e sem ON ONLY nos índices). As partições são diárias, em UTC.

CREATE SCHEMA IF NOT EXISTS discovery;

COMMENT ON SCHEMA discovery IS
    'Corpus de vagas coletadas das fontes. Particionado por dia; as vagas ficam 30 dias. Nada aqui é dado do usuário.';

CREATE SEQUENCE discovery.discovered_job_id_seq;
CREATE SEQUENCE discovery.raw_payload_id_seq;

-- 1. Segmentos de busca (agregadores) e quem os pede
CREATE TABLE discovery.coverage_segment (
    segment_id bigint GENERATED ALWAYS AS IDENTITY,
    role_family varchar(50) NOT NULL,
    country_code varchar(2) NOT NULL DEFAULT 'BR',
    work_mode varchar(20) NOT NULL DEFAULT 'ANY',
    keywords varchar(200) NULL,
    demand_score int4 NOT NULL DEFAULT 0,
    is_active boolean NOT NULL DEFAULT true,
    last_ingested_at timestamptz NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT coverage_segment_pkey PRIMARY KEY (segment_id),
    CONSTRAINT coverage_segment_key UNIQUE (role_family, country_code, work_mode),
    CONSTRAINT coverage_segment_work_mode_check CHECK (work_mode IN ('REMOTE', 'HYBRID', 'ON_SITE', 'ANY')),
    CONSTRAINT coverage_segment_country_code_check CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT coverage_segment_demand_check CHECK (demand_score >= 0)
);
COMMENT ON TABLE discovery.coverage_segment IS
    'Segmento coletado pelas fontes que buscam por palavra-chave (área + país + modo).';
COMMENT ON COLUMN discovery.coverage_segment.keywords IS 'Termo enviado ao agregador. Sem ele, usa o nome da área.';
COMMENT ON COLUMN discovery.coverage_segment.demand_score IS 'Quantas preferências pedem o segmento. Maior = coletado antes.';

CREATE TABLE discovery.segment_demand (
    segment_demand_id bigint GENERATED ALWAYS AS IDENTITY,
    segment_id bigint NOT NULL,
    users_preference_id int4 NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT segment_demand_pkey PRIMARY KEY (segment_demand_id),
    CONSTRAINT fk_segment_demand_segment_id FOREIGN KEY (segment_id)
        REFERENCES discovery.coverage_segment (segment_id) ON DELETE CASCADE,
    CONSTRAINT fk_segment_demand_users_preference_id FOREIGN KEY (users_preference_id)
        REFERENCES core.userpreference (users_preference_id) ON DELETE CASCADE,
    CONSTRAINT segment_demand_segment_preference_key UNIQUE (segment_id, users_preference_id)
);
COMMENT ON TABLE discovery.segment_demand IS 'Liga uma preferência de usuário aos segmentos que ela pede.';

-- 2. Grupos de vagas iguais entre fontes (B2)
CREATE TABLE discovery.job_group (
    job_group_id bigint GENERATED ALWAYS AS IDENTITY,
    fingerprint char(64) NOT NULL,
    canonical_discovered_job_id bigint NULL,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT job_group_pkey PRIMARY KEY (job_group_id),
    CONSTRAINT job_group_fingerprint_key UNIQUE (fingerprint)
);
COMMENT ON TABLE discovery.job_group IS
    'Vagas iguais vindas de fontes diferentes. Sem FK para a representante, de propósito (partições).';

-- 3. Coletas e fila
CREATE TABLE discovery.ingestion_run (
    ingestion_run_id bigint GENERATED ALWAYS AS IDENTITY,
    source_id bigint NOT NULL,
    segment_id bigint NULL,
    status varchar(20) NOT NULL DEFAULT 'COMPLETED',
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz NULL,
    jobs_fetched int4 NOT NULL DEFAULT 0,
    jobs_new int4 NOT NULL DEFAULT 0,
    jobs_updated int4 NOT NULL DEFAULT 0,
    jobs_deduplicated int4 NOT NULL DEFAULT 0,
    jobs_rejected int4 NOT NULL DEFAULT 0,
    error_message text NULL,
    CONSTRAINT ingestion_run_pkey PRIMARY KEY (ingestion_run_id),
    CONSTRAINT fk_ingestion_run_source_id FOREIGN KEY (source_id) REFERENCES core.job_source (job_source_id),
    CONSTRAINT fk_ingestion_run_segment_id FOREIGN KEY (segment_id)
        REFERENCES discovery.coverage_segment (segment_id) ON DELETE SET NULL,
    CONSTRAINT ingestion_run_status_check CHECK (status IN ('COMPLETED', 'PARTIAL', 'FAILED'))
);
CREATE INDEX idx_ingestion_run_source_time ON discovery.ingestion_run (source_id, started_at DESC);
COMMENT ON TABLE discovery.ingestion_run IS 'Auditoria de cada coleta: uma linha por alvo (empresa, segmento ou lote).';

CREATE TABLE discovery.ingestion_task (
    ingestion_task_id bigint GENERATED ALWAYS AS IDENTITY,
    task_type varchar(30) NOT NULL,
    source_id bigint NULL,
    segment_id bigint NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    priority smallint NOT NULL DEFAULT 5,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    idempotency_key varchar(150) NULL,
    attempts int4 NOT NULL DEFAULT 0,
    max_attempts int4 NOT NULL DEFAULT 3,
    run_after timestamptz NOT NULL DEFAULT now(),
    locked_until timestamptz NULL,
    last_error text NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz NULL,
    CONSTRAINT ingestion_task_pkey PRIMARY KEY (ingestion_task_id),
    CONSTRAINT fk_ingestion_task_source_id FOREIGN KEY (source_id) REFERENCES core.job_source (job_source_id),
    CONSTRAINT fk_ingestion_task_segment_id FOREIGN KEY (segment_id)
        REFERENCES discovery.coverage_segment (segment_id) ON DELETE SET NULL,
    CONSTRAINT ingestion_task_idempotency_key_key UNIQUE (idempotency_key),
    CONSTRAINT ingestion_task_task_type_check
        CHECK (task_type IN ('INGEST_SEGMENT', 'VERIFY_LINKS', 'CLEANUP_PARTITIONS', 'REPROCESS_SEGMENT')),
    CONSTRAINT ingestion_task_status_check CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'DEAD')),
    CONSTRAINT ingestion_task_attempts_check CHECK (attempts >= 0 AND max_attempts >= 1),
    CONSTRAINT ingestion_task_payload_object_check CHECK (jsonb_typeof(payload) = 'object')
);
CREATE INDEX idx_ingestion_task_pending ON discovery.ingestion_task (priority, run_after) WHERE status = 'PENDING';
COMMENT ON TABLE discovery.ingestion_task IS 'Fila de tarefas do motor, com trava de linha e idempotência.';

-- 4. Corpus (particionado por dia em discovered_at)
CREATE TABLE discovery.discovered_job (
    discovered_job_id bigint NOT NULL DEFAULT nextval('discovery.discovered_job_id_seq'),
    discovered_at timestamptz NOT NULL DEFAULT now(),
    source_id bigint NOT NULL,
    external_id varchar(255) NOT NULL,
    job_group_id bigint NULL,
    organization_id bigint NULL,
    location_id bigint NULL,
    title varchar(255) NOT NULL,
    organization_name varchar(150) NOT NULL,
    job_url text NOT NULL,
    work_mode varchar(20) NULL,
    experience_level varchar(30) NULL,
    job_type varchar(20) NULL,
    salary_min numeric(10, 2) NULL,
    salary_max numeric(10, 2) NULL,
    salary_currency char(3) NULL,
    salary_period varchar(20) NULL,
    full_description text NULL,
    posted_at timestamptz NULL,
    expires_at timestamptz NOT NULL,
    corpus_status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    fingerprint char(64) NULL,
    content_hash char(64) NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT discovered_job_pkey PRIMARY KEY (discovered_job_id, discovered_at),
    CONSTRAINT discovered_job_source_id_fkey FOREIGN KEY (source_id) REFERENCES core.job_source (job_source_id),
    CONSTRAINT discovered_job_job_group_id_fkey FOREIGN KEY (job_group_id)
        REFERENCES discovery.job_group (job_group_id) ON DELETE SET NULL,
    CONSTRAINT discovered_job_organization_id_fkey FOREIGN KEY (organization_id)
        REFERENCES core.organization (organization_id),
    CONSTRAINT discovered_job_location_id_fkey FOREIGN KEY (location_id)
        REFERENCES core."location" (location_id),
    CONSTRAINT discovered_job_work_mode_check
        CHECK (work_mode IS NULL OR work_mode IN ('REMOTE', 'HYBRID', 'ON_SITE')),
    CONSTRAINT discovered_job_experience_level_check CHECK (experience_level IS NULL OR experience_level IN
        ('INTERN', 'TRAINEE', 'JUNIOR', 'MID', 'SENIOR', 'SPECIALIST', 'LEAD')),
    CONSTRAINT discovered_job_job_type_check
        CHECK (job_type IS NULL OR job_type IN ('CLT', 'PJ', 'INTERNSHIP', 'FREELANCE')),
    CONSTRAINT discovered_job_corpus_status_check
        CHECK (corpus_status IN ('ACTIVE', 'PROBABLY_CLOSED', 'CLOSED', 'DEAD')),
    CONSTRAINT discovered_job_salary_range_check
        CHECK (salary_min IS NULL OR salary_max IS NULL OR salary_min <= salary_max),
    CONSTRAINT discovered_job_expires_check CHECK (expires_at > discovered_at)
) PARTITION BY RANGE (discovered_at);

CREATE INDEX idx_discovered_job_source_external ON discovery.discovered_job (source_id, external_id);
CREATE INDEX idx_discovered_job_active ON discovery.discovered_job (expires_at) WHERE corpus_status = 'ACTIVE';
CREATE INDEX idx_discovered_job_organization ON discovery.discovered_job (organization_id);
CREATE INDEX idx_discovered_job_fingerprint ON discovery.discovered_job (fingerprint);

COMMENT ON TABLE discovery.discovered_job IS
    'Corpus de vagas. Uma partição por dia (UTC). A partição inteira é descartada 30 dias depois. '
    'Ninguém tem FK para esta tabela, de propósito.';
COMMENT ON COLUMN discovery.discovered_job.external_id IS 'Id da vaga na fonte. ATS: "{board}:{id}".';
COMMENT ON COLUMN discovery.discovered_job.full_description IS 'Só preenchida quando a fonte permite guardar (allows_storage).';
COMMENT ON COLUMN discovery.discovered_job.fingerprint IS 'SHA-256 de cargo, empresa e cidade normalizados (deduplicação entre fontes).';
COMMENT ON COLUMN discovery.discovered_job.content_hash IS 'SHA-256 dos campos normalizados. Muda quando a vaga muda.';

-- Requisitos da vaga: sempre gravados com o mesmo discovered_at da vaga (mesma partição do dia)
CREATE TABLE discovery.discovered_job_skill (
    discovered_job_id bigint NOT NULL,
    discovered_at timestamptz NOT NULL,
    skill_id int8 NOT NULL,
    is_required boolean NOT NULL DEFAULT true,
    CONSTRAINT discovered_job_skill_pkey PRIMARY KEY (discovered_job_id, discovered_at, skill_id),
    CONSTRAINT discovered_job_skill_job_fkey FOREIGN KEY (discovered_job_id, discovered_at)
        REFERENCES discovery.discovered_job (discovered_job_id, discovered_at) ON DELETE CASCADE,
    CONSTRAINT discovered_job_skill_skill_id_fkey FOREIGN KEY (skill_id) REFERENCES core.skill (skill_id)
) PARTITION BY RANGE (discovered_at);
CREATE INDEX idx_discovered_job_skill_skill ON discovery.discovered_job_skill (skill_id);

CREATE TABLE discovery.discovered_job_language (
    discovered_job_id bigint NOT NULL,
    discovered_at timestamptz NOT NULL,
    language_id int8 NOT NULL,
    min_proficiency varchar(6) NULL,
    CONSTRAINT discovered_job_language_pkey PRIMARY KEY (discovered_job_id, discovered_at, language_id),
    CONSTRAINT discovered_job_language_job_fkey FOREIGN KEY (discovered_job_id, discovered_at)
        REFERENCES discovery.discovered_job (discovered_job_id, discovered_at) ON DELETE CASCADE,
    CONSTRAINT discovered_job_language_language_id_fkey FOREIGN KEY (language_id)
        REFERENCES core."language" (language_id)
) PARTITION BY RANGE (discovered_at);

CREATE TABLE discovery.discovered_job_education (
    discovered_job_id bigint NOT NULL,
    discovered_at timestamptz NOT NULL,
    education_id int8 NOT NULL,
    CONSTRAINT discovered_job_education_pkey PRIMARY KEY (discovered_job_id, discovered_at, education_id),
    CONSTRAINT discovered_job_education_job_fkey FOREIGN KEY (discovered_job_id, discovered_at)
        REFERENCES discovery.discovered_job (discovered_job_id, discovered_at) ON DELETE CASCADE,
    CONSTRAINT discovered_job_education_education_id_fkey FOREIGN KEY (education_id)
        REFERENCES core.education (education_id)
) PARTITION BY RANGE (discovered_at);

CREATE TABLE discovery.discovered_job_certification (
    discovered_job_id bigint NOT NULL,
    discovered_at timestamptz NOT NULL,
    certification_id int8 NOT NULL,
    CONSTRAINT discovered_job_certification_pkey PRIMARY KEY (discovered_job_id, discovered_at, certification_id),
    CONSTRAINT discovered_job_certification_job_fkey FOREIGN KEY (discovered_job_id, discovered_at)
        REFERENCES discovery.discovered_job (discovered_job_id, discovered_at) ON DELETE CASCADE,
    CONSTRAINT discovered_job_certification_certification_id_fkey FOREIGN KEY (certification_id)
        REFERENCES core.certification (certification_id)
) PARTITION BY RANGE (discovered_at);

-- 5. Respostas brutas das fontes (só quando a fonte permite guardar)
CREATE TABLE discovery.raw_ingestion_payload (
    raw_payload_id bigint NOT NULL DEFAULT nextval('discovery.raw_payload_id_seq'),
    fetched_at timestamptz NOT NULL DEFAULT now(),
    source_id bigint NOT NULL,
    external_id varchar(255) NULL,
    payload jsonb NOT NULL,
    CONSTRAINT raw_ingestion_payload_pkey PRIMARY KEY (raw_payload_id, fetched_at),
    CONSTRAINT raw_ingestion_payload_source_id_fkey FOREIGN KEY (source_id) REFERENCES core.job_source (job_source_id)
) PARTITION BY RANGE (fetched_at);
COMMENT ON TABLE discovery.raw_ingestion_payload IS 'Resposta bruta da fonte, para reprocessar. Só se allows_storage.';

-- 6. Nota por usuário, vaga e versão do perfil (F4)
CREATE TABLE discovery.user_job_match (
    user_job_match_id bigint GENERATED ALWAYS AS IDENTITY,
    users_id int4 NOT NULL,
    discovered_job_id bigint NOT NULL,
    profile_version int4 NOT NULL,
    score_total numeric(5, 2) NOT NULL,
    breakdown jsonb NOT NULL DEFAULT '{}'::jsonb,
    algo_version varchar(20) NULL,
    computed_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT user_job_match_pkey PRIMARY KEY (user_job_match_id),
    CONSTRAINT fk_user_job_match_users_id FOREIGN KEY (users_id) REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT user_job_match_user_job_version_key UNIQUE (users_id, discovered_job_id, profile_version),
    CONSTRAINT user_job_match_score_check CHECK (score_total BETWEEN 0 AND 100)
);
COMMENT ON TABLE discovery.user_job_match IS 'Nota de uma vaga para um usuário. Sem FK para a vaga, de propósito (partições).';

-- 7. Funções de partição e manutenção
CREATE OR REPLACE FUNCTION discovery.create_daily_partition(p_table text, p_day date)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
    v_name text := p_table || '_p' || to_char(p_day, 'YYYYMMDD');
    v_from timestamptz := p_day::timestamp AT TIME ZONE 'UTC';
    v_to timestamptz := (p_day + 1)::timestamp AT TIME ZONE 'UTC';
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS discovery.%I PARTITION OF discovery.%I FOR VALUES FROM (%L) TO (%L)',
        v_name, p_table, v_from, v_to);
END;
$function$;
COMMENT ON FUNCTION discovery.create_daily_partition(text, date) IS
    'Cria a partição de um dia (UTC) de uma tabela particionada do discovery. Exige o dono do schema.';

CREATE OR REPLACE FUNCTION discovery.ensure_partitions(p_days_ahead integer DEFAULT 14)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
    v_table text;
    v_today date := (now() AT TIME ZONE 'UTC')::date;
    v_offset integer;
BEGIN
    FOREACH v_table IN ARRAY ARRAY[
        'discovered_job', 'discovered_job_skill', 'discovered_job_language',
        'discovered_job_education', 'discovered_job_certification', 'raw_ingestion_payload'
    ] LOOP
        FOR v_offset IN 0..p_days_ahead LOOP
            PERFORM discovery.create_daily_partition(v_table, v_today + v_offset);
        END LOOP;
    END LOOP;
END;
$function$;
COMMENT ON FUNCTION discovery.ensure_partitions(integer) IS
    'Garante as partições de hoje e dos próximos N dias. O motor chama no início de cada rodada.';

CREATE OR REPLACE FUNCTION discovery.drop_old_partitions(p_table text, p_keep_days integer)
RETURNS integer
LANGUAGE plpgsql
AS $function$
DECLARE
    r record;
    v_cutoff date := (now() AT TIME ZONE 'UTC')::date - p_keep_days;
    v_count integer := 0;
BEGIN
    FOR r IN
        SELECT c.relname
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
          JOIN pg_namespace n ON n.oid = p.relnamespace
         WHERE n.nspname = 'discovery'
           AND p.relname = p_table
           AND c.relname ~ ('^' || p_table || '_p[0-9]{8}$')
    LOOP
        IF to_date(right(r.relname, 8), 'YYYYMMDD') < v_cutoff THEN
            -- A partição da vaga é referenciada pelas filhas: o Postgres só a apaga depois de desanexada.
            EXECUTE format('ALTER TABLE discovery.%I DETACH PARTITION discovery.%I', p_table, r.relname);
            EXECUTE format('DROP TABLE discovery.%I', r.relname);
            v_count := v_count + 1;
        END IF;
    END LOOP;
    RETURN v_count;
END;
$function$;
COMMENT ON FUNCTION discovery.drop_old_partitions(text, integer) IS
    'Descarta as partições com dia anterior a hoje - p_keep_days. Devolve quantas foram descartadas.';

-- As filhas são descartadas antes da mãe, porque têm FK para ela.
CREATE OR REPLACE FUNCTION discovery.purge_expired(p_jobs_keep_days integer DEFAULT 30,
                                                  p_payload_keep_days integer DEFAULT 7,
                                                  p_tasks_keep_days integer DEFAULT 7,
                                                  p_runs_keep_days integer DEFAULT 90)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
    v_table text;
BEGIN
    FOREACH v_table IN ARRAY ARRAY[
        'discovered_job_skill', 'discovered_job_language', 'discovered_job_education',
        'discovered_job_certification', 'discovered_job'
    ] LOOP
        PERFORM discovery.drop_old_partitions(v_table, p_jobs_keep_days);
    END LOOP;

    PERFORM discovery.drop_old_partitions('raw_ingestion_payload', p_payload_keep_days);

    DELETE FROM discovery.user_job_match WHERE expires_at < now();

    DELETE FROM discovery.ingestion_task
     WHERE status = 'COMPLETED'
       AND completed_at < now() - make_interval(days => p_tasks_keep_days);

    DELETE FROM discovery.ingestion_run
     WHERE started_at < now() - make_interval(days => p_runs_keep_days);
END;
$function$;

CREATE OR REPLACE FUNCTION discovery.run_daily_maintenance()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    PERFORM discovery.ensure_partitions(14);
    PERFORM discovery.purge_expired();
END;
$function$;

SELECT discovery.ensure_partitions(14);
