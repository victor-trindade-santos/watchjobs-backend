-- Alvos das fontes: boards do Greenhouse (organização + source_company) e segmentos do Adzuna.
-- Migration repetível e idempotente. As fontes nascem inativas (V6); ative só depois de ler os termos.
-- O normalized_name segue a mesma regra do NameNormalizer (minúsculas, sem acento, sem sufixo societário).

INSERT INTO core.organization (name, organization_type, normalized_name, website_url) VALUES
    ('QuintoAndar', 'COMPANY', 'quintoandar', 'https://www.quintoandar.com.br'),
    ('Stone', 'COMPANY', 'stone', 'https://www.stone.com.br'),
    ('EBANX', 'COMPANY', 'ebanx', 'https://www.ebanx.com'),
    ('VTEX', 'COMPANY', 'vtex', 'https://vtex.com'),
    ('Gympass', 'COMPANY', 'gympass', 'https://wellhub.com'),
    ('Wildlife Studios', 'COMPANY', 'wildlife studios', 'https://wildlifestudios.com'),
    ('Belvo', 'COMPANY', 'belvo', NULL),
    ('Experian', 'COMPANY', 'experian', NULL)
ON CONFLICT ON CONSTRAINT organization_type_normalized_name_key DO NOTHING;

INSERT INTO core.source_company (job_source_id, organization_id, external_key, careers_url)
SELECT js.job_source_id, o.organization_id, b.board, 'https://job-boards.greenhouse.io/' || b.board
  FROM (VALUES
    ('quintoandar', 'quintoandar'),
    ('stone', 'stone'),
    ('ebanx', 'ebanx'),
    ('vtex', 'vtex'),
    ('gympass', 'gympass'),
    ('wildlifestudios', 'wildlife studios')
  ) AS b (board, normalized_name)
  JOIN core.organization o ON o.organization_type = 'COMPANY' AND o.normalized_name = b.normalized_name
  JOIN core.job_source js ON js.code = 'GREENHOUSE'
ON CONFLICT ON CONSTRAINT source_company_source_key_key DO NOTHING;

-- Outros ATS: só alvos validados com ao menos uma vaga no Brasil ou remota (o slug do SmartRecruiters diferencia maiúsculas).
INSERT INTO core.source_company (job_source_id, organization_id, external_key, careers_url)
SELECT js.job_source_id, o.organization_id, b.board, b.careers_url
  FROM (VALUES
    ('ASHBY', 'belvo', 'belvo', 'https://jobs.ashbyhq.com/belvo'),
    ('SMARTRECRUITERS', 'Experian', 'experian', 'https://careers.smartrecruiters.com/Experian')
  ) AS b (source_code, board, normalized_name, careers_url)
  JOIN core.organization o ON o.organization_type = 'COMPANY' AND o.normalized_name = b.normalized_name
  JOIN core.job_source js ON js.code = b.source_code
ON CONFLICT ON CONSTRAINT source_company_source_key_key DO NOTHING;

INSERT INTO discovery.coverage_segment (role_family, country_code, work_mode, keywords) VALUES
    ('BACKEND', 'BR', 'ANY', 'desenvolvedor backend'),
    ('FRONTEND', 'BR', 'ANY', 'desenvolvedor frontend'),
    ('DATA_ENGINEERING', 'BR', 'ANY', 'engenheiro de dados'),
    ('MOBILE', 'BR', 'ANY', 'desenvolvedor mobile'),
    ('BACKEND', 'BR', 'REMOTE', 'desenvolvedor backend remoto'),
    ('FRONTEND', 'BR', 'REMOTE', 'desenvolvedor frontend remoto')
ON CONFLICT ON CONSTRAINT coverage_segment_key DO NOTHING;

-- Remotive: só vagas remotas (os segmentos ANY repetiriam a mesma busca) e termos em inglês por área.
UPDATE core.job_source
   SET config = config || '{"segmentWorkModes": ["REMOTE"], "searchTerms": {"BACKEND": "backend", "FRONTEND": "frontend", "FULLSTACK": "full stack", "MOBILE": "mobile", "DATA_ENGINEERING": "data engineer", "DATA_SCIENCE": "data scientist", "DATA_ANALYTICS": "data analyst", "DEVOPS_SRE": "devops", "SECURITY": "security engineer", "QA": "qa"}}'::jsonb
 WHERE code = 'REMOTIVE' AND config -> 'segmentWorkModes' IS NULL;

-- Jooble: cota conservadora (segmentos agendados, cerca de 24 por dia, mais as buscas sob demanda).
UPDATE core.job_source SET rate_limit_per_day = 60 WHERE code = 'JOOBLE' AND rate_limit_per_day IS NULL;

-- Ativação (F2-05): depois de ler os termos de uso, preencha o link e a data e descomente.
-- A cota diária do Adzuna vem da conta de desenvolvedor (painel em https://developer.adzuna.com).
--
UPDATE core.job_source
    SET is_active = true, terms_reviewed_at = '2026-10-26T12:00:00Z', terms_url = 'https://www.greenhouse.com/legal'
  WHERE code = 'GREENHOUSE' AND terms_reviewed_at IS NULL;

UPDATE core.job_source
    SET is_active = true, terms_reviewed_at = '2026-10-26T12:00:00Z', terms_url = 'https://developer.adzuna.com/docs/terms_of_service',
        rate_limit_per_day = 150
  WHERE code = 'ADZUNA' AND terms_reviewed_at IS NULL;

-- Fontes novas: preparadas, mas só descomente depois de ler os termos (preencha o link e a data da leitura).
-- UPDATE core.job_source
--     SET is_active = true, terms_reviewed_at = '<data da leitura>', terms_url = '<link dos termos do Ashby>'
--   WHERE code = 'ASHBY' AND terms_reviewed_at IS NULL;
--
-- UPDATE core.job_source
--     SET is_active = true, terms_reviewed_at = '<data da leitura>', terms_url = '<link dos termos do SmartRecruiters>'
--   WHERE code = 'SMARTRECRUITERS' AND terms_reviewed_at IS NULL;
--
-- Lever: só depois de cadastrar ao menos uma empresa em core.source_company.
-- UPDATE core.job_source
--     SET is_active = true, terms_reviewed_at = '<data da leitura>', terms_url = '<link dos termos do Lever>'
--   WHERE code = 'LEVER' AND terms_reviewed_at IS NULL;
--
-- Remotive: exige atribuição e poucas chamadas por dia (rate_limit_per_day = 4).
-- UPDATE core.job_source
--     SET is_active = true, terms_reviewed_at = '<data da leitura>', terms_url = '<link dos termos do Remotive>'
--   WHERE code = 'REMOTIVE' AND terms_reviewed_at IS NULL;
--
-- Jooble: só depois de JOOBLE_API_KEY estar no GitHub e no Container App.
-- UPDATE core.job_source
--     SET is_active = true, terms_reviewed_at = '<data da leitura>', terms_url = '<link dos termos do Jooble>'
--   WHERE code = 'JOOBLE' AND terms_reviewed_at IS NULL;
