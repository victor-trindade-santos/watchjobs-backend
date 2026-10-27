-- V11: Kanban e histórico (F6)

ALTER TABLE core.saved_job
    ADD COLUMN discovered_job_id bigint NULL,
    ADD COLUMN role_family varchar(50) NULL,
    ADD COLUMN board_rank numeric NULL;

COMMENT ON COLUMN core.saved_job.discovered_job_id IS
    'Vaga do corpus de onde esta cópia veio. Sem FK, de propósito: o corpus é descartado em 30 dias.';
COMMENT ON COLUMN core.saved_job.role_family IS 'Área da vaga no momento em que foi salva.';
COMMENT ON COLUMN core.saved_job.board_rank IS
    'Posição do card na coluna do Kanban. Ao soltar entre dois cards, vale o ponto médio dos vizinhos.';

-- Vagas salvas do corpus antes da V11 guardavam a área e a vaga de origem só no snapshot
UPDATE core.saved_job
   SET role_family = requirements_snapshot ->> 'roleFamily'
 WHERE role_family IS NULL AND requirements_snapshot ? 'roleFamily';
UPDATE core.saved_job
   SET discovered_job_id = (requirements_snapshot ->> 'discoveredJobId')::bigint
 WHERE discovered_job_id IS NULL AND requirements_snapshot ->> 'discoveredJobId' ~ '^[0-9]+$';

WITH ranked AS (
    SELECT saved_job_id,
           row_number() OVER (
               PARTITION BY users_id,
                   CASE
                       WHEN status IN ('SAVED', 'PLANNING_TO_APPLY') THEN 1
                       WHEN status = 'APPLIED' THEN 2
                       WHEN status IN ('IN_PROCESS', 'OFFER_RECEIVED') THEN 3
                       ELSE 4
                   END
               ORDER BY saved_at) * 1000 AS rnk
      FROM core.saved_job
)
UPDATE core.saved_job s
   SET board_rank = r.rnk
  FROM ranked r
 WHERE r.saved_job_id = s.saved_job_id;

ALTER TABLE core.saved_job
    ALTER COLUMN board_rank SET DEFAULT 0,
    ALTER COLUMN board_rank SET NOT NULL;

DROP INDEX core.idx_saved_job_users_status;
CREATE INDEX idx_saved_job_board ON core.saved_job (users_id, status, board_rank);
CREATE INDEX idx_saved_job_discovered_job_id ON core.saved_job (discovered_job_id)
    WHERE discovered_job_id IS NOT NULL;

CREATE TABLE core.application_event (
    application_event_id bigint GENERATED ALWAYS AS IDENTITY,
    saved_job_id bigint NOT NULL,
    users_id int4 NOT NULL,
    application_id int4 NULL,
    event_type varchar(30) NOT NULL,
    from_status varchar(30) NULL,
    to_status varchar(30) NULL,
    from_stage_id int4 NULL,
    to_stage_id int4 NULL,
    note text NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT application_event_pkey PRIMARY KEY (application_event_id),
    CONSTRAINT fk_application_event_saved_job_id FOREIGN KEY (saved_job_id)
        REFERENCES core.saved_job (saved_job_id) ON DELETE CASCADE,
    CONSTRAINT fk_application_event_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT fk_application_event_application_id FOREIGN KEY (application_id)
        REFERENCES core.application (application_id) ON DELETE SET NULL,
    CONSTRAINT fk_application_event_from_stage_id FOREIGN KEY (from_stage_id)
        REFERENCES core.applicationstage (application_stage_id),
    CONSTRAINT fk_application_event_to_stage_id FOREIGN KEY (to_stage_id)
        REFERENCES core.applicationstage (application_stage_id),
    CONSTRAINT application_event_type_check CHECK (event_type IN
        ('SAVED', 'STATUS_CHANGED', 'STAGE_CHANGED', 'MOVED', 'APPLICATION_CREATED', 'APPLICATION_DELETED')),
    CONSTRAINT application_event_status_check CHECK (
        (from_status IS NULL OR from_status IN ('SAVED', 'PLANNING_TO_APPLY', 'APPLIED', 'IN_PROCESS',
                                                'OFFER_RECEIVED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'))
        AND (to_status IS NULL OR to_status IN ('SAVED', 'PLANNING_TO_APPLY', 'APPLIED', 'IN_PROCESS',
                                                'OFFER_RECEIVED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN')))
);
CREATE INDEX idx_application_event_saved_job ON core.application_event (saved_job_id, occurred_at);
CREATE INDEX idx_application_event_users_time ON core.application_event (users_id, occurred_at);

COMMENT ON TABLE core.application_event IS
    'Histórico do Kanban (salvou, mudou status ou etapa, moveu, criou ou excluiu a candidatura). '
    'Preso à vaga salva para não sumir quando a candidatura é excluída.';

-- Histórico aproximado para os dados que já existem
INSERT INTO core.application_event (saved_job_id, users_id, event_type, to_status, occurred_at)
SELECT saved_job_id, users_id, 'SAVED', 'SAVED', saved_at
  FROM core.saved_job;

INSERT INTO core.application_event
    (saved_job_id, users_id, application_id, event_type, to_status, to_stage_id, occurred_at)
SELECT a.saved_job_id, a.users_id, a.application_id, 'APPLICATION_CREATED', 'APPLIED',
       a.application_stage_id, a.start_date::timestamptz
  FROM core.application a;

INSERT INTO core.application_event (saved_job_id, users_id, event_type, to_status, occurred_at)
SELECT saved_job_id, users_id, 'STATUS_CHANGED', status, status_updated_at
  FROM core.saved_job
 WHERE status NOT IN ('SAVED', 'APPLIED');
