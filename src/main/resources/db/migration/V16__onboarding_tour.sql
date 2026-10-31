-- V16: tour guiado (F06). Concluir ou pular marca a data; o app só mostra o tour sozinho enquanto ela estiver vazia.
ALTER TABLE core.users ADD COLUMN onboarding_completed_at timestamptz NULL;

COMMENT ON COLUMN core.users.onboarding_completed_at IS
    'Quando o usuário concluiu ou pulou o tour guiado. Ele pode rever o tour pelo menu, sem mudar esta data.';
