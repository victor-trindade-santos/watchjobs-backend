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

O `bootRun` sobe o PostgreSQL do `compose.yaml` sozinho (Docker Compose Support), e o Flyway aplica a V1 e a V2. Espere aparecer `Tomcat started on port 8081`.

### 7.2 Sem Docker e sem instalar nenhum banco (H2)

Se o computador não tiver Docker, este é o caminho mais simples. **Não precisa instalar nada além do Java**: o H2 é um banco embutido que o Gradle baixa como qualquer outra dependência.

```powershell
.\gradlew.bat bootRun -Ph2
```

O `-Ph2` ativa o perfil [`application-h2.properties`](src/main/resources/application-h2.properties):

- desliga o Docker Compose e o Flyway;
- cria as tabelas pelo [`db/h2/schema.sql`](src/main/resources/db/h2/schema.sql), já com as 6 etapas de candidatura.

Tudo funciona igual: as rotas, a segurança, as validações e as regras. A única diferença é que o banco fica **em memória**, então os dados somem quando a aplicação para.

### 7.3 Com um PostgreSQL já instalado (sem Docker)

Crie o banco `watchjobs`, informe a conexão e rode:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/watchjobs"
$env:DB_USERNAME = "watchjobs"
$env:DB_PASSWORD = "watchjobs"
.\gradlew.bat bootRun --args='--spring.docker.compose.enabled=false'
```

O Flyway cria o schema `core` e aplica as migrations na primeira subida.

### Acesso e testes

**Primeiro acesso:** crie um usuário com `POST /signup` e faça login com `POST /signin`. O arquivo [`requests.http`](requests.http) (abra com a extensão REST Client, seção 7.0) tem o roteiro completo, que funciona com qualquer uma das três formas: cadastro, login, CRUD das duas entidades, buscas, regras de status e casos de erro.

**Testes automatizados** (não precisam de banco):

```powershell
.\gradlew.bat test --tests "com.watchjobs.controller.*"
```
