# syntax=docker/dockerfile:1

# Both base images can be swapped for internal ones:
#   docker build --build-arg BUILD_IMAGE=my-registry/openjdk-maven:21 .
ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${BUILD_IMAGE} AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM ${RUNTIME_IMAGE}
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar

# OpenShift runs containers as an arbitrary UID that is always in group 0.
RUN chgrp 0 app.jar && chmod g=u app.jar
USER 10001

EXPOSE 8080 8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s \
    CMD wget -qO- http://127.0.0.1:8081/actuator/health/readiness | grep -q UP

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
