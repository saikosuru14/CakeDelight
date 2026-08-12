# Cake Delight - API Gateway (port 8080)
#
# Multi-stage build. Base image tags are pinned; `latest` is never used.
#
# No configuration and no credentials are baked into this image. Every downstream
# URI is supplied by the environment at run time. The container expects:
#   CATALOG_SERVICE_URL
#   ORDER_SERVICE_URL
#   RATING_SERVICE_URL
#   NOTIFICATION_SERVICE_URL

# ---------- build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM first so the dependency cache layer survives source-only changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests run in CI and locally; the routing tests need WireMock stubs and are not
# part of the image build.
RUN mvn -B -DskipTests package

# ---------- runtime stage ----------
FROM eclipse-temurin:21.0.4_7-jre-alpine
WORKDIR /app

# Unprivileged user: the JVM never runs as root.
RUN addgroup -S -g 10001 cakedelight \
 && adduser -S -u 10001 -G cakedelight -h /app -s /sbin/nologin cakedelight

COPY --from=build /build/target/api-gateway-1.0.0.jar app.jar
RUN chown cakedelight:cakedelight /app/app.jar

USER cakedelight
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
