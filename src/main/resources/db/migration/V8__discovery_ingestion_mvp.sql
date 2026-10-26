-- V8: ingestão do MVP no discovery: job_seen, colunas novas no corpus e nas coletas,
-- e limpeza com prazo fixo de 30 dias.

-- 1. Corpus: colunas novas (valem para todas as partições)
ALTER TABLE discovery.discovered_job
    ADD COLUMN source_company_id bigint NULL,
    ADD COLUMN role_family varchar(50) NULL,
    ADD COLUMN country_code varchar(2) NULL,
    ADD COLUMN raw_location text NULL,
    ADD COLUMN description_snippet varchar(600) NULL,
    ADD COLUMN classification_source varchar(10) NOT NULL DEFAULT 'RULES',
    ADD COLUMN classification_confidence numeric(4, 3) NULL,
    ADD COLUMN rules_version varchar(16) NULL,
    ADD CONSTRAINT discovered_job_source_company_id_fkey FOREIGN KEY (source_company_id)
        REFERENCES core.source_company (source_company_id) ON DELETE SET NULL,
    ADD CONSTRAINT discovered_job_role_family_fkey FOREIGN KEY (role_family)
        REFERENCES core.role_family (code),
    ADD CONSTRAINT discovered_job_classification_source_check
        CHECK (classification_source IN ('RULES', 'MODEL', 'MANUAL')),
    ADD CONSTRAINT discovered_job_classification_confidence_check
        CHECK (classification_confidence IS NULL OR classification_confidence BETWEEN 0 AND 1),
    ADD CONSTRAINT discovered_job_country_code_check
        CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$');

ALTER TABLE discovery.coverage_segment
    ADD CONSTRAINT fk_coverage_segment_role_family FOREIGN KEY (role_family)
        REFERENCES core.role_family (code);

ALTER TABLE discovery.discovered_job DROP CONSTRAINT discovered_job_job_type_check;
ALTER TABLE discovery.discovered_job
    ADD CONSTRAINT discovered_job_job_type_check
        CHECK (job_type IS NULL OR job_type IN ('CLT', 'PJ', 'INTERNSHIP', 'FREELANCE', 'TEMPORARY'));

CREATE INDEX idx_discovered_job_feed ON discovery.discovered_job (role_family, posted_at DESC)
    WHERE corpus_status = 'ACTIVE';
CREATE INDEX idx_discovered_job_source_company ON discovery.discovered_job (source_company_id)
    WHERE source_company_id IS NOT NULL;

COMMENT ON COLUMN discovery.discovered_job.expires_at IS
    'discovered_at + 30 dias. Não é renovado quando a vaga é vista de novo: no 31º dia a partição '
    'é descartada. Para guardar a vaga, o usuário a salva (cópia em core.saved_job).';
COMMENT ON COLUMN discovery.discovered_job.role_family IS 'Área deduzida pelas regras (ou pelo modelo, depois do MVP).';
COMMENT ON COLUMN discovery.discovered_job.raw_location IS 'Local como veio da fonte, antes de normalizar.';
COMMENT ON COLUMN discovery.discovered_job.description_snippet IS 'Trecho da descrição em texto limpo (até 500 caracteres).';
COMMENT ON COLUMN discovery.discovered_job.classification_source IS 'Quem classificou: RULES, MODEL ou MANUAL.';
COMMENT ON COLUMN discovery.discovered_job.classification_confidence IS 'Confiança da área (0 a 1).';
COMMENT ON COLUMN discovery.discovered_job.rules_version IS 'Hash das regras ativas que produziram a classificação.';

-- 2. Coletas
ALTER TABLE discovery.ingestion_run DROP CONSTRAINT ingestion_run_status_check;
ALTER TABLE discovery.ingestion_run
    ADD COLUMN source_company_id bigint NULL,
    ADD COLUMN trigger_type varchar(20) NOT NULL DEFAULT 'SCHEDULED',
    ADD COLUMN is_full_snapshot boolean NOT NULL DEFAULT false,
    ADD COLUMN jobs_closed int4 NOT NULL DEFAULT 0,
    ADD COLUMN batch_reference varchar(255) NULL,
    ADD COLUMN payload_checksum char(64) NULL,
    ALTER COLUMN status SET DEFAULT 'RUNNING',
    ADD CONSTRAINT ingestion_run_status_check
        CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    ADD CONSTRAINT fk_ingestion_run_source_company_id FOREIGN KEY (source_company_id)
        REFERENCES core.source_company (source_company_id) ON DELETE SET NULL,
    ADD CONSTRAINT ingestion_run_trigger_type_check
        CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'ON_DEMAND', 'PARTNER_UPLOAD', 'BACKFILL')),
    ADD CONSTRAINT ingestion_run_counters_check
        CHECK (jobs_fetched >= 0 AND jobs_new >= 0 AND jobs_updated >= 0
               AND jobs_deduplicated >= 0 AND jobs_rejected >= 0 AND jobs_closed >= 0),
    ADD CONSTRAINT ingestion_run_completed_check
        CHECK (completed_at IS NULL OR completed_at >= started_at);
CREATE UNIQUE INDEX uq_ingestion_run_payload_checksum ON discovery.ingestion_run (source_id, payload_checksum)
    WHERE payload_checksum IS NOT NULL AND status <> 'FAILED';
CREATE INDEX idx_ingestion_run_source_company ON discovery.ingestion_run (source_company_id, started_at DESC)
    WHERE source_company_id IS NOT NULL;

