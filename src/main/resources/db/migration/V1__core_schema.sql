-- DROP SCHEMA core;

CREATE SCHEMA IF NOT EXISTS core;

COMMENT ON SCHEMA core IS 'Dados permanentes do WatchJobs: usuários, preferências, vagas salvas, candidaturas, currículos e catálogos (skills, idiomas, empresas, locais). Nada aqui expira sozinho.';

-- DROP SEQUENCE core.application_application_id_seq;

-- DROP TABLE core.applicationstage;

CREATE TABLE core.applicationstage ( application_stage_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, "name" varchar(100) NOT NULL, CONSTRAINT applicationstage_pkey PRIMARY KEY (application_stage_id));
COMMENT ON TABLE core.applicationstage IS 'Lista das etapas possíveis de um processo seletivo (ex: triagem, entrevista técnica, proposta). Cada candidatura aponta para uma delas.';

-- Column comments

COMMENT ON COLUMN core.applicationstage.application_stage_id IS 'Número que identifica a etapa. Gerado automaticamente.';
COMMENT ON COLUMN core.applicationstage."name" IS 'Nome da etapa, como aparece para o usuário.';


-- core.certification definition

-- Drop table

-- DROP TABLE core.certification;

CREATE TABLE core.certification ( certification_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, certification_name varchar(255) NOT NULL, issuer varchar(150) NOT NULL, CONSTRAINT certification_pkey PRIMARY KEY (certification_id));
COMMENT ON TABLE core.certification IS 'Catálogo de certificações que existem (ex: AWS Solutions Architect). Usado nas certificações do usuário e nas preferências de busca.';

-- Column comments

COMMENT ON COLUMN core.certification.certification_id IS 'Número que identifica a certificação. Gerado automaticamente.';
COMMENT ON COLUMN core.certification.certification_name IS 'Nome da certificação.';
COMMENT ON COLUMN core.certification.issuer IS 'Quem emite a certificação (ex: Amazon, Microsoft, Google).';


-- core.contact definition

-- Drop table

-- DROP TABLE core.contact;

CREATE TABLE core.contact ( contact_id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL, contact_type varchar(30) NULL, contact_content varchar(100) NULL, CONSTRAINT contact_pkey PRIMARY KEY (contact_id));
COMMENT ON TABLE core.contact IS 'Catálogo de contatos (telefone, e-mail, rede social etc.). Cada linha é um contato. Quem é o dono dele é definido na tabela userscontact.';

-- Column comments

COMMENT ON COLUMN core.contact.contact_id IS 'Número que identifica o contato. Gerado automaticamente.';
COMMENT ON COLUMN core.contact.contact_type IS 'Tipo do contato (ex: telefone, e-mail, LinkedIn).';
COMMENT ON COLUMN core.contact.contact_content IS 'O contato em si: o número, o endereço de e-mail ou o link, conforme o tipo.';


-- core.education definition

-- Drop table

-- DROP TABLE core.education;

CREATE TABLE core.education ( education_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, education_level varchar(30) NOT NULL, field_of_study varchar(100) NOT NULL, CONSTRAINT education_pkey PRIMARY KEY (education_id));
COMMENT ON TABLE core.education IS 'Catálogo de formações acadêmicas, combinando nível e área (ex: Graduação em Ciência da Computação). Usado nas formações do usuário e nas preferências de busca.';

-- Column comments

COMMENT ON COLUMN core.education.education_id IS 'Número que identifica a formação. Gerado automaticamente.';
COMMENT ON COLUMN core.education.education_level IS 'Nível da formação (ex: técnico, graduação, pós-graduação, mestrado).';
COMMENT ON COLUMN core.education.field_of_study IS 'Área de estudo ou curso (ex: Ciência da Computação).';


-- core.job_source definition

-- Drop table

-- DROP TABLE core.job_source;

CREATE TABLE core.job_source ( job_source_id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL, "name" varchar(100) NOT NULL, source_type varchar(20) NOT NULL, base_url text NULL, is_active bool DEFAULT true NOT NULL, allows_storage bool DEFAULT false NOT NULL, rate_limit_per_minute int4 NULL, rate_limit_per_day int4 NULL, health_status varchar(20) DEFAULT 'HEALTHY'::character varying NOT NULL, last_health_check_at timestamptz NULL, circuit_breaker_state varchar(20) DEFAULT 'CLOSED'::character varying NOT NULL, capabilities jsonb DEFAULT '{}'::jsonb NOT NULL, created_at timestamptz DEFAULT now() NOT NULL, updated_at timestamptz DEFAULT now() NOT NULL, requires_attribution bool DEFAULT false NOT NULL, CONSTRAINT job_source_circuit_breaker_state_check CHECK (((circuit_breaker_state)::text = ANY ((ARRAY['CLOSED'::character varying, 'OPEN'::character varying, 'HALF_OPEN'::character varying])::text[]))), CONSTRAINT job_source_health_status_check CHECK (((health_status)::text = ANY ((ARRAY['HEALTHY'::character varying, 'DEGRADED'::character varying, 'DOWN'::character varying])::text[]))), CONSTRAINT job_source_name_key UNIQUE (name), CONSTRAINT job_source_pkey PRIMARY KEY (job_source_id), CONSTRAINT job_source_source_type_check CHECK (((source_type)::text = ANY ((ARRAY['ATS_PUBLIC'::character varying, 'AGGREGATOR'::character varying, 'PARTNER_FEED'::character varying, 'SCRAPING'::character varying])::text[]))));
COMMENT ON TABLE core.job_source IS 'Lista dos sites e serviços de onde o sistema busca vagas (ex: Greenhouse, Adzuna, Gupy). Cada fonte nova que for conectada ganha uma linha aqui.';

-- Column comments

