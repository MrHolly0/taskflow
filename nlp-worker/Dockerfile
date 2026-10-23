FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY . .

# Кэш /root/.gradle общий с остальными JVM-образами — пояснение в core-service/Dockerfile.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && \
    ./gradlew :nlp-worker:bootJar -x test --no-daemon --max-workers=2

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/nlp-worker/build/libs/*.jar nlp-worker.jar

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 CMD wget -q --spider http://127.0.0.1:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "nlp-worker.jar"]
