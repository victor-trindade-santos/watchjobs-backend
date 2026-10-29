# Runbook do WatchJobs (produção)

Uma página com o que fazer nas situações previsíveis. Comandos `gh` exigem o GitHub CLI autenticado;
comandos `psql` usam a conexão **direct** do Neon com o usuário dono.

## Configuração única (F7-01 a F7-04)

Hospedagem: **Azure Container Apps** (Azure for Students, região `brazilsouth`), imagem no **GHCR**, banco no
**Neon** (`sa-east-1`). Grupo de recursos `watchjobs-rg`, app `watchjobs-api`.

**Secrets do repositório (GitHub → Settings → Secrets):** `DB_URL_DIRECT`, `DB_OWNER_USERNAME`, `DB_OWNER_PASSWORD`,
`JWT_SECRET`, `NEON_API_KEY`, `ADZUNA_APP_ID`, `ADZUNA_APP_KEY`, `MAIL_USERNAME`, `MAIL_PASSWORD`,
`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`.

**Variables do repositório:** `NEON_PROJECT_ID`, `APP_CORS_ALLOWED_ORIGINS`, `PUBLIC_BASE_URL`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`.

**Segredos do Container App (`az containerapp secret set`, lidos pela API):** `db-url` (pooled, com
`sslmode=require&prepareThreshold=0`), `db-username` (`watchjobs_app`), `db-password`, `jwt-secret`,
`mail-username`, `mail-password`, `adzuna-app-id`, `adzuna-app-key`, `github-dispatch-token` (token fine-grained só com
"Actions: read and write" neste repositório). A API lê `jwt-secret` da Azure, não o secret do GitHub. Copiar o valor
da Azure para o GitHub só alinha os dois cofres; não muda a revisão que já está no ar. O deploy copia `JWT_SECRET`
do GitHub de volta para `jwt-secret` e cria uma revisão nova. Colar o valor na variável de ambiente do portal não
adianta: o workflow regrava `JWT_SECRET=secretref:jwt-secret`. `az containerapp revision restart` também não pega
segredo novo — é preciso uma revisão nova. Um `_` no log do `JwtUtil` significa que o valor efetivo é Base64URL;
a imagem antiga rejeita esse alfabeto mesmo que GitHub e Azure tenham o mesmo texto.

**Usuário da API sem DDL:** crie `watchjobs_app` no console do Neon (Roles) e, depois da primeira migration, rode com o dono:

```sql
GRANT USAGE ON SCHEMA core, discovery TO watchjobs_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core, discovery TO watchjobs_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA core, discovery TO watchjobs_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA core, discovery TO watchjobs_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA core, discovery GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO watchjobs_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA core, discovery GRANT USAGE, SELECT ON SEQUENCES TO watchjobs_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA core, discovery GRANT EXECUTE ON FUNCTIONS TO watchjobs_app;
```

As partições novas são criadas pelo dono (motor); confira que herdam as permissões com
`SELECT has_table_privilege('watchjobs_app', 'discovery.discovered_job', 'INSERT');`.

**Alerta de orçamento:** portal do Azure → Cost Management → Budgets → escopo da assinatura Azure for Students,
alertas em 50%, 90% e 100% do valor mensal escolhido. O crédito restante aparece em "Azure for Students" no portal.

**Logs da API:** `az containerapp logs show -n watchjobs-api -g watchjobs-rg --follow`.

## O agendador parou (60 dias sem atividade no repositório)

Sinal: nenhuma linha nova em `discovery.ingestion_run` nas últimas 24 h.

1. GitHub → Actions → "Motor de ingestão" → **Enable workflow** (se aparecer o aviso de desativado).
2. Rode na mão (abaixo) e confira o resumo no log.
3. Faça um commit qualquer no repositório pelo menos uma vez a cada 50 dias.

## Rodar o motor na mão

- Pelo GitHub: `gh workflow run ingestion.yml` (ou Actions → Motor de ingestão → Run workflow).
- Local, contra o banco local: `.\gradlew.bat bootRun --args="--spring.profiles.active=ingestion"`.
- Uma fonte ou empresa só: acrescente `--source=GREENHOUSE --company=vtex --force --no-maintenance`.

## Cota do Adzuna esgotada

Sinal: no log, "cota diária de N chamadas atingida"; buscas sob demanda com `partial: true`.

1. Confira o uso do dia: `SELECT count(*) FROM discovery.ingestion_run r JOIN core.job_source s ON s.job_source_id = r.source_id WHERE s.code = 'ADZUNA' AND r.started_at >= date_trunc('day', now());`
2. Nada a fazer no mesmo dia: a busca sob demanda manda tudo para o motor (ATS) até a cota voltar.
3. Se acontecer com frequência: reduza `ONDEMAND_PER_DAY`, aumente `ONDEMAND_CACHE_HOURS` ou avalie o Jooble.

## CU-horas do Neon estouradas (ou armazenamento acima de 60%)

1. Confira no console do Neon o consumo do mês.
2. Garanta que nada mantém o banco acordado (nenhum "keep alive", `/health` não toca no banco).
3. Reduza a frequência do agendador (`cron` em `ingestion.yml`) ou o prazo do corpus com
   `SELECT discovery.purge_expired(21);` (muda a regra de produto: decidir antes).

## Partições não criadas (motor parado mais de 14 dias)

Sinal: erro "no partition of relation". Rode com o dono: `SELECT discovery.ensure_partitions(14);` e reative o agendador.

## Restaurar um branch do Neon

Cada deploy recria o branch `pre-deploy`: a cópia do banco logo antes da migration.

1. Neon → Branches → `main` → **Restore** a partir do `pre-deploy` (ou de um ponto no tempo).
2. Republique a versão anterior do código: Actions → Deploy → rode no commit anterior, ou
   `az containerapp update -n watchjobs-api -g watchjobs-rg --image ghcr.io/<repo>:<sha anterior>`.
3. Migration que falhou no meio: o Postgres desfaz a migration inteira; corrija com uma versão nova
   (ou `flyway repair` se o erro foi no próprio arquivo).

## Cobertura das regras (gatilho da Laya, 3.13)

```sql
SELECT round(100.0 * count(*) FILTER (WHERE role_family IS NULL) / count(*), 1) AS sem_area,
       round(100.0 * count(*) FILTER (WHERE experience_level IS NULL) / count(*), 1) AS sem_nivel
  FROM discovery.discovered_job
 WHERE corpus_status = 'ACTIVE' AND expires_at > now();