COMMENT ON COLUMN discovery.ingestion_run.trigger_type IS 'O que disparou a coleta: agenda, manual, busca sob demanda, lote ou carga.';
COMMENT ON COLUMN discovery.ingestion_run.is_full_snapshot IS 'A coleta trouxe todas as vagas abertas do alvo (permite fechar as que sumiram).';
COMMENT ON COLUMN discovery.ingestion_run.payload_checksum IS 'SHA-256 do lote de parceiro. Lote repetido é ignorado.';

-- 3. Respostas brutas
ALTER TABLE discovery.raw_ingestion_payload
    ADD COLUMN ingestion_run_id bigint NULL,
    ADD COLUMN content_hash char(64) NULL,
    ADD CONSTRAINT raw_ingestion_payload_run_id_fkey FOREIGN KEY (ingestion_run_id)
        REFERENCES discovery.ingestion_run (ingestion_run_id) ON DELETE SET NULL;

-- 4. Registro estável de cada vaga (sem FK para discovered_job, por causa das partições)
CREATE TABLE discovery.job_seen (
    source_id bigint NOT NULL,
    external_id varchar(255) NOT NULL,
    discovered_job_id bigint NOT NULL,
    discovered_at timestamptz NOT NULL,
    source_company_id bigint NULL,
    content_hash char(64) NOT NULL,
    version int4 NOT NULL DEFAULT 1,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    last_changed_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz NULL,
    expired_at timestamptz NULL,
    last_ingestion_run_id bigint NULL,
    CONSTRAINT job_seen_pkey PRIMARY KEY (source_id, external_id),
    CONSTRAINT job_seen_discovered_job_id_key UNIQUE (discovered_job_id),
    CONSTRAINT fk_job_seen_source_id FOREIGN KEY (source_id)
        REFERENCES core.job_source (job_source_id),
    CONSTRAINT fk_job_seen_source_company_id FOREIGN KEY (source_company_id)
        REFERENCES core.source_company (source_company_id) ON DELETE SET NULL,
    CONSTRAINT fk_job_seen_last_ingestion_run_id FOREIGN KEY (last_ingestion_run_id)
        REFERENCES discovery.ingestion_run (ingestion_run_id) ON DELETE SET NULL,
    CONSTRAINT job_seen_version_check CHECK (version >= 1),
    CONSTRAINT job_seen_seen_order_check CHECK (last_seen_at >= first_seen_at)
);
CREATE INDEX idx_job_seen_company_open ON discovery.job_seen (source_company_id, last_seen_at)
    WHERE closed_at IS NULL AND expired_at IS NULL;
CREATE INDEX idx_job_seen_expired_at ON discovery.job_seen (expired_at) WHERE expired_at IS NOT NULL;

COMMENT ON TABLE discovery.job_seen IS
    'Registro estável de cada vaga por fonte: o motor consulta antes de gravar (nova, igual, mudou ou expirada).';
COMMENT ON COLUMN discovery.job_seen.expired_at IS
    'Quando a vaga foi descartada do corpus (30 dias). Enquanto preenchido, o motor ignora a vaga, '
    'mesmo que a fonte continue listando: ela não volta como nova.';

-- 5. Limpeza: 30 dias de corpus (prazo fixo), 1 dia de resposta bruta, job_seen expirado por 90 dias.
-- Tem um parâmetro a mais que a versão da V5: sem o DROP, o Postgres criaria uma segunda função
-- com o mesmo nome, e a chamada purge_expired() ficaria ambígua.
DROP FUNCTION discovery.purge_expired(integer, integer, integer, integer);

-- As filhas são descartadas antes da mãe, porque têm FK para ela.
CREATE FUNCTION discovery.purge_expired(p_jobs_keep_days integer DEFAULT 30,
                                        p_payload_keep_days integer DEFAULT 1,
                                        p_tasks_keep_days integer DEFAULT 7,
                                        p_runs_keep_days integer DEFAULT 90,
                                        p_seen_keep_days integer DEFAULT 90)
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

    UPDATE discovery.job_seen s
       SET expired_at = now()
     WHERE s.expired_at IS NULL
       AND NOT EXISTS (
        SELECT 1 FROM discovery.discovered_job j
         WHERE j.discovered_job_id = s.discovered_job_id
           AND j.discovered_at = s.discovered_at);

    DELETE FROM discovery.job_seen
     WHERE expired_at < now() - make_interval(days => p_seen_keep_days);

    DELETE FROM discovery.user_job_match m
     WHERE m.expires_at < now()
        OR NOT EXISTS (
            SELECT 1 FROM discovery.discovered_job j
             WHERE j.discovered_job_id = m.discovered_job_id);

    DELETE FROM core.usersjobinteraction u
     WHERE NOT EXISTS (
        SELECT 1 FROM discovery.discovered_job j
         WHERE j.discovered_job_id = u.discovered_job_id);

    DELETE FROM discovery.job_group g
     WHERE g.last_seen_at < now() - make_interval(days => p_jobs_keep_days + 1)
       AND NOT EXISTS (
        SELECT 1 FROM discovery.discovered_job j WHERE j.job_group_id = g.job_group_id);

    DELETE FROM discovery.ingestion_task
     WHERE status = 'COMPLETED'
       AND completed_at < now() - make_interval(days => p_tasks_keep_days);

    DELETE FROM discovery.ingestion_run
     WHERE started_at < now() - make_interval(days => p_runs_keep_days);
END;
$function$;

COMMENT ON FUNCTION discovery.purge_expired(integer, integer, integer, integer, integer) IS
    'Descarta as partições vencidas (30 dias), marca job_seen.expired_at e limpa as linhas órfãs.';