COMMENT ON COLUMN core.job_source.job_source_id IS 'Número que identifica a fonte. Gerado automaticamente.';
COMMENT ON COLUMN core.job_source."name" IS 'Nome da fonte, como a equipe vai reconhecer (ex: Adzuna). Não pode repetir.';
COMMENT ON COLUMN core.job_source.source_type IS 'Tipo da fonte. ATS_PUBLIC = página de vagas da própria empresa; AGGREGATOR = site que junta vagas de vários lugares; PARTNER_FEED = lista de vagas enviada por um parceiro (ex: Gupy); SCRAPING = leitura direta das páginas de um site.';
COMMENT ON COLUMN core.job_source.base_url IS 'Endereço principal da fonte na internet.';
COMMENT ON COLUMN core.job_source.is_active IS 'Liga e desliga a fonte. Se estiver falso, o sistema para de buscar vagas nela, sem precisar apagar nada.';
COMMENT ON COLUMN core.job_source.allows_storage IS 'Diz se as regras da fonte permitem guardar o texto das vagas no nosso banco. Se for falso, guardar só o básico e o link da vaga. Começa como falso por segurança: precisa ser ligado de propósito.';
COMMENT ON COLUMN core.job_source.rate_limit_per_minute IS 'Quantas consultas por minuto a fonte aceita antes de nos bloquear. Vazio = a fonte não informa limite.';
COMMENT ON COLUMN core.job_source.rate_limit_per_day IS 'Mesma ideia da coluna anterior, mas contando por dia.';
COMMENT ON COLUMN core.job_source.health_status IS 'Como a fonte está funcionando agora. HEALTHY = tudo certo; DEGRADED = lenta ou falhando às vezes; DOWN = fora do ar.';
COMMENT ON COLUMN core.job_source.last_health_check_at IS 'Última vez que o sistema conferiu se a fonte estava funcionando.';
COMMENT ON COLUMN core.job_source.circuit_breaker_state IS 'Trava automática que evita ficar insistindo numa fonte quebrada. CLOSED = funcionando normal; OPEN = bloqueada por um tempo depois de muitas falhas seguidas; HALF_OPEN = fazendo um teste para ver se a fonte voltou.';
COMMENT ON COLUMN core.job_source.capabilities IS 'Ficha do que a fonte sabe informar, guardada num campo de texto flexível. Ex: se traz salário, se traz a descrição completa da vaga, se permite buscar só as novidades.';
COMMENT ON COLUMN core.job_source.created_at IS 'Quando a fonte foi cadastrada.';
COMMENT ON COLUMN core.job_source.updated_at IS 'Última vez que os dados da fonte foram alterados.';
COMMENT ON COLUMN core.job_source.requires_attribution IS 'Se verdadeiro, a fonte exige que a gente mostre o nome dela junto da vaga na tela.';


-- core."language" definition

-- Drop table

-- DROP TABLE core."language";

CREATE TABLE core."language" ( language_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, language_name varchar(50) NOT NULL, CONSTRAINT language_pkey PRIMARY KEY (language_id));
COMMENT ON TABLE core."language" IS 'Catálogo de idiomas (ex: português, inglês, espanhol). Usado nos idiomas do usuário e nas preferências de busca.';

-- Column comments

COMMENT ON COLUMN core."language".language_id IS 'Número que identifica o idioma. Gerado automaticamente.';
COMMENT ON COLUMN core."language".language_name IS 'Nome do idioma.';


-- core."location" definition

-- Drop table

-- DROP TABLE core."location";

CREATE TABLE core."location" ( location_id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL, country_code varchar(2) NOT NULL, state text NULL, city text NULL, neighborhood text NULL, postal_code varchar(20) NULL, CONSTRAINT location_pkey PRIMARY KEY (location_id));
COMMENT ON TABLE core."location" IS 'Catálogo de locais (país, estado, cidade, bairro). Usado no endereço do usuário, no local preferido para trabalhar e nas vagas encontradas pela busca.';

-- Column comments

COMMENT ON COLUMN core."location".location_id IS 'Número que identifica o local. Gerado automaticamente.';
COMMENT ON COLUMN core."location".country_code IS 'País, em 2 letras (ex: BR).';
COMMENT ON COLUMN core."location".state IS 'Estado. Vazio quando o local é só o país.';
COMMENT ON COLUMN core."location".city IS 'Cidade. Vazio quando o local é só o estado ou o país.';
COMMENT ON COLUMN core."location".neighborhood IS 'Bairro. Vazio quando não se aplica.';
COMMENT ON COLUMN core."location".postal_code IS 'CEP ou código postal. Vazio quando não se aplica.';


-- core.organization definition

-- Drop table

-- DROP TABLE core.organization;

CREATE TABLE core.organization ( organization_id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL, "name" varchar(150) NOT NULL, organization_type varchar(20) NOT NULL, website_url text NULL, logo_url text NULL, description text NULL, CONSTRAINT organization_pkey PRIMARY KEY (organization_id));
COMMENT ON TABLE core.organization IS 'Catálogo de empresas e organizações. Usado nas experiências profissionais do usuário e nas vagas encontradas pela busca. A vaga salva não aponta para cá: ela guarda uma cópia do nome da empresa.';

-- Column comments

COMMENT ON COLUMN core.organization.organization_id IS 'Número que identifica a organização. Gerado automaticamente.';
COMMENT ON COLUMN core.organization."name" IS 'Nome da organização.';
COMMENT ON COLUMN core.organization.organization_type IS 'Tipo da organização (ex: empresa, instituição de ensino).';
COMMENT ON COLUMN core.organization.website_url IS 'Site da organização.';
COMMENT ON COLUMN core.organization.logo_url IS 'Link da logo da organização.';
COMMENT ON COLUMN core.organization.description IS 'Texto livre sobre a organização.';


-- core.skill definition

-- Drop table

-- DROP TABLE core.skill;

CREATE TABLE core.skill ( skill_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, skill_name varchar(100) NOT NULL, skill_category varchar(50) NOT NULL, CONSTRAINT skill_pkey PRIMARY KEY (skill_id), CONSTRAINT skill_skill_name_key UNIQUE (skill_name));
COMMENT ON TABLE core.skill IS 'Catálogo de skills (habilidades) que existem (ex: Java, SQL, comunicação). Usado nas skills do usuário e nas preferências de busca.';

