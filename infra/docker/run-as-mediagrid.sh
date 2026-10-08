#!/bin/sh
# Запуск службы с томом данных (хранение, обработка) от пользователя mediagrid, а не от root.
# Каталог данных — MEDIAGRID_DATA_DIR. Тома, созданные до 0.14.0 или восстановленные из старой копии,
# принадлежат root: при запуске всё, что не принадлежит mediagrid, передаётся ему, затем права root
# сбрасываются (su-exec заменяет этот процесс службой — сигналы остановки доходят до неё напрямую).
set -e

if [ "$(id -u)" = 0 ]; then
    if [ -n "$(find "$MEDIAGRID_DATA_DIR" ! -user mediagrid -print -quit)" ]; then
        echo "Каталог $MEDIAGRID_DATA_DIR передаётся пользователю mediagrid"
        chown -R mediagrid:mediagrid "$MEDIAGRID_DATA_DIR"
    fi
    exec su-exec mediagrid "$@"
fi
exec "$@"
