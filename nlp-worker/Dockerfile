FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY nlp-worker ./nlp-worker

RUN chmod +x gradlew && \
    ./gradlew :nlp-worker:bootJar -x test

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=0 /app/nlp-worker/build/libs/*.jar nlp-worker.jar

ENTRYPOINT ["java", "-jar", "nlp-worker.jar"]
