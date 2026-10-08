# Служба резервного копирования (ТЗ, пп. 4.1.4, 7): по расписанию копия базы (pg_dump) и файлового
# хранилища одним снимком restic. Основа — тот же образ PostgreSQL, что у базы: pg_dump и pg_restore
# точно той же версии, что сервер, а слои образа на диске общие с ним.
FROM postgres:18.6-alpine3.24

# restic — копии с дедупликацией и шифрованием; tzdata — расписание по местному времени (BACKUP_TZ);
# openssh-client — хранилище копий на другом сервере по SFTP
RUN apk add --no-cache restic tzdata openssh-client

COPY --chmod=755 entrypoint.sh /usr/local/bin/mediagrid-backup-start
COPY --chmod=755 backup.sh /usr/local/bin/mediagrid-backup
COPY --chmod=755 restore.sh /usr/local/bin/mediagrid-restore
COPY --chmod=755 health.sh /usr/local/bin/mediagrid-backup-health

# Образ PostgreSQL останавливает базу сигналом SIGINT; здесь не база, а расписание — обычный SIGTERM
STOPSIGNAL SIGTERM

ENTRYPOINT ["mediagrid-backup-start"]
