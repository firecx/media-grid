#!/bin/sh
# Проверка готовности для docker compose: служба «нездорова», если последняя копия не удалась
# или успешной копии нет дольше BACKUP_MAX_AGE_HOURS (по умолчанию 26 ч — сутки с запасом).
# Пока копий ещё не было (только что установлено), служба считается здоровой.
STATE=/var/lib/mediagrid-backup
now=$(date +%s)

if [ -f "$STATE/last-failure" ]; then
    echo "последняя копия не удалась: $(cat "$STATE/last-error" 2>/dev/null)"
    exit 1
fi
if [ -f "$STATE/last-success" ]; then
    age=$(( (now - $(cat "$STATE/last-success")) / 3600 ))
    if [ "$age" -ge "${BACKUP_MAX_AGE_HOURS:-26}" ]; then
        echo "последней успешной копии $age ч"
        exit 1
    fi
fi
exit 0
