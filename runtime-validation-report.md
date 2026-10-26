# Auth Sessions Validation

Date: 2026-10-24

## Verdict

Implementation added. Compilation and PostgreSQL execution are BLOCKED, not PASS.
The workspace is shared through Live Share (`vsls:/`). The execution terminal
runs on the guest machine, where the host project path is unavailable.

## Execution Evidence

Attempted:

```powershell
& 'C:\projects\watchjobs-backend\gradlew.bat' -p 'C:\projects\watchjobs-backend' compileKotlin compileTestKotlin test --rerun-tasks
```

Exit code: 1. PowerShell could not find the Gradle wrapper at that path.
Gradle did not start: this is an environment failure, not a compiler result.

A workspace task was also attempted. VS Code rejected `vsls:/` with:
`Cannot create a task outside the current workspace folder: vsls:/`.

Editor diagnostics reported no errors in the touched Kotlin files. This does
not establish compiler success, valid Hibernate mappings, or passing tests.
No PostgreSQL tests were executed; no pass count is claimed.
Docker availability on the host could not be verified. No H2 fallback was used.
Browser tests are not applicable to this JSON API.

## Added Coverage

- Flyway V4 application and PostgreSQL-only context startup.
- Hibernate mapping validation through the application context.
- Persistence of reset codes and counting after revocation.
- Signup, login, SHA-256-only refresh persistence and configured lifetimes.
- Refresh rotation, replacement link, family preservation and old-token rejection.
- Concurrent renewal: exactly one success for the same refresh token.
- Unknown and expired refresh rejection with an appropriate 401 message.
- Authenticated logout, revocation persistence and rejection after logout.
- Missing access and attempts to revoke another user's refresh.
- Invalid login password does not create a new session.

The API integration tests use full Spring context and MockMvc with real
PostgreSQL persistence, rather than an externally running HTTP server.
Session tests create unique users and delete only those users afterward.
The reset-code repository test rolls back its transaction.

## Run on the Host

From the project directory with Java 21 and Docker Desktop running:

```powershell
docker compose up -d postgres
.\gradlew.bat compileKotlin compileTestKotlin
.\gradlew.bat test --rerun-tasks
```

Run each command only after the preceding command succeeds. Use a development
or test PostgreSQL database, never production. Tests use `DB_URL`, `DB_USERNAME`
and `DB_PASSWORD`, or the application's local PostgreSQL defaults. Do not set
the H2 profile. There is no need to remove Docker volumes.

Reports after execution:

- `build/reports/tests/test/index.html`
- `build/test-results/test/`

For live HTTP verification, start `bootRun` and execute requests 39-45 in
`requests.http`, after signup and signin. Logout revokes the refresh token;
the access token remains valid until its normal expiration.