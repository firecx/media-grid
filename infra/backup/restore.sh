#!/bin/sh
# Восстановление из резервной копии: база данных и файловое хранилище из одного снимка.
#   docker compose exec backup restic snapshots          — список снимков
#   docker compose exec backup mediagrid-restore latest  — восстановить последний (или номер снимка)
#
# Перед восстановлением службы, работающие с базой и файлами, должны быть остановлены:
#   docker compose stop gateway auth-service media-service storage-service processing-service
# Текущие данные заменяются данными снимка: всё, что появилось после снимка, теряется.
set -eu

SNAPSHOT="${1:-}"
SCHEMAS="${BACKUP_DB_SCHEMAS:-auth media storage processing}"
WORK=/restore

log() { echo "$(date '+%F %T') mediagrid-restore: $*"; }
fail() { log "ОШИБКА: $*"; rm -rf "$WORK"; exit 1; }

if [ -z "$SNAPSHOT" ]; then
    echo "Использование: mediagrid-restore <снимок | latest>"
    echo "Снимки: restic snapshots"
    exit 2
fi

# Пока службы подключены к базе, восстановление испортило бы данные на ходу
others=$(psql -tAc "select count(*) from pg_stat_activity where datname = current_database() and pid <> pg_backend_pid()") \
    || fail "нет связи с базой данных"
if [ "$others" != 0 ]; then
    fail "к базе подключено $others соединений: сначала остановите службы
    docker compose stop gateway auth-service media-service storage-service processing-service"
fi

log "снимок $SNAPSHOT"
restic snapshots --host mediagrid "$SNAPSHOT" || fail "снимок $SNAPSHOT не найден"

rm -rf "$WORK" && mkdir -p "$WORK"
restic restore "$SNAPSHOT" --host mediagrid --target "$WORK" --include /dump \
    || fail "не удалось извлечь копию базы"
[ -s "$WORK/dump/mediagrid.dump" ] || fail "в снимке нет копии базы"

log "база: замена схем $SCHEMAS"
drop=""
for schema in $SCHEMAS; do
    drop="$drop drop schema if exists $schema cascade;"
done
psql -v ON_ERROR_STOP=1 -qc "$drop" || fail "не удалось очистить базу"
pg_restore --no-owner --exit-on-error --dbname="$PGDATABASE" "$WORK/dump/mediagrid.dump" \
    || fail "не удалось восстановить базу (pg_restore)"
rm -rf "$WORK"

# Содержимое хранилища заменяется целиком (сам каталог — точка подключения тома, его не удалить)
log "файлы: замена содержимого /data/storage"
find /data/storage -mindepth 1 -delete || fail "не удалось очистить файловое хранилище"
restic restore "$SNAPSHOT" --host mediagrid --target / --include /data/storage \
    || fail "не удалось восстановить файлы"

log "готово: $(find /data/storage -type f | wc -l) файлов. Запустите службы: docker compose up -d"
