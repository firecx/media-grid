# Контекст сборки — корень репозитория (docker-compose.yaml: context: .)
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /src
# Копируется весь репозиторий: Maven нужны все модули из корневого pom.xml.
# Лишнее отсекает .dockerignore.
COPY . .
# Зависимости Maven сохраняются между сборками в кэше
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl auth-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/auth-service/target/auth-service.jar app.jar
# Восстановление пароля администратора: docker compose exec auth-service reset-admin-password <почта>
COPY --chmod=755 auth-service/reset-admin-password.sh /usr/local/bin/reset-admin-password
EXPOSE 8100
ENTRYPOINT ["java", "-jar", "app.jar"]
