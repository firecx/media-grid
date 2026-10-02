-- Схема auth: пользователи, обновляемые токены, ключи подписи токенов доступа.

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(320) NOT NULL UNIQUE,  -- хранится в нижнем регистре
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    enabled       BOOLEAN      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

-- Обновляемые токены. Сам токен не хранится, только его отпечаток SHA-256.
-- family_id объединяет цепочку токенов одного входа: при повторном предъявлении
-- уже заменённого токена отзывается вся цепочка.
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    family_id   UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_expires_idx ON refresh_tokens (expires_at);

-- Ключи RSA для подписи токенов доступа. Открытая часть публикуется через /api/auth/jwks.
CREATE TABLE signing_keys (
    kid         VARCHAR(64) PRIMARY KEY,
    private_key TEXT        NOT NULL,  -- PKCS#8, Base64
    public_key  TEXT        NOT NULL,  -- X.509, Base64
    active      BOOLEAN     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
