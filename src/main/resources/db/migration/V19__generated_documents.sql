-- V19: documentos gerados por vaga (F10): currículo adaptado e cover letter, com versões.
-- O currículo base continua sendo o perfil (core.users, experience, education...); nada aqui o altera.

CREATE TABLE core.generated_document (
    generated_document_id bigint GENERATED ALWAYS AS IDENTITY,
    users_id int4 NOT NULL,
    saved_job_id bigint NOT NULL,
    doc_type varchar(20) NOT NULL,
    title varchar(300) NOT NULL,
    current_version int4 NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT generated_document_pkey PRIMARY KEY (generated_document_id),
    CONSTRAINT generated_document_job_type_key UNIQUE (saved_job_id, doc_type),
    CONSTRAINT generated_document_type_check CHECK (doc_type IN ('RESUME', 'COVER_LETTER')),
    CONSTRAINT fk_generated_document_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT fk_generated_document_saved_job_id FOREIGN KEY (saved_job_id)
        REFERENCES core.saved_job (saved_job_id) ON DELETE CASCADE
);
CREATE INDEX idx_generated_document_users ON core.generated_document (users_id, updated_at DESC);

CREATE TABLE core.generated_document_version (
    generated_document_version_id bigint GENERATED ALWAYS AS IDENTITY,
    generated_document_id bigint NOT NULL,
    version int4 NOT NULL,
    content text NOT NULL,
    source varchar(20) NOT NULL,
    model varchar(80) NULL,
    instructions text NULL,
    prompt_tokens int4 NULL,
    completion_tokens int4 NULL,
    warnings jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT generated_document_version_pkey PRIMARY KEY (generated_document_version_id),
    CONSTRAINT generated_document_version_key UNIQUE (generated_document_id, version),
    CONSTRAINT generated_document_version_source_check CHECK (source IN ('AI', 'EDIT', 'RESTORE')),
    CONSTRAINT generated_document_version_content_check CHECK (length(content) BETWEEN 1 AND 60000),
    CONSTRAINT fk_generated_document_version_document FOREIGN KEY (generated_document_id)
        REFERENCES core.generated_document (generated_document_id) ON DELETE CASCADE
);
CREATE INDEX idx_generated_document_version_ai ON core.generated_document_version (created_at)
    WHERE source = 'AI';

COMMENT ON TABLE core.generated_document IS
    'Currículo adaptado ou cover letter de uma vaga salva. Um por vaga e tipo; o conteúdo fica nas versões.';
COMMENT ON TABLE core.generated_document_version IS
    'Versões do documento: AI (gerada), EDIT (editada pelo usuário) ou RESTORE (cópia de uma versão anterior).';
