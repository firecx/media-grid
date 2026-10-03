-- Схема media: каталог файлов, категории, теги.

CREATE TABLE categories (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX categories_name_uq ON categories (lower(name));

-- owner_id — идентификатор пользователя из службы авторизации. Внешнего ключа нет:
-- пользователи живут в схеме другой службы.
CREATE TABLE media_files (
    id                UUID          PRIMARY KEY,
    owner_id          UUID          NOT NULL,
    title             VARCHAR(255)  NOT NULL,
    original_filename VARCHAR(255)  NOT NULL,
    content_type      VARCHAR(127)  NOT NULL,
    media_kind        VARCHAR(10)   NOT NULL CHECK (media_kind IN ('IMAGE', 'VIDEO', 'AUDIO')),
    size_bytes        BIGINT        NOT NULL CHECK (size_bytes > 0),
    status            VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'READY', 'FAILED')),
    visibility        VARCHAR(10)   NOT NULL CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    category_id       UUID          REFERENCES categories (id) ON DELETE SET NULL,
    has_preview       BOOLEAN       NOT NULL,
    processing_error  VARCHAR(1000),
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    uploaded_at       TIMESTAMPTZ
);
CREATE INDEX media_files_owner_idx ON media_files (owner_id);
CREATE INDEX media_files_status_created_idx ON media_files (status, created_at);
CREATE INDEX media_files_category_idx ON media_files (category_id);
CREATE INDEX media_files_created_idx ON media_files (created_at);

-- Теги хранятся в нижнем регистре и создаются при первом использовании.
CREATE TABLE tags (
    id   UUID        PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE media_tags (
    media_id UUID NOT NULL REFERENCES media_files (id) ON DELETE CASCADE,
    tag_id   UUID NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (media_id, tag_id)
);
CREATE INDEX media_tags_tag_idx ON media_tags (tag_id);
