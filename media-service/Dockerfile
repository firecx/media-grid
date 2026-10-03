# Контекст сборки — корень репозитория (docker-compose.yaml: context: .)
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /src
# Копируется весь репозиторий: Maven нужны все модули из корневого pom.xml.
# Лишнее отсекает .dockerignore.
COPY . .
# Зависимости Maven сохраняются между сборками в кэше
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl media-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/media-service/target/media-service.jar app.jar
EXPOSE 8200
ENTRYPOINT ["java", "-jar", "app.jar"]
