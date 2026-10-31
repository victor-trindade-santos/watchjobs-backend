-- V14: evolução dos avisos (F04): lembretes de entrevista, candidaturas paradas, vagas salvas e push no Android

-- 1. Novos tipos de aviso
ALTER TABLE core.notification DROP CONSTRAINT notification_type_check;
ALTER TABLE core.notification
    ADD CONSTRAINT notification_type_check CHECK (notification_type IN
        ('NEW_MATCHES', 'SEARCH_COMPLETED', 'SYSTEM',
         'INTERVIEW_REMINDER', 'STALE_APPLICATION', 'SAVED_JOB_ALERT'));

ALTER TABLE core.notification ADD COLUMN pushed_at timestamptz NULL;
COMMENT ON COLUMN core.notification.pushed_at IS 'Quando o aviso foi enviado por push (FCM). Vazio = ainda não enviado.';

-- Avisos ainda não enviados por push, para o envio no fim da rodada
CREATE INDEX idx_notification_push_pending ON core.notification (created_at) WHERE pushed_at IS NULL;

-- 2. Preferência de push
ALTER TABLE core.notification_preference ADD COLUMN push_enabled boolean NOT NULL DEFAULT true;

-- 3. Aparelhos Android que recebem push
CREATE TABLE core.push_device (
    push_device_id bigint GENERATED ALWAYS AS IDENTITY,
    users_id int4 NOT NULL,
    token varchar(512) NOT NULL,
    platform varchar(20) NOT NULL DEFAULT 'ANDROID',
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT push_device_pkey PRIMARY KEY (push_device_id),
    CONSTRAINT push_device_token_key UNIQUE (token),
    CONSTRAINT fk_push_device_users_id FOREIGN KEY (users_id)
        REFERENCES core.users (users_id) ON DELETE CASCADE,
    CONSTRAINT push_device_platform_check CHECK (platform IN ('ANDROID'))
);
CREATE INDEX idx_push_device_users ON core.push_device (users_id);

COMMENT ON TABLE core.push_device IS
    'Token FCM de cada aparelho. Um token pertence a um usuário só: ao logar com outra conta no mesmo aparelho, ele muda de dono.';
