ALTER TABLE core.users
    ALTER COLUMN first_name TYPE VARCHAR(60),
    ALTER COLUMN last_name TYPE VARCHAR(80),
    ALTER COLUMN email TYPE VARCHAR(254);

CREATE UNIQUE INDEX uq_users_email_lower ON core.users (lower(email));

ALTER TABLE core.users
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_login_at timestamptz NULL;

ALTER TABLE core.application
    ADD COLUMN interview_at timestamp NULL;

DROP INDEX core.idx_application_saved_job_id;

CREATE UNIQUE INDEX uq_application_saved_job_id ON core.application (saved_job_id);

ALTER TABLE core.application
    DROP CONSTRAINT application_saved_job_id_fkey;

ALTER TABLE core.application
    ADD CONSTRAINT application_saved_job_id_fkey
    FOREIGN KEY (saved_job_id)
    REFERENCES core.saved_job (saved_job_id)
    ON DELETE CASCADE;

INSERT INTO core.applicationstage (name) VALUES
    ('Triagem'),
    ('Entrevista RH'),
    ('Teste técnico'),
    ('Entrevista técnica'),
    ('Entrevista final'),
    ('Proposta');