# telegram-notifier Dockerfile
# Multi-stage build: Vue SPA → Maven Build → Runtime

# ============================================================
# Stage 1: Build Vue Frontend
# ============================================================
FROM node:20-slim AS frontend-builder

WORKDIR /app
COPY telegram-notifier-control-server/src/main/frontend/package*.json telegram-notifier-control-server/src/main/frontend/
RUN cd telegram-notifier-control-server/src/main/frontend && npm ci
COPY telegram-notifier-control-server/src/main/frontend telegram-notifier-control-server/src/main/frontend
RUN cd telegram-notifier-control-server/src/main/frontend && npm run build

# ============================================================
# Stage 2: Maven Build
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS backend-builder

WORKDIR /app
COPY pom.xml .
COPY telegram-tdlight-classifier-all/pom.xml telegram-tdlight-classifier-all/
COPY telegram-spring-boot-starter/pom.xml telegram-spring-boot-starter/
COPY telegram-python-subprocess-runtime/pom.xml telegram-python-subprocess-runtime/
COPY telegram-notifier-core/pom.xml telegram-notifier-core/
COPY telegram-notifier-control-server/pom.xml telegram-notifier-control-server/

# Download dependencies first (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY telegram-tdlight-classifier-all telegram-tdlight-classifier-all
COPY telegram-spring-boot-starter telegram-spring-boot-starter
COPY telegram-python-subprocess-runtime telegram-python-subprocess-runtime
COPY telegram-notifier-core telegram-notifier-core
COPY telegram-notifier-control-server telegram-notifier-control-server

# Copy frontend build output into control-server static resources
COPY --from=frontend-builder /app/telegram-notifier-control-server/src/main/resources/static telegram-notifier-control-server/src/main/resources/static

# Build application (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 3: Runtime
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
RUN pip3 install --no-cache-dir pyrogram tgcrypto

# Copy Spring Boot application (with frontend static resources included)
COPY --from=backend-builder /app/telegram-notifier-control-server/target/telegram-notifier-control-server-*.jar /app/app.jar

# Create data directory
RUN mkdir -p /telegram-notifier/data

# Set working directory
WORKDIR /app

# Environment variables with defaults
ENV TELEGRAM_NOTIFIER_DATA_DIR=/telegram-notifier/data
ENV TELEGRAM_PYTHON_EXECUTABLE=python3
ENV JAVA_OPTS=""

# Expose HTTP port
EXPOSE 21192

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:21192/api/system/bootstrap-status || exit 1

# Volume for persistent data (SQLite database, Telegram sessions)
VOLUME /telegram-notifier/data

# Run application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
