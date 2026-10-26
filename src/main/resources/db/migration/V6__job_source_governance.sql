-- V6: governança das fontes (core.job_source) e empresas por fonte (core.source_company).

ALTER TABLE core.job_source
    ADD COLUMN code varchar(50),
    ADD COLUMN access_method varchar(20),
    ADD COLUMN trust_rank smallint NOT NULL DEFAULT 50,
    ADD COLUMN terms_url text NULL,
    ADD COLUMN terms_reviewed_at timestamptz NULL,
    ADD COLUMN credentials_ref varchar(100) NULL,
    ADD COLUMN config jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN consecutive_failures int4 NOT NULL DEFAULT 0,
    ADD COLUMN circuit_opened_at timestamptz NULL,
    ADD COLUMN next_attempt_at timestamptz NULL,
    ADD COLUMN last_success_at timestamptz NULL,
    ADD COLUMN notes text NULL;

-- Linhas que já existirem (se houver) ganham valores coerentes e ficam inativas até a revisão
UPDATE core.job_source
   SET code = upper(regexp_replace(name, '[^A-Za-z0-9]+', '_', 'g')),
       access_method = CASE source_type
                           WHEN 'SCRAPING' THEN 'HTML_PAGE'
                           WHEN 'PARTNER_FEED' THEN 'FILE_FEED'
                           ELSE 'PUBLIC_API'
                       END,
       trust_rank = CASE source_type
                        WHEN 'ATS_PUBLIC' THEN 10
                        WHEN 'PARTNER_FEED' THEN 20
                        WHEN 'SCRAPING' THEN 30
                        ELSE 40
                    END,
       allows_storage = CASE WHEN source_type = 'SCRAPING' THEN false ELSE allows_storage END,
       is_active = false;