-- Column comments

COMMENT ON COLUMN core.skill.skill_id IS 'Número que identifica a skill. Gerado automaticamente.';
COMMENT ON COLUMN core.skill.skill_name IS 'Nome da skill. Não pode repetir.';
COMMENT ON COLUMN core.skill.skill_category IS 'Grupo da skill (ex: linguagem de programação, banco de dados, habilidade comportamental).';


-- core.users definition

-- Drop table

-- DROP TABLE core.users;

CREATE TABLE core.users ( users_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, first_name varchar(20) NOT NULL, last_name varchar(40) NOT NULL, birth_date date NOT NULL, profile_image_url text NULL, location_id int8 NULL, email varchar(60) NOT NULL, "password" varchar(255) NOT NULL, CONSTRAINT users_pkey PRIMARY KEY (users_id), CONSTRAINT users_location_id_fkey FOREIGN KEY (location_id) REFERENCES core."location"(location_id));
COMMENT ON TABLE core.users IS 'Usuários do WatchJobs: dados de cadastro e de acesso.';

-- Column comments

COMMENT ON COLUMN core.users.users_id IS 'Número que identifica o usuário. Gerado automaticamente.';
COMMENT ON COLUMN core.users.first_name IS 'Primeiro nome.';
COMMENT ON COLUMN core.users.last_name IS 'Sobrenome.';
COMMENT ON COLUMN core.users.birth_date IS 'Data de nascimento.';
COMMENT ON COLUMN core.users.profile_image_url IS 'Link da foto de perfil.';
COMMENT ON COLUMN core.users.location_id IS 'Onde o usuário mora (ligado à tabela de localização).';
COMMENT ON COLUMN core.users.email IS 'E-mail do usuário.';
COMMENT ON COLUMN core.users."password" IS 'Senha do usuário, guardada de forma criptografada (hash). Nunca deve ser guardada em texto puro.';


-- core.userscontact definition

-- Drop table

-- DROP TABLE core.userscontact;

CREATE TABLE core.userscontact ( users_contact_id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL, contact_id int8 NULL, users_id int4 NULL, CONSTRAINT userscontact_pkey PRIMARY KEY (users_contact_id), CONSTRAINT userscontact_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES core.contact(contact_id), CONSTRAINT userscontact_users_id_fkey FOREIGN KEY (users_id) REFERENCES core.users(users_id));
COMMENT ON TABLE core.userscontact IS 'Liga cada usuário aos seus contatos (telefone, e-mail, redes sociais). Um usuário pode ter vários.';

-- Column comments

COMMENT ON COLUMN core.userscontact.users_contact_id IS 'Número que identifica esta ligação. Gerado automaticamente.';
COMMENT ON COLUMN core.userscontact.contact_id IS 'O contato do usuário (ligado à tabela contact).';
COMMENT ON COLUMN core.userscontact.users_id IS 'Usuário dono deste contato (telefone, e-mail, rede social etc.).';


-- core.usersjobinteraction definition

-- Drop table

-- DROP TABLE core.usersjobinteraction;

CREATE TABLE core.usersjobinteraction ( users_job_interaction_id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL, interaction_type varchar(50) NULL, created_at timestamptz NULL, updated_at timestamptz NULL, users_id int4 NULL, discovered_job_id int8 NULL, CONSTRAINT usersjobinteraction_pkey PRIMARY KEY (users_job_interaction_id), CONSTRAINT usersjobinteraction_users_id_fkey FOREIGN KEY (users_id) REFERENCES core.users(users_id));
CREATE INDEX idx_usersjobinteraction_user_discovered_job ON core.usersjobinteraction USING btree (users_id, discovered_job_id) WHERE (discovered_job_id IS NOT NULL);
COMMENT ON TABLE core.usersjobinteraction IS 'Registro do que o usuário fez com uma vaga encontrada pela busca automática: viu, dispensou ou ocultou.';

-- Column comments

COMMENT ON COLUMN core.usersjobinteraction.users_job_interaction_id IS 'Número que identifica a interação. Gerado automaticamente.';
COMMENT ON COLUMN core.usersjobinteraction.interaction_type IS 'Tipo da ação do usuário. Exemplos: VIEWED (viu), DISMISSED (dispensou), HIDDEN (ocultou).';
COMMENT ON COLUMN core.usersjobinteraction.created_at IS 'Quando a interação aconteceu (data e hora).';
COMMENT ON COLUMN core.usersjobinteraction.updated_at IS 'Última vez que a interação foi alterada (data e hora).';
COMMENT ON COLUMN core.usersjobinteraction.users_id IS 'Usuário que fez a interação com a vaga.';
COMMENT ON COLUMN core.usersjobinteraction.discovered_job_id IS 'Número da vaga encontrada pela busca automática com a qual o usuário interagiu. Sem ligação oficial com a tabela dessas vagas, de propósito: ela é limpa todo dia e a ligação travaria a limpeza. Quando a vaga some da busca, esta linha perde o sentido e pode ser apagada.';


-- core.userskill definition

-- Drop table

-- DROP TABLE core.userskill;

CREATE TABLE core.userskill ( user_skill_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, skill_level varchar(20) NOT NULL, years_experience numeric(3, 1) NULL, users_id int4 NOT NULL, skill_id int4 NOT NULL, CONSTRAINT userskill_pkey PRIMARY KEY (user_skill_id), CONSTRAINT skill_id FOREIGN KEY (skill_id) REFERENCES core.skill(skill_id), CONSTRAINT users_id FOREIGN KEY (users_id) REFERENCES core.users(users_id));
COMMENT ON TABLE core.userskill IS 'Skills que o usuário afirma ter, com o nível dele em cada uma.';

-- Column comments

COMMENT ON COLUMN core.userskill.user_skill_id IS 'Número que identifica esta skill do usuário. Gerado automaticamente.';
COMMENT ON COLUMN core.userskill.skill_level IS 'Nível do usuário nessa skill (ex: básico, intermediário, avançado).';
COMMENT ON COLUMN core.userskill.years_experience IS 'Anos de experiência com a skill. Aceita uma casa decimal (ex: 2,5). Vazio = não informado.';
COMMENT ON COLUMN core.userskill.users_id IS 'Usuário dono da skill.';
COMMENT ON COLUMN core.userskill.skill_id IS 'A skill (ligado à tabela skill).';


