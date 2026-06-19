# telegram-notifier Dockerfile
# Multi-stage build for Java 21 + Python 3 Telegram notification service

# ============================================================
# Stage 1: Maven Build
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
COPY telegram-tdlight-classifier-all/pom.xml telegram-tdlight-classifier-all/
COPY telegram-spring-boot-starter/pom.xml telegram-spring-boot-starter/
COPY telegram-python-subprocess-runtime/pom.xml telegram-python-subprocess-runtime/
COPY telegram-notifier-control-server/pom.xml telegram-notifier-control-server/
COPY telegram-notifier-control-web/pom.xml telegram-notifier-control-web/

# Download dependencies first (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY telegram-tdlight-classifier-all telegram-tdlight-classifier-all
COPY telegram-spring-boot-starter telegram-spring-boot-starter
COPY telegram-python-subprocess-runtime telegram-python-subprocess-runtime
COPY telegram-notifier-control-server telegram-notifier-control-server
COPY telegram-notifier-control-web telegram-notifier-control-web

# Build application (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="Kael <kael@example.com>"
LABEL description="Telegram Notifier - Self-hosted Telegram notification service"

# Install Python 3, pip, and curl (for health check)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
        curl \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY telegram-python-subprocess-runtime/src/main/resources/telegram-python-worker /opt/telegram-worker
WORKDIR /opt/telegram-worker
RUN pip3 install --no-cache-dir --break-system-packages pyrogram

# Copy Spring Boot application
COPY --from=builder /app/telegram-notifier-control-server/target/telegram-notifier-control-server-*.jar /app/app.jar

# Create data directory
RUN mkdir -p /telegram-notifier/data

# Set working directory
WORKDIR /app

# Environment variables with defaults
ENV TELEGRAM_NOTIFIER_DATA_DIR=/telegram-notifier/data
ENV TELEGRAM_PYTHON_EXECUTABLE=python3
ENV JAVA_OPTS=""

# Expose HTTP port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/api/system/bootstrap-status || exit 1

# Volume for persistent data (SQLite database, Telegram sessions)
VOLUME /telegram-notifier/data

# Run application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
