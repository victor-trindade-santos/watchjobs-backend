ALTER TABLE core.users
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN email_verified_at timestamptz NULL,
    ADD COLUMN password_changed_at timestamptz NULL;

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON core.users
    FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

COMMENT ON COLUMN core.users."password" IS 'Hash BCrypt da senha. Nunca a senha.';
COMMENT ON COLUMN core.users.password_changed_at IS
    'Última troca de senha. Tokens de acesso emitidos antes disso são recusados.';

CREATE TABLE core.refresh_token (
    refresh_token_id bigint GENERATED ALWAYS AS IDENTITY,
    users_id int4 NOT NULL,
    token_hash char(64) NOT NULL,
    family_id uuid NOT NULL,
    device_label varchar(100) NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz NULL,
    replaced_by_id bigint NULL,
    CONSTRAINT refresh_token_pkey PRIMARY KEY (refresh_token_id),
    CONSTRAINT refresh_token_token_hash_key UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_token_replaced_by_id FOREIGN KEY (replaced_by_id)
        REFERENCES core.refresh_token (refresh_token_id) ON DELETE SET NULL,
    CONSTRAINT refresh_token_expiry_check CHECK (expires_at > created_at)
);
CREATE INDEX idx_refresh_token_users_active ON core.refresh_token (users_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_token_family_id ON core.refresh_token (family_id);
CREATE INDEX idx_refresh_token_expires_at ON core.refresh_token (expires_at);

CREATE TABLE core.password_reset_code (
    password_reset_code_id bigint GENERATED ALWAYS AS IDENTITY,
    users_id int4 NOT NULL,
    code_hash varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    attempts smallint NOT NULL DEFAULT 0,
    used_at timestamptz NULL,
    revoked_at timestamptz NULL,
    CONSTRAINT password_reset_code_pkey PRIMARY KEY (password_reset_code_id),
    CONSTRAINT fk_password_reset_code_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT password_reset_code_attempts_check CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT password_reset_code_expiry_check CHECK (expires_at > created_at)
);
CREATE INDEX idx_password_reset_code_active ON core.password_reset_code (users_id, created_at DESC)
    WHERE used_at IS NULL AND revoked_at IS NULL;

CREATE OR REPLACE FUNCTION core.purge_expired_auth(p_keep_days integer DEFAULT 7)
RETURNS void
LANGUAGE sql
AS $function$
    DELETE FROM core.password_reset_code
     WHERE expires_at < now() - make_interval(days => p_keep_days);
    DELETE FROM core.refresh_token
     WHERE expires_at < now() - make_interval(days => p_keep_days);
$function$;

COMMENT ON COLUMN core.users."password" IS 'Hash BCrypt da senha. Nunca a senha.';
COMMENT ON COLUMN core.users.password_changed_at IS 'Última troca de senha. Tokens de acesso emitidos antes disso são recusados.';
COMMENT ON COLUMN core.users.updated_at IS 'Data e hora da última alteração deste usuário. Atualizada automaticamente.';
COMMENT ON COLUMN core.users.email_verified_at IS 'Data e hora em que o e-mail foi confirmado. Vazio enquanto não houver confirmação.';

COMMENT ON TABLE core.refresh_token IS 'Sessões renováveis do usuário. Cada linha é um refresh token. O valor secreto não fica aqui, só o hash.';

COMMENT ON COLUMN core.refresh_token.refresh_token_id IS 'Número que identifica o refresh token. Gerado automaticamente.';
COMMENT ON COLUMN core.refresh_token.users_id IS 'Usuário dono desta sessão.';
COMMENT ON COLUMN core.refresh_token.token_hash IS 'Hash SHA-256 do refresh token, em 64 caracteres hexadecimais. Nunca o token em si.';
COMMENT ON COLUMN core.refresh_token.family_id IS 'Família da sessão. Todos os tokens nascidos do mesmo login compartilham este identificador.';
COMMENT ON COLUMN core.refresh_token.device_label IS 'Nome opcional do aparelho ou navegador em que a sessão foi aberta.';
COMMENT ON COLUMN core.refresh_token.created_at IS 'Data e hora em que este refresh token foi criado.';
COMMENT ON COLUMN core.refresh_token.last_used_at IS 'Data e hora do último uso deste refresh token. Vazio se ele ainda não foi usado.';
COMMENT ON COLUMN core.refresh_token.expires_at IS 'Data e hora em que este refresh token deixa de valer.';
COMMENT ON COLUMN core.refresh_token.revoked_at IS 'Data e hora em que este refresh token foi revogado. Vazio enquanto ele puder ser usado.';
COMMENT ON COLUMN core.refresh_token.replaced_by_id IS 'Refresh token emitido no lugar deste, quando ele foi renovado. Vazio se não houve substituição.';

COMMENT ON TABLE core.password_reset_code IS 'Códigos de recuperação de senha. Cada linha é um código enviado por e-mail. O código não fica aqui, só o hash.';

COMMENT ON COLUMN core.password_reset_code.password_reset_code_id IS 'Número que identifica o código. Gerado automaticamente.';
COMMENT ON COLUMN core.password_reset_code.users_id IS 'Usuário que pediu a recuperação de senha.';
COMMENT ON COLUMN core.password_reset_code.code_hash IS 'Hash BCrypt do código de 6 dígitos. Nunca o código em si.';
COMMENT ON COLUMN core.password_reset_code.created_at IS 'Data e hora em que este código foi criado.';
COMMENT ON COLUMN core.password_reset_code.expires_at IS 'Data e hora em que este código deixa de valer.';
COMMENT ON COLUMN core.password_reset_code.attempts IS 'Quantas vezes um código errado já foi tentado nesta linha. No máximo 5.';
COMMENT ON COLUMN core.password_reset_code.used_at IS 'Data e hora em que este código foi usado para trocar a senha. Vazio se ainda não foi usado.';
COMMENT ON COLUMN core.password_reset_code.revoked_at IS 'Data e hora em que este código foi cancelado, em geral porque outro foi emitido. Vazio enquanto ele puder ser usado.';