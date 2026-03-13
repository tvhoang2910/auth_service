# syntax=docker/dockerfile:1.7

# ===== STAGE 1: BUILD (CACHE-FRIENDLY) =====
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /app

# 1) Copy pom and prefetch dependencies
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# 2) Copy source and package
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests -Dspring-boot.build-image.skip=true -B

# ===== STAGE 2: TOOLING FOR HEALTHCHECK =====
FROM busybox:1.36.1-musl AS healthtools

# ===== STAGE 3: RUNTIME (DISTROLESS NONROOT) =====
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /

# 3) Copy app and healthcheck helper binary
COPY --from=builder /app/target/*.jar /app.jar
COPY --from=healthtools /bin/busybox /busybox

# 4) OCI labels
LABEL org.opencontainers.image.title="auth-service"
LABEL org.opencontainers.image.description="Spring Boot Auth Service production image"
LABEL org.opencontainers.image.licenses="MIT"
LABEL org.opencontainers.image.version="1.0.0"

# 5) Expose port
EXPOSE 8080

# 6) Container healthcheck (Spring Actuator)
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD ["/busybox", "wget", "-qO-", "http://127.0.0.1:8080/api/v1/auth/actuator/health"]

# 7) Optimized JVM startup
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-XX:MaxRAMPercentage=75.0", "-XX:MinRAMPercentage=50.0", "-XX:+UseContainerSupport", "-XX:+AlwaysPreTouch", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app.jar"]
