#!/bin/sh
# Запуск службы: проверить хранилище копий (создать, если его ещё нет) и ждать расписания.
set -eu

: "${RESTIC_PASSWORD:?Не задан BACKUP_PASSWORD — пароль хранилища резервных копий}"
: "${RESTIC_REPOSITORY:=/repo}"
: "${BACKUP_SCHEDULE:=0 3 * * *}"
export RESTIC_REPOSITORY

log() { echo "$(date '+%F %T') mediagrid-backup: $*"; }

mkdir -p /var/lib/mediagrid-backup

# Хранилище копий доступно и пароль подходит — или его ещё нет, и тогда оно создаётся.
# Неверный пароль или недоступное хранилище — ошибка запуска: лучше узнать сразу, чем в день аварии
if restic cat config > /dev/null 2>&1; then
    log "хранилище копий $RESTIC_REPOSITORY доступно"
elif restic init > /dev/null; then
    log "создано хранилище копий $RESTIC_REPOSITORY"
else
    log "ОШИБКА: хранилище копий $RESTIC_REPOSITORY недоступно или не подходит пароль BACKUP_PASSWORD"
    exit 1
fi

# Задачи по расписанию crond запускает без переменных среды контейнера — сохранить нужные
export -p | grep -E '^export (RESTIC_|PG|BACKUP_|AWS_|TZ=)' > /etc/mediagrid-backup.env
chmod 600 /etc/mediagrid-backup.env

echo "$BACKUP_SCHEDULE /usr/local/bin/mediagrid-backup > /proc/1/fd/1 2>&1" > /etc/crontabs/root
log "расписание: $BACKUP_SCHEDULE (часовой пояс ${TZ:-UTC})"

exec crond -f -l 8
