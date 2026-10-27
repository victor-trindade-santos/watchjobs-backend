-- V12: objetivo profissional e disponibilidade no perfil.
ALTER TABLE core.users
    ADD COLUMN professional_objective varchar(500) NULL,
    ADD COLUMN availability varchar(20) NULL,
    ADD CONSTRAINT users_availability_check
        CHECK (availability IS NULL OR availability IN ('IMMEDIATE', 'TWO_WEEKS', 'ONE_MONTH', 'NOT_LOOKING'));

COMMENT ON COLUMN core.users.professional_objective IS 'Frase curta do cabeçalho do perfil (o que a pessoa procura).';
COMMENT ON COLUMN core.users.availability IS 'Quando a pessoa pode começar: IMMEDIATE, TWO_WEEKS, ONE_MONTH ou NOT_LOOKING.';
