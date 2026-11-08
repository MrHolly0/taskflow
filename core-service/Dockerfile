FROM node:22-alpine AS code-runtime
WORKDIR /runner
COPY code-runtime/package.json code-runtime/package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY code-runtime/runner.mjs ./

# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy entire project (Gradle multi-module requires all modules)
COPY . .

# Build the application.
# Кэш /root/.gradle общий для трёх JVM-образов: без него каждый заново качает
# дистрибутив Gradle (~130 МБ) и весь набор зависимостей, а docker compose build
# собирает их параллельно — три одновременные загрузки одного файла, и любой
# сетевой сбой роняет всю сборку. sharing=locked сериализует доступ к кэшу:
# первый образ наполняет его, остальные ждут и переиспользуют.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && \
    ./gradlew :core-service:app:bootJar -x test --no-daemon --max-workers=2

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# gzip — для распаковки базы geoip, которую сам entrypoint скачивает при
# старте, если её ещё нет (wget уже есть в busybox самого образа).
RUN apk add --no-cache gzip nodejs

# Copy JAR from builder
COPY --from=builder /app/core-service/app/build/libs/*.jar app.jar
COPY core-service/docker-entrypoint.sh /app/docker-entrypoint.sh
COPY --from=code-runtime /runner /app/code-runtime
RUN chmod +x /app/docker-entrypoint.sh

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q --spider http://127.0.0.1:8080/actuator/health || exit 1

# Run application
ENTRYPOINT ["/app/docker-entrypoint.sh"]