-- core.experience definition

-- Drop table

-- DROP TABLE core.experience;

CREATE TABLE core.experience ( experience_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, job_title varchar(150) NOT NULL, "current" bool DEFAULT true NULL, experience_type varchar(20) NOT NULL, description text NULL, start_date date NOT NULL, end_date date NULL, organization_id int8 NOT NULL, users_id int4 NOT NULL, CONSTRAINT experience_pkey PRIMARY KEY (experience_id), CONSTRAINT organization_id FOREIGN KEY (organization_id) REFERENCES core.organization(organization_id), CONSTRAINT users_id FOREIGN KEY (users_id) REFERENCES core.users(users_id));
COMMENT ON TABLE core.experience IS 'Experiências profissionais do usuário (empregos, estágios etc.).';

-- Column comments

COMMENT ON COLUMN core.experience.experience_id IS 'Número que identifica a experiência. Gerado automaticamente.';
COMMENT ON COLUMN core.experience.job_title IS 'Cargo que o usuário ocupou.';
COMMENT ON COLUMN core.experience."current" IS 'Verdadeiro se é a experiência atual (o usuário ainda trabalha lá). Nesse caso a data de término fica vazia.';
COMMENT ON COLUMN core.experience.experience_type IS 'Tipo da experiência (ex: emprego, estágio, freelance).';
COMMENT ON COLUMN core.experience.description IS 'Descrição das atividades e conquistas no cargo.';
COMMENT ON COLUMN core.experience.start_date IS 'Data em que o usuário começou.';
COMMENT ON COLUMN core.experience.end_date IS 'Data em que o usuário saiu. Vazio = ainda está lá.';
COMMENT ON COLUMN core.experience.organization_id IS 'Empresa onde o usuário teve esta experiência profissional.';
COMMENT ON COLUMN core.experience.users_id IS 'Usuário dono da experiência.';


-- core.saved_job definition

-- Drop table

-- DROP TABLE core.saved_job;

CREATE TABLE core.saved_job ( saved_job_id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL, users_id int4 NOT NULL, source_id int8 NULL, external_id varchar(255) NULL, position_role varchar(255) NOT NULL, organization_name varchar(150) NOT NULL, organization_logo_url text NULL, organization_website text NULL, location_city text NULL, location_state text NULL, location_country_code varchar(2) NULL, work_mode varchar(30) NULL, experience_level varchar(30) NULL, job_type varchar(50) NULL, salary_min numeric(10, 2) NULL, salary_max numeric(10, 2) NULL, salary_currency bpchar(3) NULL, salary_period varchar(20) NULL, full_description text NULL, job_benefits text NULL, job_url text NULL, requirements_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL, match_score_at_save numeric(5, 2) NULL, match_breakdown_at_save jsonb NULL, status varchar(30) DEFAULT 'SAVED'::character varying NOT NULL, saved_at timestamptz DEFAULT now() NOT NULL, status_updated_at timestamptz DEFAULT now() NOT NULL, notes text NULL, CONSTRAINT saved_job_match_score_check CHECK (((match_score_at_save IS NULL) OR ((match_score_at_save >= (0)::numeric) AND (match_score_at_save <= (100)::numeric)))), CONSTRAINT saved_job_pkey PRIMARY KEY (saved_job_id), CONSTRAINT saved_job_salary_range_check CHECK (((salary_min IS NULL) OR (salary_max IS NULL) OR (salary_min <= salary_max))), CONSTRAINT saved_job_status_check CHECK (((status)::text = ANY ((ARRAY['SAVED'::character varying, 'PLANNING_TO_APPLY'::character varying, 'APPLIED'::character varying, 'IN_PROCESS'::character varying, 'OFFER_RECEIVED'::character varying, 'ACCEPTED'::character varying, 'REJECTED'::character varying, 'WITHDRAWN'::character varying])::text[]))), CONSTRAINT saved_job_user_source_external_key UNIQUE (users_id, source_id, external_id), CONSTRAINT fk_saved_job_source_id FOREIGN KEY (source_id) REFERENCES core.job_source(job_source_id), CONSTRAINT fk_saved_job_users_id FOREIGN KEY (users_id) REFERENCES core.users(users_id));
CREATE INDEX idx_saved_job_users_status ON core.saved_job USING btree (users_id, status);
COMMENT ON TABLE core.saved_job IS 'Vagas que o usuário salvou. Cada linha é uma cópia completa da vaga no momento em que foi salva, então continua existindo mesmo que a vaga original saia do ar ou seja apagada da busca.';

-- Column comments

