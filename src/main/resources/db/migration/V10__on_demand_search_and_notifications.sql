-- V10: busca sob demanda e notificações (F5)

-- 1. Pedidos de busca sob demanda
CREATE TABLE discovery.search_request (
    search_request_id bigint GENERATED ALWAYS AS IDENTITY,
    users_id int4 NOT NULL,
    query_text varchar(200) NOT NULL,
    location_text varchar(150) NULL,
    work_mode varchar(20) NULL,
    query_key char(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'RUNNING',
    cache_hit boolean NOT NULL DEFAULT false,
    sync_found int4 NOT NULL DEFAULT 0,
    async_found int4 NOT NULL DEFAULT 0,
    ingestion_task_id bigint NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    sync_completed_at timestamptz NULL,
    completed_at timestamptz NULL,
    error_message text NULL,
    CONSTRAINT search_request_pkey PRIMARY KEY (search_request_id),
    CONSTRAINT fk_search_request_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT fk_search_request_ingestion_task_id FOREIGN KEY (ingestion_task_id)
        REFERENCES discovery.ingestion_task (ingestion_task_id) ON DELETE SET NULL,
    CONSTRAINT search_request_status_check
        CHECK (status IN ('RUNNING', 'SYNC_DONE', 'COMPLETED', 'FAILED')),
    CONSTRAINT search_request_work_mode_check
        CHECK (work_mode IS NULL OR work_mode IN ('REMOTE', 'HYBRID', 'ON_SITE')),
    CONSTRAINT search_request_counters_check CHECK (sync_found >= 0 AND async_found >= 0)
);
CREATE INDEX idx_search_request_users_time ON discovery.search_request (users_id, requested_at DESC);
CREATE INDEX idx_search_request_query_key ON discovery.search_request (query_key, requested_at DESC);

COMMENT ON TABLE discovery.search_request IS
    'Pedido de "Buscar mais vagas": parte na hora (agregador) e parte em segundo plano (motor). Também é o limite por usuário.';
COMMENT ON COLUMN discovery.search_request.query_key IS 'SHA-256 da consulta normalizada (termo, local e modo). Chave do cache de 6 h.';

-- 2. Fila: pedidos da busca sob demanda e coleta por empresa
ALTER TABLE discovery.ingestion_task DROP CONSTRAINT ingestion_task_task_type_check;
ALTER TABLE discovery.ingestion_task
    ADD COLUMN source_company_id bigint NULL,
    ADD CONSTRAINT ingestion_task_task_type_check CHECK (task_type IN
        ('INGEST_SEGMENT', 'INGEST_COMPANY', 'ON_DEMAND_SEARCH',
         'VERIFY_LINKS', 'CLEANUP_PARTITIONS', 'REPROCESS_SEGMENT')),
    ADD CONSTRAINT fk_ingestion_task_source_company_id FOREIGN KEY (source_company_id)
        REFERENCES core.source_company (source_company_id) ON DELETE SET NULL;

-- 3. Vagas já avisadas (sem FK para discovered_job, por causa das partições)
CREATE TABLE discovery.match_notification (
    users_id int4 NOT NULL,
    discovered_job_id bigint NOT NULL,
    score_total numeric(5, 2) NOT NULL,
    notified_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT match_notification_pkey PRIMARY KEY (users_id, discovered_job_id),
    CONSTRAINT fk_match_notification_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT match_notification_score_check CHECK (score_total BETWEEN 0 AND 100)
);
COMMENT ON TABLE discovery.match_notification IS
    'Vaga já avisada a cada usuário: evita repetir o aviso quando a nota é recalculada.';

-- 4. Avisos e preferências
CREATE TABLE core.notification (
    notification_id bigint GENERATED ALWAYS AS IDENTITY,
    users_id int4 NOT NULL,
    notification_type varchar(30) NOT NULL,
    title varchar(150) NOT NULL,
    body text NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    dedup_key varchar(150) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz NULL,
    emailed_at timestamptz NULL,
    CONSTRAINT notification_pkey PRIMARY KEY (notification_id),
    CONSTRAINT fk_notification_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT notification_user_dedup_key UNIQUE (users_id, dedup_key),
    CONSTRAINT notification_type_check
        CHECK (notification_type IN ('NEW_MATCHES', 'SEARCH_COMPLETED', 'SYSTEM')),
    CONSTRAINT notification_payload_object_check CHECK (jsonb_typeof(payload) = 'object')
);
CREATE INDEX idx_notification_users_time ON core.notification (users_id, created_at DESC);
CREATE INDEX idx_notification_unread ON core.notification (users_id) WHERE read_at IS NULL;

COMMENT ON TABLE core.notification IS 'Avisos do sininho. dedup_key impede o mesmo aviso duas vezes.';
COMMENT ON COLUMN core.notification.emailed_at IS 'Quando o aviso foi enviado por e-mail. Vazio = ainda não enviado.';

CREATE TABLE core.notification_preference (
    users_id int4 NOT NULL,
    min_score numeric(5, 2) NOT NULL DEFAULT 70,
    in_app_enabled boolean NOT NULL DEFAULT true,
    email_enabled boolean NOT NULL DEFAULT false,
    email_frequency varchar(20) NOT NULL DEFAULT 'DAILY',
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notification_preference_pkey PRIMARY KEY (users_id),
    CONSTRAINT fk_notification_preference_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT notification_preference_min_score_check CHECK (min_score BETWEEN 0 AND 100),
    CONSTRAINT notification_preference_frequency_check CHECK (email_frequency IN ('IMMEDIATE', 'DAILY'))
);
CREATE TRIGGER trg_notification_preference_updated_at BEFORE UPDATE ON core.notification_preference
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE core.notification_preference IS
    'Uma linha por usuário: nota mínima dos avisos, avisos no app, e-mail e frequência (imediato ou resumo diário).';

INSERT INTO core.notification_preference (users_id)
SELECT users_id FROM core.users
ON CONFLICT (users_id) DO NOTHING;

-- 5. Limpeza da busca sob demanda e dos avisos
CREATE OR REPLACE FUNCTION discovery.purge_on_demand(p_requests_keep_days integer DEFAULT 30,
                                                    p_notifications_keep_days integer DEFAULT 90)
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    DELETE FROM discovery.search_request
     WHERE requested_at < now() - make_interval(days => p_requests_keep_days);

    DELETE FROM discovery.match_notification n
     WHERE NOT EXISTS (
        SELECT 1 FROM discovery.discovered_job j
         WHERE j.discovered_job_id = n.discovered_job_id);

    DELETE FROM core.notification
     WHERE read_at IS NOT NULL
       AND created_at < now() - make_interval(days => p_notifications_keep_days);
END;
$function$;

CREATE OR REPLACE FUNCTION discovery.run_daily_maintenance()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    PERFORM discovery.ensure_partitions(14);
    PERFORM discovery.purge_expired();
    PERFORM discovery.purge_on_demand();
END;
$function$;