```

Mais de 15% sem área ou sem nível por 2 semanas: avaliar a classificação por modelo (B8).

## Cobertura brasileira (F2-14)

```sql
SELECT s.code, count(*) FILTER (WHERE j.country_code = 'BR' OR j.work_mode = 'REMOTE') AS br_ou_remoto, count(*) AS total
  FROM discovery.discovered_job j JOIN core.job_source s ON s.job_source_id = j.source_id
 WHERE j.corpus_status = 'ACTIVE' AND j.expires_at > now()
 GROUP BY s.code;
```

## Busca sob demanda presa em segundo plano

Sinal: pedidos `SYNC_DONE` há mais de 10 minutos.

```sql
SELECT search_request_id, query_text, requested_at, ingestion_task_id FROM discovery.search_request
 WHERE status = 'SYNC_DONE' AND requested_at < now() - interval '10 minutes';
SELECT ingestion_task_id, status, attempts, last_error FROM discovery.ingestion_task WHERE task_type = 'ON_DEMAND_SEARCH' ORDER BY created_at DESC LIMIT 20;
```

Se o disparo falhou (token vencido), renove o `GITHUB_DISPATCH_TOKEN` e rode o motor na mão: a fila é atendida primeiro.
Tarefa travada em `RUNNING` por um motor que caiu: `UPDATE discovery.ingestion_task SET status = 'PENDING' WHERE status = 'RUNNING' AND locked_until < now();`

openssl rand -base64 32









