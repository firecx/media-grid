-- Производные файлы, которые делает служба обработки: миниатюра, превью, версия для воспроизведения.
-- Удаляются вместе с исходным файлом.

CREATE TABLE file_variants (
    media_id      UUID         NOT NULL REFERENCES stored_files (media_id) ON DELETE CASCADE,
    kind          VARCHAR(20)  NOT NULL CHECK (kind IN ('THUMBNAIL', 'PREVIEW', 'PLAYBACK')),
    content_type  VARCHAR(127) NOT NULL,
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes >= 0),
    storage_key   VARCHAR(500) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (media_id, kind)
);
