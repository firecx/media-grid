-- Схема storage: где и в каком состоянии лежит файл каждой записи каталога.
-- media_id — идентификатор записи в службе медиаданных (внешнего ключа нет: другая служба).

CREATE TABLE stored_files (
    media_id          UUID         PRIMARY KEY,
    owner_id          UUID         NOT NULL,
    content_type      VARCHAR(127) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    expected_size     BIGINT       NOT NULL CHECK (expected_size > 0),
    received_size     BIGINT       NOT NULL CHECK (received_size >= 0),
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('UPLOADING', 'COMPLETE')),
    -- Способ хранения и ключ внутри него: при переходе на объектное хранилище меняются только они
    storage_backend   VARCHAR(30)  NOT NULL,
    storage_key       VARCHAR(500) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    completed_at      TIMESTAMPTZ
);
