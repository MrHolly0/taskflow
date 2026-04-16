# TaskFlow

Voice and text to structured task list, powered by LLM.

## Requirements

- Java 21
- Docker + Docker Compose

## Quick start

```bash
cp .env.example .env
docker compose -f infra/docker-compose.yml up -d
./gradlew :core-service:app:bootRun
```

Health check:

```bash
curl http://localhost:8080/actuator/health
```

## Structure

```
core-service/    Spring Boot core (multi-module)
nlp-worker/      LLM text/voice processing
notification-worker/  Scheduled reminders
miniapp/         Telegram Mini App (React + Vite)
website/         Next.js site
infra/           docker-compose, nginx
```

## Development

```bash
# Start infra only
docker compose -f infra/docker-compose.yml up -d postgres redis

# Run core service
./gradlew :core-service:app:bootRun

# pgAdmin at http://localhost:5050
```
