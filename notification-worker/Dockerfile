FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY . .

# Кэш /root/.gradle общий с остальными JVM-образами — пояснение в core-service/Dockerfile.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && \
    ./gradlew :notification-worker:bootJar -x test --no-daemon --max-workers=2

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/notification-worker/build/libs/*.jar notification-worker.jar

ENTRYPOINT ["java", "-jar", "notification-worker.jar"]