COMMENT ON COLUMN core.saved_job.saved_job_id IS 'Número que identifica a vaga salva. Gerado automaticamente.';
COMMENT ON COLUMN core.saved_job.users_id IS 'Dono da vaga salva: o usuário que clicou em salvar.';
COMMENT ON COLUMN core.saved_job.source_id IS 'De qual fonte a vaga veio. Fica vazio quando o usuário cadastrou a vaga manualmente.';
COMMENT ON COLUMN core.saved_job.external_id IS 'Código da vaga dentro da fonte original. Serve para o sistema perceber que o usuário já salvou essa mesma vaga e não deixar salvar duas vezes. Vagas cadastradas manualmente não têm esse código, então não passam por essa checagem.';
COMMENT ON COLUMN core.saved_job.position_role IS 'Nome do cargo da vaga (ex: Desenvolvedor Backend Pleno).';
COMMENT ON COLUMN core.saved_job.organization_name IS 'Nome da empresa, escrito como texto. Não é ligado à tabela de empresas de propósito: se a empresa for alterada lá, a vaga salva continua como estava.';
COMMENT ON COLUMN core.saved_job.organization_logo_url IS 'Link da logo da empresa.';
COMMENT ON COLUMN core.saved_job.organization_website IS 'Site da empresa.';
COMMENT ON COLUMN core.saved_job.location_city IS 'Cidade da vaga, escrita como texto (também não é ligada à tabela de localização, pelo mesmo motivo da empresa).';
COMMENT ON COLUMN core.saved_job.location_state IS 'Estado da vaga, como texto.';
COMMENT ON COLUMN core.saved_job.location_country_code IS 'País da vaga, em 2 letras (ex: BR).';
COMMENT ON COLUMN core.saved_job.work_mode IS 'Modelo de trabalho: remoto, híbrido ou presencial.';
COMMENT ON COLUMN core.saved_job.experience_level IS 'Nível de experiência pedido: júnior, pleno, sênior etc.';
COMMENT ON COLUMN core.saved_job.job_type IS 'Tipo de contrato: CLT, PJ, estágio etc.';
COMMENT ON COLUMN core.saved_job.salary_min IS 'Menor salário informado pela vaga. Vazio = a vaga não informou salário (isso não significa salário zero).';
COMMENT ON COLUMN core.saved_job.salary_max IS 'Maior salário informado pela vaga. Vazio = não informado.';
COMMENT ON COLUMN core.saved_job.salary_currency IS 'Moeda do salário, em 3 letras (ex: BRL).';
COMMENT ON COLUMN core.saved_job.salary_period IS 'A que período o salário se refere: por hora, por mês, por ano.';
COMMENT ON COLUMN core.saved_job.full_description IS 'Texto completo da vaga, como foi publicado.';
COMMENT ON COLUMN core.saved_job.job_benefits IS 'Benefícios oferecidos (vale-refeição, plano de saúde etc.).';
COMMENT ON COLUMN core.saved_job.job_url IS 'Link original da vaga, onde o usuário se candidata.';
COMMENT ON COLUMN core.saved_job.requirements_snapshot IS 'Cópia do que a vaga exige (skills, idiomas, certificações, escolaridade) na hora em que foi salva. Fica num único campo de texto organizado, só para exibir na tela.';
COMMENT ON COLUMN core.saved_job.match_score_at_save IS 'Porcentagem de compatibilidade (0 a 100) que o sistema calculou no momento em que o usuário salvou a vaga. Fica congelada: não muda mesmo que o cálculo seja alterado depois.';
COMMENT ON COLUMN core.saved_job.match_breakdown_at_save IS 'O "porquê" daquela porcentagem: quantos pontos cada critério (cargo, skills, senioridade, local, salário etc.) rendeu. Também fica congelado.';
COMMENT ON COLUMN core.saved_job.status IS 'Em que pé está a vaga para o usuário. SAVED = só salva; PLANNING_TO_APPLY = pretende se candidatar; APPLIED = já se candidatou; IN_PROCESS = em processo seletivo; OFFER_RECEIVED = recebeu proposta; ACCEPTED = aceitou; REJECTED = foi recusado; WITHDRAWN = desistiu.';
COMMENT ON COLUMN core.saved_job.saved_at IS 'Quando o usuário salvou a vaga.';
COMMENT ON COLUMN core.saved_job.status_updated_at IS 'Última vez que o status mudou. O backend precisa atualizar este campo sempre que mudar o status.';
COMMENT ON COLUMN core.saved_job.notes IS 'Anotações pessoais do usuário sobre a vaga.';


-- core.saved_job_contact definition

-- Drop table

-- DROP TABLE core.saved_job_contact;

CREATE TABLE core.saved_job_contact ( saved_job_contact_id int8 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START 1 CACHE 1 NO CYCLE) NOT NULL, saved_job_id int8 NOT NULL, contact_name varchar(150) NULL, contact_role varchar(100) NULL, email varchar(255) NULL, phone varchar(30) NULL, social_url text NULL, notes text NULL, created_at timestamptz DEFAULT now() NOT NULL, updated_at timestamptz DEFAULT now() NOT NULL, CONSTRAINT saved_job_contact_has_data_check CHECK (((contact_name IS NOT NULL) OR (email IS NOT NULL) OR (phone IS NOT NULL) OR (social_url IS NOT NULL))), CONSTRAINT saved_job_contact_pkey PRIMARY KEY (saved_job_contact_id), CONSTRAINT fk_saved_job_contact_saved_job_id FOREIGN KEY (saved_job_id) REFERENCES core.saved_job(saved_job_id) ON DELETE CASCADE);
CREATE INDEX idx_saved_job_contact_saved_job_id ON core.saved_job_contact USING btree (saved_job_id);
COMMENT ON TABLE core.saved_job_contact IS 'Contatos ligados a uma vaga salva (recrutador, gestor, indicação etc.). Uma vaga salva pode ter vários. Se a vaga salva for apagada, os contatos dela também são.';

-- Column comments

COMMENT ON COLUMN core.saved_job_contact.saved_job_contact_id IS 'Número que identifica o contato. Gerado automaticamente.';
COMMENT ON COLUMN core.saved_job_contact.saved_job_id IS 'A vaga salva à qual este contato pertence.';
COMMENT ON COLUMN core.saved_job_contact.contact_name IS 'Nome da pessoa de contato (ex: Mariana Souza).';
COMMENT ON COLUMN core.saved_job_contact.contact_role IS 'Função da pessoa em relação à vaga (ex: Recrutadora, Gestor da área, Indicação).';
COMMENT ON COLUMN core.saved_job_contact.email IS 'E-mail da pessoa de contato.';
COMMENT ON COLUMN core.saved_job_contact.phone IS 'Telefone ou WhatsApp da pessoa de contato, do jeito que o usuário digitou (com DDD).';
COMMENT ON COLUMN core.saved_job_contact.social_url IS 'Link do perfil da pessoa numa rede social (ex: LinkedIn).';
COMMENT ON COLUMN core.saved_job_contact.notes IS 'Anotações do usuário sobre este contato (ex: falei por telefone em 10/03, pediu o currículo).';
COMMENT ON COLUMN core.saved_job_contact.created_at IS 'Quando o contato foi cadastrado.';
COMMENT ON COLUMN core.saved_job_contact.updated_at IS 'Última vez que o contato foi alterado. O backend precisa atualizar este campo a cada alteração.';


