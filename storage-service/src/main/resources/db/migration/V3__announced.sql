-- Когда шина подтвердила приём события file.uploaded (0.14.0). Пусто у полученного файла — каталог
-- о нём ещё не знает: событие досылается по расписанию. Иначе запись каталога так и осталась бы
-- «ожидающей загрузки» и через сутки была бы удалена вместе с полученным файлом.
ALTER TABLE stored_files ADD COLUMN announced_at TIMESTAMPTZ;

-- Файлы, полученные до 0.14.0, считаются сообщёнными: повторные события им не нужны
UPDATE stored_files SET announced_at = completed_at WHERE status = 'COMPLETE';

CREATE INDEX stored_files_unannounced_idx ON stored_files (completed_at)
    WHERE status = 'COMPLETE' AND announced_at IS NULL;
