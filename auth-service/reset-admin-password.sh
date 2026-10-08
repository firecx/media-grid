#!/bin/sh
# Восстановление пароля администратора (см. AdminPasswordReset):
#   docker compose exec auth-service reset-admin-password <почта>
# Запускает отдельную программу из того же архива службы, не саму службу: малая куча, без оптимизирующего
# компилятора — работает около секунды и не мешает работающей службе.
exec java -Xmx64m -XX:TieredStopAtLevel=1 -cp /app/app.jar \
    -Dloader.main=io.mediagrid.auth.user.AdminPasswordReset \
    org.springframework.boot.loader.launch.PropertiesLauncher "$@"
