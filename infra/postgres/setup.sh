#!/bin/sh
# Учётные записи служб в PostgreSQL (ТЗ, п. 4.3.1: у каждой службы своя схема). Выполняется службой
# db-setup при каждом запуске системы, до прикладных служб; повторный запуск ничего не ломает.
#
# У каждой службы своя роль mediagrid_<схема>: владелец только своей схемы, без прав суперпользователя,
# чужие схемы ей не видны. Учётная запись администратора базы (POSTGRES_USER) остаётся только у самой
# базы, db-setup и службы резервного копирования.
#
# Владение существующими таблицами передаётся роли: так на отдельные учётные записи переходит база,
# созданная до версии 0.12.0, и база после восстановления из резервной копии.
set -eu

for schema in auth media storage processing; do
    variable="$(echo "$schema" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
    eval "password=\${$variable:-}"
    if [ "${#password}" -lt 16 ]; then
        echo "mediagrid-db-setup: ОШИБКА: $variable не задан или короче 16 символов" >&2
        exit 1
    fi
    # Пароль передаётся через переменную среды, а не в командной строке
    ROLE_PASSWORD="$password" psql -v ON_ERROR_STOP=1 -q -v schema="$schema" -v role="mediagrid_$schema" <<'SQL'
\getenv password ROLE_PASSWORD
SET client_min_messages = warning;
SELECT format('CREATE ROLE %I LOGIN', :'role')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'role') \gexec
ALTER ROLE :"role" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'password';
GRANT CONNECT, TEMPORARY ON DATABASE :"DBNAME" TO :"role";

CREATE SCHEMA IF NOT EXISTS :"schema";
ALTER SCHEMA :"schema" OWNER TO :"role";

-- Таблицы, представления и отдельные последовательности схемы — роли службы. Последовательности
-- столбцов (serial, identity) переходят вместе со своей таблицей
SELECT format('ALTER %s %I.%I OWNER TO %I',
              CASE c.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW'
                             WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END,
              n.nspname, c.relname, :'role')
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = :'schema'
  AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
  AND c.relowner <> (SELECT oid FROM pg_roles WHERE rolname = :'role')
  AND NOT (c.relkind = 'S' AND EXISTS (
      SELECT FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('a', 'i')))
\gexec
SQL
    echo "mediagrid-db-setup: роль mediagrid_$schema — схема $schema"
done

# Подключаться к базе могут только администратор и роли служб
psql -v ON_ERROR_STOP=1 -q -c "REVOKE ALL ON DATABASE \"$PGDATABASE\" FROM PUBLIC"
echo "mediagrid-db-setup: готово"
