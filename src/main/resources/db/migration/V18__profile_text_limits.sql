-- V18: resumo profissional sem corte (F08). O limite de 500 caracteres cortava o resumo vindo do currículo.
ALTER TABLE core.users ALTER COLUMN professional_objective TYPE text;

ALTER TABLE core.users
    ADD CONSTRAINT users_professional_objective_length_check
        CHECK (professional_objective IS NULL OR char_length(professional_objective) <= 10000);

COMMENT ON COLUMN core.users.professional_objective IS 'Resumo profissional (até 10.000 caracteres), digitado ou importado do currículo.';