ALTER TABLE core.job_source
    ALTER COLUMN code SET NOT NULL,
    ALTER COLUMN access_method SET NOT NULL,
    ADD CONSTRAINT job_source_code_key UNIQUE (code),
    ADD CONSTRAINT job_source_code_format_check CHECK (code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    ADD CONSTRAINT job_source_access_method_check
        CHECK (access_method IN ('PUBLIC_API', 'AUTHENTICATED_API', 'FILE_FEED', 'HTML_PAGE')),
    ADD CONSTRAINT job_source_type_method_check CHECK (
           (source_type IN ('ATS_PUBLIC', 'AGGREGATOR') AND access_method IN ('PUBLIC_API', 'AUTHENTICATED_API'))
        OR (source_type = 'PARTNER_FEED' AND access_method IN ('AUTHENTICATED_API', 'FILE_FEED'))
        OR (source_type = 'SCRAPING' AND access_method = 'HTML_PAGE')),
    ADD CONSTRAINT job_source_credentials_check
        CHECK (access_method <> 'AUTHENTICATED_API' OR credentials_ref IS NOT NULL),
    ADD CONSTRAINT job_source_credentials_ref_format_check
        CHECK (credentials_ref IS NULL OR credentials_ref ~ '^[A-Z][A-Z0-9_]*(,[A-Z][A-Z0-9_]*)*$'),
    ADD CONSTRAINT job_source_scraping_storage_check
        CHECK (source_type <> 'SCRAPING' OR allows_storage = false),
    ADD CONSTRAINT job_source_activation_check
        CHECK (NOT is_active OR terms_reviewed_at IS NOT NULL),
    ADD CONSTRAINT job_source_trust_rank_check CHECK (trust_rank BETWEEN 1 AND 100),
    ADD CONSTRAINT job_source_rate_limit_check
        CHECK ((rate_limit_per_minute IS NULL OR rate_limit_per_minute > 0)
           AND (rate_limit_per_day IS NULL OR rate_limit_per_day > 0)),
    ADD CONSTRAINT job_source_failures_check CHECK (consecutive_failures >= 0),
    ADD CONSTRAINT job_source_capabilities_object_check CHECK (jsonb_typeof(capabilities) = 'object'),
    ADD CONSTRAINT job_source_config_object_check CHECK (jsonb_typeof(config) = 'object');

ALTER TABLE core.job_source ALTER COLUMN is_active SET DEFAULT false;

COMMENT ON COLUMN core.job_source.source_type IS
    'Origem e comportamento. ATS_PUBLIC = ATS oficial da empresa (Greenhouse, Lever, Ashby, SmartRecruiters); '
    'AGGREGATOR = agregador de várias origens (Adzuna, Jooble, Remotive); PARTNER_FEED = lote entregue por '
    'parceiro (Gupy, parceiros corporativos); SCRAPING = leitura direta de páginas sem API.';
COMMENT ON COLUMN core.job_source.access_method IS
    'Como a coleta acontece: PUBLIC_API, AUTHENTICATED_API, FILE_FEED ou HTML_PAGE.';
COMMENT ON COLUMN core.job_source.code IS 'Chave técnica estável usada pelo motor para achar o conector.';
COMMENT ON COLUMN core.job_source.trust_rank IS 'Confiança da fonte (1 a 100, menor = mais confiável).';
COMMENT ON COLUMN core.job_source.terms_url IS 'Link dos termos de uso lidos antes de ativar a fonte.';
COMMENT ON COLUMN core.job_source.terms_reviewed_at IS 'Quando os termos foram revisados. Fonte ativa exige este campo.';
COMMENT ON COLUMN core.job_source.credentials_ref IS
    'Nomes das variáveis de ambiente com a credencial, separados por vírgula. Nunca o segredo.';
COMMENT ON COLUMN core.job_source.config IS 'Parâmetros do conector (pausa entre chamadas, país padrão, paginação).';
COMMENT ON COLUMN core.job_source.consecutive_failures IS 'Falhas seguidas. Abre o circuit breaker.';
COMMENT ON COLUMN core.job_source.next_attempt_at IS 'Com o circuito aberto, a fonte só volta a ser coletada depois disto.';

CREATE TABLE core.source_company (
    source_company_id bigint GENERATED ALWAYS AS IDENTITY,
    job_source_id bigint NOT NULL,
    organization_id bigint NOT NULL,
    external_key varchar(150) NOT NULL,
    careers_url text NULL,
    is_active boolean NOT NULL DEFAULT true,
    fetch_interval_minutes int4 NOT NULL DEFAULT 360,
    last_fetched_at timestamptz NULL,
    last_success_at timestamptz NULL,
    last_error_at timestamptz NULL,
    last_error text NULL,
    consecutive_failures int4 NOT NULL DEFAULT 0,
    last_job_count int4 NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT source_company_pkey PRIMARY KEY (source_company_id),
    CONSTRAINT fk_source_company_job_source_id FOREIGN KEY (job_source_id)
        REFERENCES core.job_source (job_source_id),
    CONSTRAINT fk_source_company_organization_id FOREIGN KEY (organization_id)
        REFERENCES core.organization (organization_id),
    CONSTRAINT source_company_source_key_key UNIQUE (job_source_id, external_key),
    CONSTRAINT source_company_external_key_format_check CHECK (external_key ~ '^[A-Za-z0-9._:/-]+$'),
    CONSTRAINT source_company_interval_check CHECK (fetch_interval_minutes >= 60),
    CONSTRAINT source_company_failures_check CHECK (consecutive_failures >= 0),
    CONSTRAINT source_company_job_count_check CHECK (last_job_count IS NULL OR last_job_count >= 0)
);
CREATE INDEX idx_source_company_due ON core.source_company (job_source_id, last_fetched_at NULLS FIRST)
    WHERE is_active;
CREATE INDEX idx_source_company_organization_id ON core.source_company (organization_id);
CREATE TRIGGER trg_source_company_updated_at BEFORE UPDATE ON core.source_company
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON TABLE core.source_company IS 'Empresa ou página coletada por uma fonte (board do ATS, URL de carreiras).';
COMMENT ON COLUMN core.source_company.external_key IS 'Board do ATS, slug, id do parceiro ou domínio.';
COMMENT ON COLUMN core.source_company.last_job_count IS
    'Vagas na última coleta completa. Protege contra fechamento em massa quando uma coleta vem vazia.';

INSERT INTO core.job_source
    (name, code, source_type, access_method, base_url, is_active, allows_storage,
     requires_attribution, trust_rank, rate_limit_per_day, credentials_ref, capabilities, config)
VALUES
    ('Greenhouse', 'GREENHOUSE', 'ATS_PUBLIC', 'PUBLIC_API', 'https://boards-api.greenhouse.io/v1/boards',
     false, false, false, 10, NULL, NULL,
     '{"fullSnapshot": true, "queryBased": false, "hasSalary": false, "hasFullDescription": true}',
     '{"minDelayMs": 1000}'),
    ('Lever', 'LEVER', 'ATS_PUBLIC', 'PUBLIC_API', 'https://api.lever.co/v0/postings',
     false, false, false, 10, NULL, NULL,
     '{"fullSnapshot": true, "queryBased": false, "hasSalary": true, "hasFullDescription": true}',
     '{"minDelayMs": 1000}'),
    ('Ashby', 'ASHBY', 'ATS_PUBLIC', 'PUBLIC_API', 'https://api.ashbyhq.com/posting-api/job-board',
     false, false, false, 10, NULL, NULL,
     '{"fullSnapshot": true, "queryBased": false, "hasSalary": true, "hasFullDescription": true}',
     '{"minDelayMs": 1000}'),
    ('SmartRecruiters', 'SMARTRECRUITERS', 'ATS_PUBLIC', 'PUBLIC_API', 'https://api.smartrecruiters.com/v1/companies',
     false, false, false, 10, NULL, NULL,
     '{"fullSnapshot": true, "queryBased": false, "hasSalary": false, "hasFullDescription": true}',
     '{"minDelayMs": 1000}'),
    ('Adzuna', 'ADZUNA', 'AGGREGATOR', 'AUTHENTICATED_API', 'https://api.adzuna.com/v1/api/jobs',
     false, false, true, 40, 30, 'ADZUNA_APP_ID,ADZUNA_APP_KEY',
     '{"fullSnapshot": false, "queryBased": true, "hasSalary": true, "hasFullDescription": false}',
     '{"country": "br"}'),
    ('Jooble', 'JOOBLE', 'AGGREGATOR', 'AUTHENTICATED_API', 'https://jooble.org/api',
     false, false, true, 40, NULL, 'JOOBLE_API_KEY',
     '{"fullSnapshot": false, "queryBased": true, "hasSalary": true, "hasFullDescription": false}',
     '{}'),
    ('Remotive', 'REMOTIVE', 'AGGREGATOR', 'PUBLIC_API', 'https://remotive.com/api/remote-jobs',
     false, false, true, 40, 4, NULL,
     '{"fullSnapshot": false, "queryBased": true, "hasSalary": true, "hasFullDescription": true}',
     '{}'),
    ('Gupy', 'GUPY', 'PARTNER_FEED', 'FILE_FEED', NULL,
     false, false, false, 20, NULL, NULL,
     '{"fullSnapshot": true, "queryBased": false, "deliveryMode": "BATCH"}',
     '{}'),
    ('Páginas de carreira', 'CAREER_PAGES', 'SCRAPING', 'HTML_PAGE', NULL,
     false, false, false, 30, NULL, NULL,
     '{"fullSnapshot": true, "queryBased": false, "respectsRobotsTxt": true}',
     '{"minDelayMs": 5000, "userAgent": "WatchJobsBot/1.0"}')
ON CONFLICT (code) DO NOTHING;
