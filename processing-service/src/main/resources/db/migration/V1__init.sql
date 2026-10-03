-- Схема processing: очередь задач обработки и метаданные обработки (ТЗ, п. 4.3.1).
-- media_id — идентификатор записи в службе медиаданных (внешнего ключа нет: другая служба).

CREATE TABLE jobs (
    media_id        UUID          PRIMARY KEY,
    owner_id        UUID          NOT NULL,
    content_type    VARCHAR(127)  NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    -- Что делается сейчас и насколько продвинулось: для отображения хода обработки (ТЗ, п. 4.1.6)
    stage           VARCHAR(20)   CHECK (stage IN ('DOWNLOADING', 'ANALYZING', 'PREVIEW', 'TRANSCODING', 'SAVING')),
    progress        INT           NOT NULL CHECK (progress BETWEEN 0 AND 100),
    attempts        INT           NOT NULL CHECK (attempts >= 0),
    -- Раньше этого времени задача не берётся: пауза перед повтором после временного сбоя
    next_attempt_at TIMESTAMPTZ   NOT NULL,
    error           VARCHAR(1000),
    -- Метаданные обработки
    duration_ms     BIGINT,
    width           INT,
    height          INT,
    video_codec     VARCHAR(50),
    audio_codec     VARCHAR(50),
    transcoded      BOOLEAN       NOT NULL,
    has_preview     BOOLEAN       NOT NULL,
    -- Отправлено ли processing.completed; неотправленное досылается по расписанию
    notified        BOOLEAN       NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    -- Исполнитель отмечается, пока работает; задачу без отметки дольше срока возвращают в очередь
    heartbeat_at    TIMESTAMPTZ
);

CREATE INDEX jobs_queue ON jobs (next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX jobs_running ON jobs (heartbeat_at) WHERE status = 'RUNNING';
CREATE INDEX jobs_unnotified ON jobs (finished_at) WHERE NOT notified AND status IN ('DONE', 'FAILED');
CREATE INDEX jobs_status ON jobs (status, created_at);
