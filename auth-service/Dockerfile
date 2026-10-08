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
# Служба работает от своего пользователя, а не от root (номер постоянный: им же помечены файлы в томах)
RUN addgroup -S -g 10001 mediagrid && adduser -S -D -H -u 10001 -G mediagrid mediagrid
WORKDIR /app
COPY --from=build /src/auth-service/target/auth-service.jar app.jar
# Восстановление пароля администратора: docker compose exec auth-service reset-admin-password <почта>
COPY --chmod=755 auth-service/reset-admin-password.sh /usr/local/bin/reset-admin-password
EXPOSE 8100
USER mediagrid
ENTRYPOINT ["java", "-jar", "app.jar"]
