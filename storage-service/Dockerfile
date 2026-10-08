# Контекст сборки — корень репозитория (docker-compose.yaml: context: .)
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /src
# Копируется весь репозиторий: Maven нужны все модули из корневого pom.xml.
# Лишнее отсекает .dockerignore.
COPY . .
# Зависимости Maven сохраняются между сборками в кэше
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl storage-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# Служба работает от своего пользователя, а не от root (номер постоянный: им же помечены файлы в томах)
# su-exec — сброс прав root после подготовки тома (run-as-mediagrid.sh)
RUN apk add --no-cache su-exec \
    && addgroup -S -g 10001 mediagrid && adduser -S -D -H -u 10001 -G mediagrid mediagrid \
    && mkdir -p /data/storage && chown mediagrid:mediagrid /data/storage
ENV MEDIAGRID_DATA_DIR=/data/storage
COPY --chmod=755 infra/docker/run-as-mediagrid.sh /usr/local/bin/run-as-mediagrid
WORKDIR /app
COPY --from=build /src/storage-service/target/storage-service.jar app.jar
EXPOSE 8300
ENTRYPOINT ["run-as-mediagrid", "java", "-jar", "app.jar"]
