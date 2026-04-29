# infra

Docker Compose конфигурации для разработки и production. Включают PostgreSQL, Redis, pgAdmin и все сервисы TaskFlow.

## Окружение разработки

```bash
# Запустить все сервисы (dev)
docker compose -f docker-compose.yml up -d

# Просмотр логов
docker compose -f docker-compose.yml logs -f [имя-сервиса]

# Остановить
docker compose -f docker-compose.yml down

# Очистить всё
docker compose -f docker-compose.yml down -v
```

Сервисы (dev):
- **postgres:16** — Порт 5432, БД `taskflow`
- **redis:7** — Порт 6379
- **pgAdmin** — Порт 5050, администратор PostgreSQL
- **core-service** — Порт 8080, основной Spring Boot app
- **nlp-worker** — Порт 8081, NLP сервис
- **notification-worker** — Порт 8082, напоминания
- **miniapp** — Порт 3000, React фронтенд
- **nginx** — Порт 80/443, reverse proxy

## Production окружение

```bash
# Запустить с prod настройками
docker compose -f docker-compose.prod.yml up -d --build

# Просмотр логов
docker compose -f docker-compose.prod.yml logs -f

# Остановить
docker compose -f docker-compose.prod.yml down
```

Отличия от dev:
- Health checks включены
- Restart policies: `on-failure`
- Нет pgAdmin (безопасность)
- TLS/HTTPS через nginx + Let's Encrypt

## Переменные окружения

Создайте файл `.env` в корне проекта (см. `.env.example`):

```bash
# БД
DB_HOST=postgres
DB_PORT=5432
DB_NAME=taskflow
DB_USER=taskflow
DB_PASSWORD=ваш_безопасный_пароль

# Redis
REDIS_HOST=redis
REDIS_PORT=6379

# Telegram
TELEGRAM_BOT_TOKEN=ваш_токен_бота
TELEGRAM_WEBHOOK_SECRET=ваш_секрет_webhook

# LLM
GROQ_API_KEY=ваш_groq_ключ
GROQ_WHISPER_API_KEY=ваш_groq_whisper_ключ
YANDEX_GPT_API_KEY=ваш_yandex_ключ
YANDEX_GPT_FOLDER_ID=ваш_folder_id

# Опционально
ENVIRONMENT=production
APP_NOTIFICATION_POLL_INTERVAL_MS=30000
```

## nginx конфигурация

Reverse proxy маршруты:
- `/` → miniapp (React фронтенд)
- `/api/` → core-service:8080
- `/telegram/webhook` → core-service:8080
- `/swagger-ui.html` → core-service:8080

Для HTTPS на production:
```bash
# Генерировать сертификат
docker compose -f docker-compose.prod.yml exec nginx certbot certonly \
  --webroot -w /var/www/certbot \
  -d yourdomain.com

# Авто-обновление через cron (еженедельно)
0 3 * * 0 docker compose -f docker-compose.prod.yml exec -T nginx certbot renew
```

## Постоянные тома

- `postgres_data/` — Файлы БД PostgreSQL
- `redis_data/` — Снимки Redis
- `taskflow_logs/` — Логи приложения (только prod)
- `nginx_conf/` — Конфигурация nginx
- `certbot_data/` — Сертификаты Let's Encrypt

## Health checks

Все сервисы имеют health endpoints:
```bash
curl http://localhost:8080/actuator/health   # core-service
curl http://localhost:8081/health             # nlp-worker
curl http://localhost:8082/health             # notification-worker
```

## Стратегия резервного копирования (production)

Ежедневное копирование PostgreSQL (в 2 часа ночи):
```bash
docker compose -f docker-compose.prod.yml exec postgres \
  pg_dump -U taskflow taskflow > backup_$(date +%Y%m%d).sql
```

Восстановление:
```bash
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U taskflow taskflow < backup_20260426.sql
```

## Решение проблем

**Порт уже занят**:
```bash
# Изменить порт в docker-compose.yml или использовать
docker compose -f docker-compose.yml up -d -p "8080:8080"
```

**Ошибка подключения к БД**:
```bash
# Посмотреть логи postgres
docker compose -f docker-compose.yml logs postgres

# Проверить через psql
docker compose -f docker-compose.yml exec postgres \
  psql -U taskflow -d taskflow -c "SELECT 1"
```

**Сервис медленный/зависает**:
```bash
# Перезагрузить один сервис
docker compose -f docker-compose.yml restart core-service

# Полная пересборка
docker compose -f docker-compose.yml up -d --build
```
