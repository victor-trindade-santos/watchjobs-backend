-- Alvos das fontes: boards do Greenhouse (organização + source_company) e segmentos do Adzuna.
-- Migration repetível e idempotente. As fontes nascem inativas (V6); ative só depois de ler os termos.
-- O normalized_name segue a mesma regra do NameNormalizer (minúsculas, sem acento, sem sufixo societário).

INSERT INTO core.organization (name, organization_type, normalized_name, website_url) VALUES
    ('QuintoAndar', 'COMPANY', 'quintoandar', 'https://www.quintoandar.com.br'),
    ('Stone', 'COMPANY', 'stone', 'https://www.stone.com.br'),
    ('EBANX', 'COMPANY', 'ebanx', 'https://www.ebanx.com'),
    ('VTEX', 'COMPANY', 'vtex', 'https://vtex.com'),
    ('Gympass', 'COMPANY', 'gympass', 'https://wellhub.com'),
    ('Wildlife Studios', 'COMPANY', 'wildlife studios', 'https://wildlifestudios.com')
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

INSERT INTO discovery.coverage_segment (role_family, country_code, work_mode, keywords) VALUES
    ('BACKEND', 'BR', 'ANY', 'desenvolvedor backend'),
    ('FRONTEND', 'BR', 'ANY', 'desenvolvedor frontend'),
    ('DATA_ENGINEERING', 'BR', 'ANY', 'engenheiro de dados'),
    ('MOBILE', 'BR', 'ANY', 'desenvolvedor mobile'),
    ('BACKEND', 'BR', 'REMOTE', 'desenvolvedor backend remoto'),
    ('FRONTEND', 'BR', 'REMOTE', 'desenvolvedor frontend remoto')
ON CONFLICT ON CONSTRAINT coverage_segment_key DO NOTHING;

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
