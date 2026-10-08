-- Отзыв токенов доступа (0.13.0). Токен доступа проверяется по подписи без обращения к службе
-- авторизации, поэтому отозванные токены перечисляются явно, пока они не истекут сами.

-- Поколение токенов пользователя: попадает в токен (утверждение ver). Смена пароля, отключение,
-- смена роли увеличивают его — токены прежних поколений больше не принимаются.
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

-- Отозванные токены доступа: либо одного входа (session_id — утверждение sid, цепочка обновляемых
-- токенов family_id), либо всех токенов пользователя поколения меньше min_version.
-- Запись нужна, пока могут действовать отозванные токены (expires_at), потом удаляется.
CREATE TABLE access_revocations (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL,  -- без внешнего ключа: запись переживает удаление пользователя
    session_id  UUID,
    min_version INTEGER,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CHECK ((session_id IS NULL) <> (min_version IS NULL))
);
CREATE INDEX access_revocations_user_idx ON access_revocations (user_id);
CREATE INDEX access_revocations_expires_idx ON access_revocations (expires_at);