-- core.usercertification definition

-- Drop table

-- DROP TABLE core.usercertification;

CREATE TABLE core.usercertification ( user_certification_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, issue_date date NOT NULL, expiration_date date NOT NULL, users_id int4 NOT NULL, certification_id int4 NOT NULL, CONSTRAINT usercertification_pkey PRIMARY KEY (user_certification_id), CONSTRAINT certification_id FOREIGN KEY (certification_id) REFERENCES core.certification(certification_id), CONSTRAINT users_id FOREIGN KEY (users_id) REFERENCES core.users(users_id));
COMMENT ON TABLE core.usercertification IS 'Certificações que o usuário possui.';

-- Column comments

COMMENT ON COLUMN core.usercertification.user_certification_id IS 'Número que identifica esta certificação do usuário. Gerado automaticamente.';
COMMENT ON COLUMN core.usercertification.issue_date IS 'Data em que a certificação foi emitida.';
COMMENT ON COLUMN core.usercertification.expiration_date IS 'Data em que a certificação vence.';
COMMENT ON COLUMN core.usercertification.users_id IS 'Usuário dono da certificação.';
COMMENT ON COLUMN core.usercertification.certification_id IS 'A certificação (ligado à tabela certification).';


-- core.usereducation definition

-- Drop table

-- DROP TABLE core.usereducation;

CREATE TABLE core.usereducation ( user_education_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, institution varchar(150) NOT NULL, start_date date NOT NULL, end_date date NOT NULL, "current" bool NOT NULL, users_id int4 NOT NULL, education_id int4 NOT NULL, CONSTRAINT usereducation_pkey PRIMARY KEY (user_education_id), CONSTRAINT education_id FOREIGN KEY (education_id) REFERENCES core.education(education_id), CONSTRAINT users_id FOREIGN KEY (users_id) REFERENCES core.users(users_id));
COMMENT ON TABLE core.usereducation IS 'Formações acadêmicas do usuário (cursos concluídos ou em andamento).';

-- Column comments

COMMENT ON COLUMN core.usereducation.user_education_id IS 'Número que identifica esta formação do usuário. Gerado automaticamente.';
COMMENT ON COLUMN core.usereducation.institution IS 'Nome da instituição de ensino.';
COMMENT ON COLUMN core.usereducation.start_date IS 'Data de início do curso.';
COMMENT ON COLUMN core.usereducation.end_date IS 'Data de término do curso (ou a prevista, se ainda estiver cursando).';
COMMENT ON COLUMN core.usereducation."current" IS 'Verdadeiro se o usuário ainda está cursando.';
COMMENT ON COLUMN core.usereducation.users_id IS 'Usuário dono da formação.';
COMMENT ON COLUMN core.usereducation.education_id IS 'A formação (ligado à tabela education).';


-- core.userlanguage definition

-- Drop table

-- DROP TABLE core.userlanguage;

CREATE TABLE core.userlanguage ( user_language_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, proficiency_level varchar(2) NOT NULL, language_id int4 NOT NULL, users_id int4 NOT NULL, CONSTRAINT userlanguage_pkey PRIMARY KEY (user_language_id), CONSTRAINT language_id FOREIGN KEY (language_id) REFERENCES core."language"(language_id), CONSTRAINT users_id FOREIGN KEY (users_id) REFERENCES core.users(users_id));
COMMENT ON TABLE core.userlanguage IS 'Idiomas que o usuário fala, com o nível dele em cada um.';

-- Column comments

COMMENT ON COLUMN core.userlanguage.user_language_id IS 'Número que identifica este idioma do usuário. Gerado automaticamente.';
COMMENT ON COLUMN core.userlanguage.proficiency_level IS 'Nível no idioma, em 2 caracteres, no padrão europeu de idiomas (ex: A1, B2, C1).';
COMMENT ON COLUMN core.userlanguage.language_id IS 'O idioma (ligado à tabela language).';
COMMENT ON COLUMN core.userlanguage.users_id IS 'Usuário que fala o idioma.';


-- core.userpreference definition

-- Drop table

-- DROP TABLE core.userpreference;

CREATE TABLE core.userpreference ( users_preference_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, preferred_work_mode varchar(20) NOT NULL, preferred_salary_min numeric(10, 2) NULL, preferred_salary_max numeric(10, 2) NULL, preferred_job_type varchar(20) NULL, preferred_location_id int8 NULL, users_id int4 NOT NULL, job_post_date_min date NULL, job_post_date_max date NULL, preferred_experience_level varchar(30) NOT NULL, auto_search_enabled bool DEFAULT false NOT NULL, next_run_at timestamptz NULL, preferred_roles _text NOT NULL, keywords _text NULL, profile_version int4 DEFAULT 1 NOT NULL, CONSTRAINT userpreference_pkey PRIMARY KEY (users_preference_id), CONSTRAINT fk_preference_user FOREIGN KEY (users_id) REFERENCES core.users(users_id), CONSTRAINT fk_preferred_location_id FOREIGN KEY (preferred_location_id) REFERENCES core."location"(location_id));
COMMENT ON TABLE core.userpreference IS 'Preferências de busca do usuário: o que ele quer encontrar (cargos, modelo de trabalho, salário, local etc.). É o perfil usado pela busca automática de vagas.';

-- Column comments

