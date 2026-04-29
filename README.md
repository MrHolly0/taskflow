# TaskFlow

Цифровой ассистент-планировщик для преобразования голоса и текста в структурированный список задач, на базе LLM (Groq API) и Telegram интеграции.

## Возможности

- **Голосовой ввод** через Telegram бота или веб-приложение
- **AI разбор** текста/голоса → автоматическое извлечение заголовка, дедлайна, приоритета
- **Automatic напоминания** об истекающих дедлайнах
- **Draft-flow** — создание с подтверждением перед сохранением
- **Модульная архитектура** на Spring Boot (user, task, nlp-gateway, notify, telegram, audit)
- **Telegram Mini App** для доступа внутри Telegram
- **Адаптивный веб-интерфейс** (мобилка, планшет, ПК)

## Быстрый старт

Требует: Java 21, Docker, Node.js 18+, pnpm

```bash
# 1. Запустить инфраструктуру
docker compose -f infra/docker-compose.yml up -d

# 2. Запустить backend (Spring Boot ядро)
./gradlew :core-service:app:bootRun

# 3. Запустить frontend (React Vite)
cd miniapp && pnpm install && pnpm dev

# 4. Запустить NLP сервис
./gradlew :nlp-worker:bootRun

# 5. Запустить сервис уведомлений
./gradlew :notification-worker:bootRun
```

Приложение доступно:
- Mini App: `http://localhost:3000`
- Core-service API: `http://localhost:8080` (Swagger: `/swagger-ui.html`)
- pgAdmin: `http://localhost:5050` (для просмотра БД)

## Структура проекта

```
taskflow/
├── core-service/           Spring Boot ядро (Java 21, многомодульное)
│   ├── app/                Main приложение + Liquibase миграции
│   ├── modules/
│   │   ├── user/           Авторизация (JWT, Telegram)
│   │   ├── task/           CRUD задач, focus mode, дайджест
│   │   ├── nlp-gateway/    LLM шлюз (Groq, YandexGPT)
│   │   ├── notify/         Расписание напоминаний
│   │   ├── integration-telegram/  Telegram бот webhook
│   │   └── audit/          Логирование событий
│   └── shared/             Security, JPA, exception handling
├── nlp-worker/             Standalone сервис обработки текста/голоса
├── notification-worker/    Standalone сервис отправки напоминаний
├── miniapp/                React 18 + Vite (Telegram Mini App + веб)
├── infra/                  Docker Compose, nginx, конфигурация
├── .env.example            Шаблон переменных окружения
└── DEPLOYMENT.md           Полный гайд по развёртыванию на VPS
```

## Стек технологий

**Backend:**
- Java 21, Spring Boot 3.3, Spring Security
- PostgreSQL 16, Redis 7, Liquibase (миграции)
- Groq LLM API (основной), YandexGPT (fallback)
- Telegram Bot API (webhook режим)

**Frontend:**
- React 18, Vite, TypeScript
- TanStack Query v5, Zustand
- Tailwind CSS, shadcn/ui, Tabler Icons
- @dnd-kit (drag-and-drop канбан)

**Deployment:**
- Docker (multi-stage builds)
- docker-compose (dev/prod)
- nginx (reverse proxy)
- Let's Encrypt (TLS/HTTPS)

## Авторизация

Единственный способ авторизации — **через Telegram**:
- Mini App: использует `initData` от Telegram WebApp
- Веб-сайт: использует Telegram Login Widget
- Telegram бот: использует HMAC-SHA256 валидацию webhook

JWT токены хранятся в `localStorage`, refresh токены в Redis.

## Основные API эндпоинты

```bash
# Авторизация
POST /api/v1/auth/telegram-miniapp   # Telegram Mini App (initData)
POST /api/v1/auth/telegram-login     # Telegram Login Widget (веб)
POST /api/v1/auth/dev-token          # Dev/demo токен

# Задачи
GET  /api/v1/tasks/focus             # 1-3 приоритетные задачи
GET  /api/v1/tasks/digest?date=...   # Дайджест на день
GET  /api/v1/tasks?size=100          # Полный список (Page<TaskResponse>)
POST /api/v1/tasks                   # Создать
PATCH /api/v1/tasks/{id}             # Обновить статус/приоритет/дедлайн
POST /api/v1/tasks/{id}/complete     # Отметить выполненной
POST /api/v1/tasks/parse-text        # NLP-разбор текста → массив задач
POST /api/v1/tasks/quick             # Быстрое создание без черновика
DELETE /api/v1/tasks/{id}            # Удалить

# Группы
GET  /api/v1/groups                  # Список групп
POST /api/v1/groups                  # Создать группу
DELETE /api/v1/groups/{id}           # Удалить группу

# Telegram webhook
POST /api/telegram/webhook           # Входящие сообщения от бота
```

## Конфигурация

Создайте файл `.env` (см. `.env.example`):

```bash
# БД
DB_HOST=localhost
DB_PORT=5432
DB_NAME=taskflow
DB_USER=taskflow
DB_PASSWORD=ваш_пароль

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# Telegram
TELEGRAM_BOT_TOKEN=ваш_токен_бота
TELEGRAM_BOT_SECRET=ваш_вебхук_секрет

# LLM
GROQ_API_KEY=ваш_groq_ключ
GROQ_WHISPER_API_KEY=ваш_groq_ключ
YANDEX_GPT_API_KEY=ваш_yandex_ключ (опционально)
```

## Запуск на VPS

```bash
# 1. Клонировать репо
git clone https://github.com/your/taskflow.git
cd taskflow

# 2. Установить .env
cp .env.example .env
# — отредактировать .env с реальными значениями

# 3. Запустить (production)
docker compose -f infra/docker-compose.prod.yml up -d --build

# 4. Сертификат Let's Encrypt
docker compose -f infra/docker-compose.prod.yml exec nginx certbot certonly \
  --webroot -w /var/www/certbot -d yourdomain.com
```

Подробные инструкции см. [DEPLOYMENT.md](DEPLOYMENT.md).

## Документация

- **[core-service/README.md](core-service/README.md)** — Архитектура, модули, запуск
- **[nlp-worker/README.md](nlp-worker/README.md)** — Парсинг текста/голоса
- **[notification-worker/README.md](notification-worker/README.md)** — Напоминания, polling
- **[infra/README.md](infra/README.md)** — Docker Compose, окружение
- **[miniapp/README.md](miniapp/README.md)** — Фронтенд, структура проекта
- **[DEPLOYMENT.md](DEPLOYMENT.md)** — Развёртывание на VPS (200+ строк)

## Тестирование

```bash
# Unit тесты
./gradlew :core-service:app:test

# Smoke-тест
curl http://localhost:8080/actuator/health

# Swagger API docs
open http://localhost:8080/swagger-ui.html
```

## Разработка

```bash
# Сборка всех модулей (без тестов)
./gradlew build -x test

# Clean
./gradlew clean

# Lint (если настроен)
cd miniapp && pnpm lint
```

## Проблемы и решения

**Порт уже занят:**
```bash
docker compose -f infra/docker-compose.yml down
# или измените PORTS в docker-compose.yml
```

**БД не подключается:**
```bash
docker compose -f infra/docker-compose.yml logs postgres
```

**Telegram webhook не работает:**
- Проверьте `TELEGRAM_WEBHOOK_SECRET` в `.env`
- URL должен быть `https://yourdomain.com/api/telegram/webhook`
- Сертификат должен быть валидным (Let's Encrypt)

## Лицензия

MIT (или другая по выбору)

## Контакты

Проект разработан как курсовая работа (две отдельные курсовые: по Java и по веб-технологиям).
