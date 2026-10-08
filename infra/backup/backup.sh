#!/bin/sh
# Резервная копия: база данных и файловое хранилище — одним снимком restic, затем удаление
# устаревших снимков. Запускается по расписанию или вручную:
#   docker compose exec backup mediagrid-backup
#
# Порядок: сначала база, потом файлы. Файлы не меняются после записи (новая версия — новый ключ),
# поэтому всё, на что ссылается копия базы, уже лежит в хранилище к моменту копирования файлов.
set -eu

[ -f /etc/mediagrid-backup.env ] && . /etc/mediagrid-backup.env

STATE=/var/lib/mediagrid-backup
DUMP=/dump
HOST=mediagrid
SCHEMAS="${BACKUP_DB_SCHEMAS:-auth media storage processing}"

log() { echo "$(date '+%F %T') mediagrid-backup: $*"; }
fail() {
    log "ОШИБКА: $*"
    date +%s > "$STATE/last-failure"
    echo "$*" > "$STATE/last-error"
    rm -rf "$DUMP"
    exit 1
}

# Одна копия за раз: ручной запуск во время копии по расписанию просто выходит
exec 9> /tmp/mediagrid-backup.lock
if ! flock -n 9; then
    log "копия уже делается"
    exit 0
fi

started=$(date +%s)
log "начало"

rm -rf "$DUMP" && mkdir -p "$DUMP"
schema_args=""
for schema in $SCHEMAS; do
    schema_args="$schema_args --schema=$schema"
done
# Список схем намеренно без кавычек: разбивается на отдельные аргументы
pg_dump --format=custom --no-owner $schema_args --file="$DUMP/mediagrid.dump" \
    || fail "не удалось сделать копию базы (pg_dump)"
log "база: $(du -h "$DUMP/mediagrid.dump" | cut -f1)"

# --host: имя контейнера меняется при каждом пересоздании, а снимки должны считаться одной серией
restic backup --host "$HOST" --tag mediagrid "$DUMP" /data/storage \
    || fail "не удалось сохранить снимок (restic backup)"
rm -rf "$DUMP"

restic forget --host "$HOST" --tag mediagrid --prune \
    --keep-daily "${BACKUP_KEEP_DAILY:-7}" \
    --keep-weekly "${BACKUP_KEEP_WEEKLY:-4}" \
    --keep-monthly "${BACKUP_KEEP_MONTHLY:-6}" \
    || fail "не удалось удалить устаревшие снимки (restic forget)"

# Раз в неделю (в воскресенье) — проверка целостности хранилища копий
if [ "$(date +%u)" = 7 ]; then
    restic check || fail "проверка хранилища копий нашла ошибки (restic check)"
    log "проверка хранилища копий: ошибок нет"
fi

date +%s > "$STATE/last-success"
rm -f "$STATE/last-failure" "$STATE/last-error"
log "готово за $(( $(date +%s) - started )) с"