COMMENT ON COLUMN core.userpreference.users_preference_id IS 'Número que identifica o conjunto de preferências. Gerado automaticamente.';
COMMENT ON COLUMN core.userpreference.preferred_work_mode IS 'Modelo de trabalho que o usuário prefere: remoto, híbrido ou presencial.';
COMMENT ON COLUMN core.userpreference.preferred_salary_min IS 'Menor salário que o usuário aceita. Vazio = sem mínimo.';
COMMENT ON COLUMN core.userpreference.preferred_salary_max IS 'Maior salário da faixa que o usuário procura. Vazio = sem limite.';
COMMENT ON COLUMN core.userpreference.preferred_job_type IS 'Tipo de contrato que o usuário prefere (CLT, PJ, estágio etc.). Vazio = qualquer.';
COMMENT ON COLUMN core.userpreference.preferred_location_id IS 'Local onde o usuário quer trabalhar (ligado à tabela de localização).';
COMMENT ON COLUMN core.userpreference.users_id IS 'Usuário dono destas preferências.';
COMMENT ON COLUMN core.userpreference.job_post_date_min IS 'Data de publicação mais antiga aceita para as vagas (o usuário não quer vagas publicadas antes disso). Vazio = sem limite.';
COMMENT ON COLUMN core.userpreference.job_post_date_max IS 'Data de publicação mais recente aceita para as vagas. Vazio = sem limite.';
COMMENT ON COLUMN core.userpreference.preferred_experience_level IS 'Nível de experiência das vagas que o usuário quer ver: júnior, pleno, sênior etc.';
COMMENT ON COLUMN core.userpreference.auto_search_enabled IS 'Liga ou desliga a busca automática de vagas. Quando ligada, o sistema procura vagas novas de tempos em tempos, mesmo com o usuário fora do site. Começa desligada.';
COMMENT ON COLUMN core.userpreference.next_run_at IS 'Quando o sistema deve fazer a próxima busca automática para este usuário. Vazio = nenhuma busca agendada (busca automática desligada).';
COMMENT ON COLUMN core.userpreference.preferred_roles IS 'Cargos que o usuário quer encontrar (ex: Desenvolvedor Backend, Analista de Dados). Pode ter vários. É o principal critério da busca de vagas.';
COMMENT ON COLUMN core.userpreference.keywords IS 'Palavras soltas que o usuário quer encontrar nas vagas (ex: fintech, Kotlin, startup). Pode ter várias.';
COMMENT ON COLUMN core.userpreference.profile_version IS 'Número que sobe 1 a cada vez que o usuário muda suas preferências. O sistema usa isso para saber que os resultados antigos não valem mais e precisam ser refeitos.';


-- core.application definition

-- Drop table

-- DROP TABLE core.application;

CREATE TABLE core.application ( application_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, application_status varchar(20) NOT NULL, start_date date NOT NULL, end_date date NULL, notes text NULL, feedback text NULL, application_stage_id int4 NOT NULL, users_id int4 NOT NULL, saved_job_id int8 NOT NULL, CONSTRAINT application_pkey PRIMARY KEY (application_id), CONSTRAINT application_saved_job_id_fkey FOREIGN KEY (saved_job_id) REFERENCES core.saved_job(saved_job_id), CONSTRAINT application_stage_id FOREIGN KEY (application_stage_id) REFERENCES core.applicationstage(application_stage_id), CONSTRAINT users_id FOREIGN KEY (users_id) REFERENCES core.users(users_id));
CREATE INDEX idx_application_saved_job_id ON core.application USING btree (saved_job_id);
COMMENT ON TABLE core.application IS 'Candidaturas do usuário. Cada candidatura aponta para uma vaga salva e para a etapa em que está no processo seletivo.';

-- Column comments

COMMENT ON COLUMN core.application.application_id IS 'Número que identifica a candidatura. Gerado automaticamente.';
COMMENT ON COLUMN core.application.application_status IS 'Situação da candidatura (ex: em andamento, aprovada, recusada).';
COMMENT ON COLUMN core.application.start_date IS 'Data em que o usuário se candidatou.';
COMMENT ON COLUMN core.application.end_date IS 'Data em que o processo terminou. Vazio = ainda em andamento.';
COMMENT ON COLUMN core.application.notes IS 'Anotações pessoais do usuário sobre a candidatura.';
COMMENT ON COLUMN core.application.feedback IS 'Retorno dado pela empresa (ex: motivo da recusa, comentários da entrevista).';
COMMENT ON COLUMN core.application.application_stage_id IS 'Etapa em que a candidatura está agora (ligado à tabela applicationstage).';
COMMENT ON COLUMN core.application.users_id IS 'Usuário que se candidatou.';
COMMENT ON COLUMN core.application.saved_job_id IS 'A vaga salva à qual esta candidatura se refere.';


-- core.certificationuserpreference definition

-- Drop table

-- DROP TABLE core.certificationuserpreference;

CREATE TABLE core.certificationuserpreference ( certification_preference_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, users_preference_id int4 NOT NULL, certification_id int4 NOT NULL, CONSTRAINT certificationuserpreference_pkey PRIMARY KEY (certification_preference_id), CONSTRAINT fk_certification_preference FOREIGN KEY (certification_id) REFERENCES core.certification(certification_id), CONSTRAINT fk_user_preference_certification FOREIGN KEY (users_preference_id) REFERENCES core.userpreference(users_preference_id));
COMMENT ON TABLE core.certificationuserpreference IS 'Certificações que o usuário quer ver pedidas nas vagas.';

-- Column comments

COMMENT ON COLUMN core.certificationuserpreference.certification_preference_id IS 'Número que identifica esta preferência. Gerado automaticamente.';
COMMENT ON COLUMN core.certificationuserpreference.users_preference_id IS 'O conjunto de preferências de busca ao qual esta certificação pertence.';
COMMENT ON COLUMN core.certificationuserpreference.certification_id IS 'A certificação desejada (ligado à tabela certification).';


-- core.coverletter definition

-- Drop table

-- DROP TABLE core.coverletter;

CREATE TABLE core.coverletter ( cover_letter_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, created_at date NOT NULL, cover_letter_content text NULL, cover_letter_file text NULL, users_id int4 NOT NULL, saved_job_id int8 NOT NULL, CONSTRAINT coverletter_pkey PRIMARY KEY (cover_letter_id), CONSTRAINT coverletter_saved_job_id_fkey FOREIGN KEY (saved_job_id) REFERENCES core.saved_job(saved_job_id), CONSTRAINT users_id FOREIGN KEY (users_id) REFERENCES core.users(users_id));
CREATE INDEX idx_coverletter_saved_job_id ON core.coverletter USING btree (saved_job_id);
COMMENT ON TABLE core.coverletter IS 'Cartas de apresentação escritas pelo usuário, cada uma para uma vaga salva.';

