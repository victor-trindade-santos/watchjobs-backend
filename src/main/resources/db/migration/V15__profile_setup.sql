-- V15: configuração inicial do perfil (F05). O backend guarda a etapa atual e quando o assistente foi concluído.
-- Todos começam sem conclusão, inclusive os usuários antigos: eles passam pelo assistente uma vez.
ALTER TABLE core.users
    ADD COLUMN profile_setup_step varchar(30) NULL,
    ADD COLUMN profile_setup_completed_at timestamptz NULL;

COMMENT ON COLUMN core.users.profile_setup_step IS 'Última etapa salva do assistente de perfil, para continuar de onde parou.';
COMMENT ON COLUMN core.users.profile_setup_completed_at IS
    'Quando o assistente foi concluído (resumo, 1 experiência, 3 skills e preferências confirmadas). Vazio = acesso limitado ao perfil.';
