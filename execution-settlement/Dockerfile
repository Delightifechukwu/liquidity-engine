# syntax=docker/dockerfile:1
# Multi-stage build. Build context is the repo root (see docker-compose.yml) because this is
# a Maven multi-module reactor — building execution-settlement alone requires the parent pom and the
# 'common' module's sources to be visible to the build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY order-service/pom.xml ./order-service/pom.xml
COPY matching-engine/pom.xml ./matching-engine/pom.xml
COPY market-data-service/pom.xml ./market-data-service/pom.xml
COPY liquidity-provider-adapter/pom.xml ./liquidity-provider-adapter/pom.xml
COPY risk-service/pom.xml ./risk-service/pom.xml
COPY reconciliation-job/pom.xml ./reconciliation-job/pom.xml
COPY common ./common
COPY execution-settlement ./execution-settlement
RUN --mount=type=cache,target=/root/.m2 mvn -pl execution-settlement -am -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/execution-settlement/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
