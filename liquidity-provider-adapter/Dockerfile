# Multi-stage build. Build context is the repo root (see docker-compose.yml) because this is
# a Maven multi-module reactor — building liquidity-provider-adapter alone requires the parent pom and the
# 'common' module's sources to be visible to the build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common ./common
COPY liquidity-provider-adapter ./liquidity-provider-adapter
RUN mvn -pl liquidity-provider-adapter -am -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/liquidity-provider-adapter/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
