# Multi-stage build. Build context is the repo root (see docker-compose.yml) because this is
# a Maven multi-module reactor — building execution-settlement alone requires the parent pom and the
# 'common' module's sources to be visible to the build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common ./common
COPY execution-settlement ./execution-settlement
RUN mvn -pl execution-settlement -am -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/execution-settlement/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
