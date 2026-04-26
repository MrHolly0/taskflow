# core-service

Многомодульное Spring Boot приложение — ядро TaskFlow. Управляет пользователями, CRUD задач, интеграцией с LLM, уведомлениями, Telegram ботом и логированием событий.

## Структура

```
core-service/
├── app/                          Основное Spring Boot приложение
├── modules/
│   ├── user/{api,impl}          Авторизация, JWT, управление пользователями
│   ├── task/{api,impl}          CRUD задач, режим фокуса, дайджест
│   ├── nlp-gateway/{api,impl}   LLM шлюз (Groq, YandexGPT)
│   ├── notify/{api,impl}        Расписание уведомлений
│   ├── integration-telegram/impl Webhook интеграция Telegram бота
│   └── audit/{api,impl}         Логирование событий (таблица task_events)
└── shared/
    ├── common/                  Базовые исключения
    ├── security/                JWT, Spring Security, валидация Telegram
    └── persistence/             Конфигурация JPA/Hibernate
```

## Быстрый старт

Требования: Java 21, PostgreSQL 16, Redis 7

```bash
# Запустить инфраструктуру
docker compose -f infra/docker-compose.yml up -d postgres redis

# Запустить Spring Boot
./gradlew :core-service:app:bootRun

# Проверка здоровья
curl http://localhost:8080/actuator/health
```

## Основные эндпоинты

- `POST /api/v1/auth/telegram-miniapp` — Авторизация через Telegram Mini App
- `GET /api/v1/tasks/focus` — 1-3 приоритетных задачи на сейчас
- `GET /api/v1/tasks/digest?date=YYYY-MM-DD` — Дайджест задач на день
- `POST /api/v1/tasks` — Создать задачу (черновик)
- `POST /api/v1/tasks/{id}/confirm` — Подтвердить черновик
- `POST /api/telegram/webhook` — Webhook Telegram бота (secret в конфиге)

## Конфигурация

Переменные окружения (см. `.env.example`):
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` — PostgreSQL
- `REDIS_HOST`, `REDIS_PORT` — Redis для refresh токенов
- `TELEGRAM_BOT_TOKEN` — Токен бота Telegram
- `TELEGRAM_BOT_SECRET` — Secret для webhook
- `GROQ_API_KEY` — API ключ Groq
- `YANDEX_GPT_API_KEY` — YandexGPT fallback (опционально)
- `YANDEX_GPT_FOLDER_ID` — ID папки YandexGPT

## Сборка

```bash
# Собрать все модули
./gradlew :core-service:app:build

# Запустить тесты
./gradlew :core-service:app:test

# Очистить
./gradlew clean
```

## База данных

Liquibase миграции запускаются автоматически при старте. Схемы:
- `users`, `user_settings` — Аккаунты пользователей
- `groups`, `tags`, `tasks`, `task_tags` — Управление задачами
- `scheduled_notifications` — Напоминания
- `task_events` — Логирование событий

## Документация API

```bash
./gradlew :core-service:app:bootRun
# Swagger UI: http://localhost:8080/swagger-ui.html
# OpenAPI: http://localhost:8080/v3/api-docs
```
