FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY . .

RUN chmod +x gradlew && \
    ./gradlew :nlp-worker:bootJar -x test

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/nlp-worker/build/libs/*.jar nlp-worker.jar

ENTRYPOINT ["java", "-jar", "nlp-worker.jar"]
