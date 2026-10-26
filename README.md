# WatchJobs: backend

## Sumário

- [1. Projeto e grupo](#1-projeto-e-grupo)
- [2. Repositórios](#2-repositórios)
- [3. Integrante e entidades](#3-integrante-e-entidades)
- [4. O que a aplicação faz](#4-o-que-a-aplicação-faz)
   - [4.1 Vaga salva × candidatura: qual é a diferença?](#41-vaga-salva--candidatura-qual-é-a-diferença)
- [5. Tecnologias](#5-tecnologias)
- [6. Camadas e pastas](#6-camadas-e-pastas)
   - [Rotas](#rotas)
- [7. Instalação, execução e acesso](#7-instalação-execução-e-acesso)
   - [7.0 Pré-requisitos](#70-pré-requisitos)
   - [7.1 Com Docker (PostgreSQL, forma principal)](#71-com-docker-postgresql-forma-principal)
   - [7.2 Sem Docker e sem instalar nenhum banco (H2)](#72-sem-docker-e-sem-instalar-nenhum-banco-h2)
   - [7.3 Com um PostgreSQL já instalado (sem Docker)](#73-com-um-postgresql-já-instalado-sem-docker)
   - [Acesso e testes](#acesso-e-testes)
- [Checklist dos requisitos da prova](#checklist-dos-requisitos-da-prova)
   - [Parte 1: backend](#parte-1-backend)
   - [Critérios de avaliação](#critérios-de-avaliação)

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

### 4.1 Vaga salva × candidatura: qual é a diferença?

São duas etapas diferentes da busca por emprego, por isso são duas entidades.

| | **Vaga salva** (`SavedJob`, tabela `saved_job`) | **Candidatura** (`Application`, tabela `application`) |
|---|---|---|
| O que representa | Uma **oportunidade** que chamou sua atenção. Você ainda pode nem ter se candidatado | O **processo seletivo** que começou quando você se candidatou àquela vaga |
| Pergunta que responde | "Que vaga é essa?" | "Como está indo a minha candidatura?" |
| Dados | Cargo, empresa, link, local, modo de trabalho, nível, contrato, faixa salarial e anotações | Etapa (Triagem → Proposta), status, data de início e de término, próxima entrevista, notas e feedback da empresa |
| Quando é criada | Assim que você encontra a vaga | Só quando você se candidata a uma vaga **já salva** |
| Existe sozinha? | Sim | Não: toda candidatura pertence a uma vaga salva |

**Como as duas se relacionam (1 vaga → no máximo 1 candidatura):**

1. **Salvar a vaga** (`POST /saved-jobs`): ela nasce com o status `SAVED` ou `PLANNING_TO_APPLY`.
2. **Candidatar-se** (`POST /applications`): a candidatura é criada para aquela vaga, e a vaga passa para `APPLIED`.
3. **Acompanhar o processo** (`PUT /applications/{id}`): mudar a etapa, marcar a entrevista e atualizar o status (`IN_PROCESS`, `OFFER_RECEIVED`, `ACCEPTED`, `REJECTED` ou `WITHDRAWN`). O status novo é gravado também na vaga, que é a **fonte da verdade**.
4. **Desistir do registro da candidatura** (`DELETE /applications/{id}`): a vaga continua salva e volta para `SAVED`.
5. **Excluir a vaga** (`DELETE /saved-jobs/{id}`): a candidatura dela é excluída junto (`ON DELETE CASCADE`).

**Exemplo:** você vê a vaga "Desenvolvedor Kotlin" na ACME e **salva**. Uma semana depois, envia o currículo e cria a **candidatura**. A partir daí, a candidatura registra a entrevista com o RH no dia 22, a mudança para "Teste técnico" e o feedback recebido. A vaga mostra, de relance, em que pé está.

## 5. Tecnologias

Kotlin 2.3, Spring Boot 4.1 (Web MVC, Data JPA, Validation, Security), JJWT 0.13, PostgreSQL 17, Flyway, Docker Compose e Gradle (Kotlin DSL), com Java 21. Para rodar sem Docker: H2, um banco embutido em memória.

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
| Banco | `src/main/resources/db/migration/`, `src/main/resources/db/h2/` | `V1__core_schema.sql` e `V2__prova_adjustments.sql` (Flyway, PostgreSQL). `schema.sql` e `application-h2.properties` para o perfil sem Docker |

### Rotas

| Método | Caminho | Auth | Resposta |
|---|---|---|---|
| POST | `/signup` | Pública | 201 / 400 / 409 |
| POST | `/signin` | Pública | 200 com `{ token, refreshToken, tokenType, expiresAt, user }` / 401 |
| POST | `/auth/refresh` | Pública | 200 com novo par de tokens / 400 / 401 |
| POST | `/auth/logout` | Token | 204 / 400 / 401 |
| POST | `/password/forgot` | Pública | 202, mesmo se o e-mail não existir |
| POST | `/password/reset` | Pública | 204 / 400 |
| GET | `/me` | Token | 200 |
| DELETE | `/me` | Token + senha no corpo | 204 / 401 |
| GET | `/saved-jobs?q=` | Token | 200 (com `q`, busca por cargo ou empresa) |
| GET, PUT, DELETE | `/saved-jobs/{id}` | Token | 200 / 200 / 204, ou 404 |
| POST | `/saved-jobs` | Token | 201 / 400 |
| GET | `/applications?q=` | Token | 200 (com `q`, busca em notas, feedback, cargo ou empresa) |
| GET, PUT, DELETE | `/applications/{id}` | Token | 200 / 200 / 204, ou 404 |
| POST | `/applications` | Token | 201 / 400 / 404 / 409 |
| GET | `/application-stages` | Token | 200 |

Rotas protegidas exigem o cabeçalho `Authorization: Bearer <token>`. O access token vale 15 minutos e o refresh vale 30 dias. A renovação revoga o refresh antigo e entrega um substituto da mesma família. O logout revoga apenas o refresh informado, pertencente ao usuário autenticado; o access já emitido continua válido até expirar, salvo se a senha for redefinida.

`POST /password/forgot` responde sempre **202**. Se a conta existir e ainda houver cota (3 códigos por e-mail por hora), o código de 6 dígitos chega por e-mail e vale 15 minutos. `POST /password/reset` troca a senha, grava `password_changed_at` e revoga todos os refresh tokens; access tokens emitidos antes da troca passam a receber 401. `DELETE /me` exige `{ "password": "..." }` e apaga o usuário com as linhas que dependem dele.

A documentação interativa fica em **http://localhost:8081/swagger-ui.html** (spec em `/v3/api-docs`). No botão **Authorize**, cole só o access token. O Mailpit (caixa local) fica em **http://localhost:8025**; o SMTP é `localhost:1025`.

## 7. Instalação, execução e acesso

### 7.0 Pré-requisitos

1. **Java 21.** Confira com `java -version`.
2. **VS Code com a extensão REST Client** (autor: Huachao Mao). Ela executa o roteiro de testes da API, o arquivo [`requests.http`](requests.http). Para instalar, use uma das formas:
   - No VS Code, abra **Extensões** (`Ctrl+Shift+X`), busque **REST Client** e clique em **Instalar**.
   - Pelo terminal: `code --install-extension humao.rest-client`.

   Depois, abra o `requests.http` e clique em **Send Request**, que aparece acima de cada requisição. A resposta abre ao lado.
3. **Docker Desktop**, só para a forma 7.1. As formas 7.2 e 7.3 não usam Docker.
4. **Clonar o repositório:**

```powershell
git clone https://github.com/victor-trindade-santos/watchjobs-backend.git
cd watchjobs-backend
```

Depois, escolha **uma** das três formas abaixo para subir o banco. Nas três, a API fica em **http://localhost:8081**.

### 7.1 Com Docker (PostgreSQL, forma principal)

Com o Docker Desktop aberto:

```powershell
.\gradlew.bat bootRun
```

O `bootRun` sobe o PostgreSQL do `compose.yaml` sozinho (Docker Compose Support), e o Flyway aplica as migrations V1 a V4. Espere aparecer `Tomcat started on port 8081`.

### 7.2 Sem Docker e sem instalar nenhum banco (H2)

O perfil H2 não suporta a autenticação renovável da V4: seu schema está congelado, sem as tabelas e colunas novas. Para login, refresh, logout e os testes desta fase, use PostgreSQL conforme 7.1 ou 7.3.

Se o computador não tiver Docker, este é o caminho mais simples. **Não precisa instalar nada além do Java**: o H2 é um banco embutido que o Gradle baixa como qualquer outra dependência.

```powershell
.\gradlew.bat bootRun -Ph2
```

O `-Ph2` ativa o perfil [`application-h2.properties`](src/main/resources/application-h2.properties):

- desliga o Docker Compose e o Flyway;
- cria as tabelas pelo [`db/h2/schema.sql`](src/main/resources/db/h2/schema.sql), já com as 6 etapas de candidatura.

O banco fica **em memória**, então os dados somem quando a aplicação para. Esta configuração é legada e não valida as funcionalidades da V4.

### 7.3 Com um PostgreSQL já instalado (sem Docker)

Crie o banco `watchjobs`, informe a conexão e rode:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/watchjobs"
$env:DB_USERNAME = "watchjobs"
$env:DB_PASSWORD = "watchjobs"
.\gradlew.bat bootRun --args='--spring.docker.compose.enabled=false'
```

O Flyway cria o schema `core` e aplica as migrations na primeira subida.Dá para testar sem a extensão REST Client pelo Swagger, em **http://localhost:8081/swagger-ui.html**. O arquivo [`requests.http`](requests.http) continua sendo o roteiro de cadastro, login, CRUD, renovação, recuperação de senha, exclusão de conta

### Acesso e testes

**Primeiro acesso:** com PostgreSQL, crie um usuário com `POST /signup` e faça login com `POST /signin`. O arquivo [`requests.http`](requests.http) (abra com a extensão REST Client, seção 7.0) tem o roteiro de cadastro, login, CRUD das duas entidades, buscas, regras de status, renovação de sessão, logout e casos de erro.

**Testes da camada web** (não precisam de banco):

```powershell
.\gradlew.bat test --tests "com.watchjobs.controller.AuthControllerWebTest"
```

**Compilação e testes com PostgreSQL** (Docker Desktop aberto):

```powershell
docker compose up -d postgres
.\gradlew.bat compileKotlin compileTestKotlin
.\gradlew.bat test --rerun-tasks
```

Use um banco de desenvolvimento/teste, nunca produção. Os testes usam `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` ou a conexão local padrão; não use o perfil H2. O Flyway aplica as migrations e o Hibernate valida os mapeamentos. Os testes de sessão criam usuários exclusivos e removem apenas esses usuários ao terminar; o teste de códigos usa rollback. Não é necessário executar `docker compose down -v`.

São cobertos hash persistido, validade dos tokens, rotação, reuso recusado, concorrência, expiração, logout e isolamento entre donos. Os resultados ficam em `build/reports/tests/test/index.html` e `build/test-results/test/`.

## Checklist dos requisitos da prova

Como este repositório atende à **Parte 1 (backend, 8,0 pontos)** e aos critérios de avaliação. Os caminhos partem de `src/main/kotlin/com/watchjobs/`.

### Parte 1: backend

- [x] **Dependências exigidas (Kotlin + Spring Boot + Spring Security):** Spring Web, Spring Data JPA, Validation, Spring Security e PostgreSQL como banco relacional, todas no [`build.gradle.kts`](build.gradle.kts). O schema é versionado pelo Flyway em `src/main/resources/db/migration/`.

- [x] **1a. Entidades JPA (1,0):** duas entidades por integrante, cada uma com mais de 5 campos além do id e pelo menos 3 tipos da lista.
  - [`SavedJob`](src/main/kotlin/com/watchjobs/entity/SavedJob.kt): 16 campos além do id. Tipos **String** (cargo, empresa, URL, local, moeda, notas), **Double** (salário mínimo e máximo) e **LocalDateTime** (`savedAt`, `statusUpdatedAt`).
  - [`Application`](src/main/kotlin/com/watchjobs/entity/Application.kt): 6 campos além do id e das relações. Tipos **String** (status, notas, feedback), **LocalDate** (`startDate`, `endDate`) e **LocalDateTime** (`interviewAt`).
  - As duas têm `@Entity`, `@Table`, `@Id` com `@GeneratedValue(IDENTITY)` e `@Column(length = ...)` em **todo** campo String.
  - O Hibernate roda em `ddl-auto=validate`, então as entidades precisam bater com o banco criado pelo Flyway.

- [x] **1b. Repositórios JPA com JPQL e LIKE (1,0):** cada repositório tem uma consulta `@Query` em JPQL com `LIKE` e busca parcial (`lower(...) LIKE lower(concat('%', :q, '%'))`) num campo de texto.
  - [`SavedJobRepository.search`](src/main/kotlin/com/watchjobs/repository/SavedJobRepository.kt): cargo ou empresa.
  - [`ApplicationRepository.search`](src/main/kotlin/com/watchjobs/repository/ApplicationRepository.kt): notas, feedback, ou cargo e empresa da vaga (`a.savedJob.positionRole`).
  - [`UserRepository.searchByName`](src/main/kotlin/com/watchjobs/repository/UserRepository.kt) e [`ApplicationStageRepository.searchByName`](src/main/kotlin/com/watchjobs/repository/ApplicationStageRepository.kt): também têm a consulta.
  - Na API, as buscas ficam em `GET /saved-jobs?q=` e `GET /applications?q=`.

- [x] **1c. Services com CRUD (1,0):** [`SavedJobService`](src/main/kotlin/com/watchjobs/service/SavedJobService.kt) e [`ApplicationService`](src/main/kotlin/com/watchjobs/service/ApplicationService.kt) têm `create`, `findAll` (lista, com busca opcional), `findById`, `update` e `delete`, todos com `@Transactional`.
  - Eles também aplicam as regras de negócio: o status da vaga é sincronizado com a candidatura, há uma candidatura por vaga, o salário mínimo não pode passar do máximo e um registro de outro usuário responde 404.

- [x] **1d. Controllers REST (1,0):** [`SavedJobController`](src/main/kotlin/com/watchjobs/controller/SavedJobController.kt) (`/saved-jobs`) e [`ApplicationController`](src/main/kotlin/com/watchjobs/controller/ApplicationController.kt) (`/applications`) têm as mesmas rotas:
  - `GET` lista todos.
  - `GET /{id}` busca por id.
  - `POST` cria e responde 201.
  - `PUT /{id}` atualiza.
  - `DELETE /{id}` remove e responde 204.

- [x] **1e. Autenticação com `/signin` e `/signup` (1,0):** [`AuthController`](src/main/kotlin/com/watchjobs/controller/AuthController.kt).
  - O `/signin` autentica pelo **Spring Security**: o [`AuthService`](src/main/kotlin/com/watchjobs/service/AuthService.kt) chama `authenticationManager.authenticate(UsernamePasswordAuthenticationToken(...))`.
  - Com as credenciais válidas, a resposta traz o **token JWT** (`{ token, tokenType, expiresAt, user }`). Com credenciais inválidas, a resposta é 401.
  - O `/signup` cria a conta com a senha em BCrypt.

- [x] **1f. Utilitário JWT e configuração do Spring Security (1,0):**
  - [`JwtUtil`](src/main/kotlin/com/watchjobs/security/JwtUtil.kt): `generateToken` (gera), `isValid` (valida assinatura e validade) e `extractUsername` (lê o usuário do token validado).
  - [`SecurityConfig`](src/main/kotlin/com/watchjobs/config/SecurityConfig.kt):
    - `PasswordEncoder` (BCrypt).
    - `AuthenticationManager` com `DaoAuthenticationProvider`.
    - O filtro JWT [`JwtAuthenticationFilter`](src/main/kotlin/com/watchjobs/security/JwtAuthenticationFilter.kt) (`OncePerRequestFilter`), registrado antes do `UsernamePasswordAuthenticationFilter`.
    - A `SecurityFilterChain`, com sessão stateless e CORS.
    - As regras de autorização: `POST /signin` e `POST /signup` públicas, todas as outras rotas exigem autenticação, e uma requisição sem token recebe 401 em JSON.

- [x] **1g. Dois DTOs por entidade e camada mapper (1,0):**
  - Request e Response em `dto/request/` e `dto/response/`: [`SavedJobRequest`](src/main/kotlin/com/watchjobs/dto/request/SavedJobRequest.kt) / [`SavedJobResponse`](src/main/kotlin/com/watchjobs/dto/response/SavedJobResponse.kt) e [`ApplicationRequest`](src/main/kotlin/com/watchjobs/dto/request/ApplicationRequest.kt) / [`ApplicationResponse`](src/main/kotlin/com/watchjobs/dto/response/ApplicationResponse.kt).
  - Mappers em `mapper/`, com `toEntity`, `updateEntity` e `toResponse`: [`SavedJobMapper`](src/main/kotlin/com/watchjobs/mapper/SavedJobMapper.kt), [`ApplicationMapper`](src/main/kotlin/com/watchjobs/mapper/ApplicationMapper.kt), `UserMapper` e `ApplicationStageMapper`.
  - Os controllers recebem e devolvem só DTOs, nunca as entidades.

- [x] **1h. Bean Validation nos DTOs de Request (1,0):** com `@field:`, mensagens em português e `@Valid` nos controllers. Uma requisição inválida responde 400 com os erros por campo. Cada DTO tem pelo menos 2 regras diferentes:
  - `SavedJobRequest`: `@NotBlank`, `@Size`, `@Pattern`, `@PositiveOrZero` e `@NotNull`.
  - `ApplicationRequest`: `@NotNull`, `@Positive`, `@NotBlank`, `@Pattern`, `@PastOrPresent` e `@Size`.
  - `SignupRequest`: `@NotBlank`, `@Size`, `@NotNull`, `@Past`, `@Email` e `@Pattern`.
  - `SigninRequest`: `@NotBlank` e `@Email`.

### Critérios de avaliação

- [x] **3a. Professor como colaborador:** `antoniorcn@hotmail.com` foi convidado neste repositório e no do frontend.
- [x] **3b e 3c. Autoria no histórico Git:** o histórico tem commits pequenos por etapa, com o item da prova na mensagem, feitos pelo integrante.
- [x] **3d. Compila e roda:**
  - `.\gradlew.bat bootRun` sobe a API com o PostgreSQL e as migrations.
  - Sem Docker, `.\gradlew.bat bootRun -Ph2` sobe a API com o H2 embutido, sem instalar nenhum banco (seção 7.2).
  - O [`requests.http`](requests.http) exercita as 38 requisições da demonstração, incluindo os casos de erro.
  - `.\gradlew.bat test --tests "com.watchjobs.controller.*"` roda os testes automatizados.
- [x] **3e. README com os 7 itens:**
  - i. Nome do projeto e do grupo: seção 1.
  - ii. Links dos repositórios: seção 2.
  - iii. Integrantes e entidades: seção 3.
  - iv. O que a aplicação faz: seção 4.
  - v. Tecnologias: seção 5.
  - vi. Camadas e pastas: seção 6 (a do frontend está no README do frontend).
  - vii. Instalação, execução e acesso: seção 7.

