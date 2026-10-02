# Контекст сборки — корень репозитория (docker-compose.yaml: context: .)
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /src
# Копируется весь репозиторий: Maven нужны все модули из корневого pom.xml.
# Лишнее отсекает .dockerignore.
COPY . .
# Зависимости Maven сохраняются между сборками в кэше
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl config-server -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/config-server/target/config-server.jar app.jar
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]
