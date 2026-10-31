-- V17: motor de ingestão robusto (F07): fila com DLQ e backoff, erros por registro dos lotes de parceiro,
-- índices da deduplicação entre fontes e credencial do feed de parceiro.

-- 1. Fila: tipo do erro da última tentativa e quando a tarefa foi para a fila de mortas (DLQ = status DEAD)
ALTER TABLE discovery.ingestion_task
    ADD COLUMN last_attempt_at timestamptz NULL,
    ADD COLUMN failure_kind varchar(20) NULL,
    ADD COLUMN dead_at timestamptz NULL,
    ADD CONSTRAINT ingestion_task_failure_kind_check CHECK (failure_kind IS NULL OR failure_kind IN ('TRANSIENT', 'PERMANENT'));
CREATE INDEX idx_ingestion_task_running ON discovery.ingestion_task (locked_until) WHERE status = 'RUNNING';
CREATE INDEX idx_ingestion_task_dead ON discovery.ingestion_task (dead_at) WHERE status = 'DEAD';

COMMENT ON COLUMN discovery.ingestion_task.locked_until IS
    'Fim do lease do worker que pegou a tarefa. Vencido, outra rodada pode retomar a tarefa abandonada.';
COMMENT ON COLUMN discovery.ingestion_task.run_after IS 'Próxima tentativa (backoff exponencial depois de falha temporária).';
COMMENT ON COLUMN discovery.ingestion_task.dead_at IS 'Quando a tarefa foi para a fila de mortas (falha permanente ou tentativas esgotadas).';

-- 2. Erros por registro dos lotes de parceiro (o lote segue; o registro inválido fica rastreado)
CREATE TABLE discovery.ingestion_record_error (
    ingestion_record_error_id bigint GENERATED ALWAYS AS IDENTITY,
    ingestion_run_id bigint NOT NULL,
    record_index int4 NOT NULL,
    external_id varchar(255) NULL,
    error_message text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ingestion_record_error_pkey PRIMARY KEY (ingestion_record_error_id),
    CONSTRAINT fk_ingestion_record_error_run_id FOREIGN KEY (ingestion_run_id)
        REFERENCES discovery.ingestion_run (ingestion_run_id) ON DELETE CASCADE,
    CONSTRAINT ingestion_record_error_index_check CHECK (record_index >= 0)
);
CREATE INDEX idx_ingestion_record_error_run ON discovery.ingestion_record_error (ingestion_run_id, record_index);

COMMENT ON TABLE discovery.ingestion_record_error IS 'Registro inválido de um lote (PARTNER_FEED): posição no lote, id externo e o motivo.';

-- 3. Deduplicação entre fontes (job_group) e busca por URL original
CREATE INDEX idx_discovered_job_group ON discovery.discovered_job (job_group_id) WHERE job_group_id IS NOT NULL;
CREATE INDEX idx_discovered_job_url ON discovery.discovered_job USING hash (job_url);

-- 4. Feed de parceiro: a chave do parceiro fica numa variável de ambiente (nunca no banco)
UPDATE core.job_source SET credentials_ref = 'GUPY_FEED_KEY' WHERE code = 'GUPY' AND credentials_ref IS NULL;
