FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY . .

RUN chmod +x gradlew && \
    ./gradlew :notification-worker:bootJar -x test

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/notification-worker/build/libs/*.jar notification-worker.jar

ENTRYPOINT ["java", "-jar", "notification-worker.jar"]
