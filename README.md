# WatchJobs: backend

## 1. Projeto e grupo

**WatchJobs.** Grupo: individual.

## 2. Repositórios

- Backend: https://github.com/victor-trindade-santos/watchjobs-backend
- Frontend: https://github.com/victor-trindade-santos/watchjobs-frontend

## 3. Integrante e entidades

| Integrante | Responsável por |
|---|---|
| Victor Trindade dos Santos | Entidades **SavedJob** (vaga salva) e **Application** (candidatura), além da autenticação |

## 4. O que a aplicação faz

Quem procura emprego perde o controle de quais vagas lhe interessaram, onde já se candidatou, em que etapa está cada processo e quando é a próxima entrevista. O WatchJobs centraliza isso: registra as vagas de interesse e as candidaturas, com etapa, status, datas e entrevistas, e tem busca nas duas listas.

Regras principais:

- Cada usuário só vê e altera os próprios registros. Um registro de outro usuário responde **404**.
- Cada vaga tem no máximo **uma** candidatura.
- O status da vaga é a fonte da verdade e é copiado para a candidatura. Criar a candidatura leva a vaga para `APPLIED`.
- Excluir a vaga apaga a candidatura junto. Excluir só a candidatura devolve a vaga para `SAVED`.

## 5. Tecnologias

Kotlin 2.3, Spring Boot 4.1 (Web MVC, Data JPA, Validation, Security), JJWT 0.13, PostgreSQL 17, Flyway, Docker Compose e Gradle (Kotlin DSL), com Java 21.

## 6. Camadas e pastas

Código em `src/main/kotlin/com/watchjobs/`:

| Camada | Pasta | O que tem |
|---|---|---|
| Apresentação (API) | `controller/` | `AuthController`, `SavedJobController`, `ApplicationController`, `ApplicationStageController` |
| Contrato | `dto/request/`, `dto/response/` | DTOs de entrada (com Bean Validation) e de saída |
| Conversão | `mapper/` | `SavedJobMapper`, `ApplicationMapper`, `ApplicationStageMapper`, `UserMapper` |
| Negócio | `service/` | `AuthService`, `SavedJobService`, `ApplicationService`, `ApplicationStageService` |
| Persistência | `repository/` | Repositórios Spring Data com consultas JPQL e `LIKE` |
| Domínio | `entity/`, `enums/` | Entidades JPA e enums |
| Segurança | `security/`, `config/` | `JwtUtil`, `JwtAuthenticationFilter`, `AppUserDetails`, `AppUserDetailsService`, `SecurityConfig` |
| Erros | `exception/` | Exceções e `GlobalExceptionHandler` (formato `{ code, message, details }`) |
| Banco | `src/main/resources/db/migration/` | `V1__core_schema.sql`, `V2__prova_adjustments.sql` |

### Rotas

| Método | Caminho | Auth | Resposta |
|---|---|---|---|
| POST | `/signup` | Pública | 201 / 400 / 409 |
| POST | `/signin` | Pública | 200 com `{ token, tokenType, expiresAt, user }` / 401 |
| GET | `/me` | Token | 200 |
| GET | `/saved-jobs?q=` | Token | 200 (com `q`, busca por cargo ou empresa) |
| GET, PUT, DELETE | `/saved-jobs/{id}` | Token | 200 / 200 / 204, ou 404 |
| POST | `/saved-jobs` | Token | 201 / 400 |
| GET | `/applications?q=` | Token | 200 (com `q`, busca em notas, feedback, cargo ou empresa) |
| GET, PUT, DELETE | `/applications/{id}` | Token | 200 / 200 / 204, ou 404 |
| POST | `/applications` | Token | 201 / 400 / 404 / 409 |
| GET | `/application-stages` | Token | 200 |

Rotas protegidas exigem o cabeçalho `Authorization: Bearer <token>`. O token vale 2 horas.

## 7. Instalação, execução e acesso

**Pré-requisitos:** Java 21 e Docker Desktop.

```powershell
git clone https://github.com/victor-trindade-santos/watchjobs-backend.git
cd watchjobs-backend
.\gradlew.bat bootRun
```

O `bootRun` sobe o Postgres do `compose.yaml` sozinho (Docker Compose Support), e o Flyway aplica a V1 e a V2. A API fica em **http://localhost:8081**.

**Sem Docker**, com um PostgreSQL já rodando:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/watchjobs"
$env:DB_USERNAME = "watchjobs"
$env:DB_PASSWORD = "watchjobs"
.\gradlew.bat bootRun --args='--spring.docker.compose.enabled=false'
```

**Primeiro acesso:** crie um usuário com `POST /signup` e faça login com `POST /signin`. O arquivo [`requests.http`](requests.http) (extensão REST Client do VS Code) tem o roteiro completo: cadastro, login, CRUD das duas entidades, buscas, regras de status e casos de erro.

**Testes automatizados** (não precisam de banco):

```powershell
.\gradlew.bat test --tests "com.watchjobs.controller.*"
```
