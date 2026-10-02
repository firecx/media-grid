# Контекст сборки — корень репозитория (docker-compose.yaml: context: .)
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY discovery/pom.xml discovery/pom.xml
RUN mvn -q -B -pl discovery -am dependency:go-offline
COPY common/src common/src
COPY discovery/src discovery/src
RUN mvn -q -B -pl discovery -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/discovery/target/discovery.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