-- Column comments

COMMENT ON COLUMN core.coverletter.cover_letter_id IS 'Número que identifica a carta. Gerado automaticamente.';
COMMENT ON COLUMN core.coverletter.created_at IS 'Data em que a carta foi criada.';
COMMENT ON COLUMN core.coverletter.cover_letter_content IS 'Texto da carta.';
COMMENT ON COLUMN core.coverletter.cover_letter_file IS 'Arquivo da carta (caminho ou link), quando ela existir também em arquivo.';
COMMENT ON COLUMN core.coverletter.users_id IS 'Usuário que escreveu a carta.';
COMMENT ON COLUMN core.coverletter.saved_job_id IS 'A vaga salva para a qual esta carta de apresentação foi escrita.';


-- core.educationuserpreference definition

-- Drop table

-- DROP TABLE core.educationuserpreference;

CREATE TABLE core.educationuserpreference ( education_preference_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, users_preference_id int4 NOT NULL, education_id int4 NOT NULL, CONSTRAINT educationuserpreference_pkey PRIMARY KEY (education_preference_id), CONSTRAINT fk_education_preference FOREIGN KEY (education_id) REFERENCES core.education(education_id), CONSTRAINT fk_user_preference_education FOREIGN KEY (users_preference_id) REFERENCES core.userpreference(users_preference_id));
COMMENT ON TABLE core.educationuserpreference IS 'Formações acadêmicas que o usuário quer ver pedidas nas vagas.';

-- Column comments

COMMENT ON COLUMN core.educationuserpreference.education_preference_id IS 'Número que identifica esta preferência. Gerado automaticamente.';
COMMENT ON COLUMN core.educationuserpreference.users_preference_id IS 'O conjunto de preferências de busca ao qual esta formação pertence.';
COMMENT ON COLUMN core.educationuserpreference.education_id IS 'A formação desejada (ligado à tabela education).';


-- core.languageuserpreference definition

-- Drop table

-- DROP TABLE core.languageuserpreference;

CREATE TABLE core.languageuserpreference ( language_preference_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, language_id int4 NOT NULL, users_preference_id int4 NOT NULL, CONSTRAINT languageuserpreference_pkey PRIMARY KEY (language_preference_id), CONSTRAINT language_id FOREIGN KEY (language_id) REFERENCES core."language"(language_id), CONSTRAINT users_preference_id FOREIGN KEY (users_preference_id) REFERENCES core.userpreference(users_preference_id));
COMMENT ON TABLE core.languageuserpreference IS 'Idiomas que o usuário quer ver pedidos nas vagas.';

-- Column comments

COMMENT ON COLUMN core.languageuserpreference.language_preference_id IS 'Número que identifica esta preferência. Gerado automaticamente.';
COMMENT ON COLUMN core.languageuserpreference.language_id IS 'O idioma desejado (ligado à tabela language).';
COMMENT ON COLUMN core.languageuserpreference.users_preference_id IS 'O conjunto de preferências de busca ao qual este idioma pertence.';


-- core.resume definition

-- Drop table

-- DROP TABLE core.resume;

CREATE TABLE core.resume ( resume_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, created_at date NULL, resume_content text NULL, resume_file text NULL, users_id int4 NOT NULL, saved_job_id int8 NOT NULL, CONSTRAINT resume_pkey PRIMARY KEY (resume_id), CONSTRAINT resume_saved_job_id_fkey FOREIGN KEY (saved_job_id) REFERENCES core.saved_job(saved_job_id), CONSTRAINT users_id FOREIGN KEY (users_id) REFERENCES core.users(users_id));
CREATE INDEX idx_resume_saved_job_id ON core.resume USING btree (saved_job_id);
COMMENT ON TABLE core.resume IS 'Currículos do usuário, cada um adaptado para uma vaga salva.';

-- Column comments

COMMENT ON COLUMN core.resume.resume_id IS 'Número que identifica o currículo. Gerado automaticamente.';
COMMENT ON COLUMN core.resume.created_at IS 'Data em que o currículo foi criado.';
COMMENT ON COLUMN core.resume.resume_content IS 'Texto do currículo.';
COMMENT ON COLUMN core.resume.resume_file IS 'Arquivo do currículo (caminho ou link), quando ele existir também em arquivo.';
COMMENT ON COLUMN core.resume.users_id IS 'Usuário dono do currículo.';
COMMENT ON COLUMN core.resume.saved_job_id IS 'A vaga salva para a qual este currículo foi adaptado.';


-- core.skilluserpreference definition

-- Drop table

-- DROP TABLE core.skilluserpreference;

CREATE TABLE core.skilluserpreference ( skill_preference_id int4 GENERATED ALWAYS AS IDENTITY( INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START 1 CACHE 1 NO CYCLE) NOT NULL, users_preference_id int4 NOT NULL, skill_id int4 NOT NULL, CONSTRAINT skilluserpreference_pkey PRIMARY KEY (skill_preference_id), CONSTRAINT fk_skill_preference FOREIGN KEY (skill_id) REFERENCES core.skill(skill_id), CONSTRAINT fk_user_preference_skill FOREIGN KEY (users_preference_id) REFERENCES core.userpreference(users_preference_id));
COMMENT ON TABLE core.skilluserpreference IS 'Skills que o usuário quer ver pedidas nas vagas. Podem ser skills que ele ainda não tem: a preferência não depende das skills do perfil dele.';

-- Column comments

COMMENT ON COLUMN core.skilluserpreference.skill_preference_id IS 'Número que identifica esta preferência. Gerado automaticamente.';
COMMENT ON COLUMN core.skilluserpreference.users_preference_id IS 'O conjunto de preferências de busca ao qual esta skill pertence.';
COMMENT ON COLUMN core.skilluserpreference.skill_id IS 'A skill desejada (ligado à tabela skill).';