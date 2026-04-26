# notification-worker

Отдельный Spring Boot сервис для расписания и отправки напоминаний о задачах. Опрашивает таблицу `scheduled_notifications`, отправляет сообщения в Telegram, отслеживает попытки повтора.

## Что делает

- **Опрос**: Каждые 30 секунд проверяет напоминания с `fire_at <= NOW()` и `sent = false`
- **Блокировка**: Использует `FOR UPDATE SKIP LOCKED` для избежания конфликтов в распределённых системах
- **Отправка**: Вызывает Telegram Bot API для отправки отформатированных сообщений
- **Повторы**: Переотправляет неудачные попытки до 3 раз, затем отмечает как отправленное
- **Асинхронно**: Неблокирующее, не замедляет core-service

## Быстрый старт

Требует: Java 21, PostgreSQL 16, Redis 7, токен Telegram бота

```bash
# Запустить инфраструктуру
docker compose -f infra/docker-compose.yml up -d postgres redis

# Установить env
export TELEGRAM_BOT_TOKEN=ваш_токен_бота_здесь

# Запустить
./gradlew :notification-worker:bootRun
```

## Конфигурация

Переменные окружения:
- `NOTIFICATION_PORT=8082` — Порт сервиса
- `DB_HOST=localhost` — Хост PostgreSQL
- `DB_PORT=5432` — Порт PostgreSQL
- `DB_NAME=taskflow` — Имя БД
- `DB_USER=taskflow` — Пользователь БД
- `DB_PASSWORD=taskflow` — Пароль БД
- `TELEGRAM_BOT_TOKEN` — Токен бота Telegram (обязательный)
- `APP_NOTIFICATION_POLL_INTERVAL_MS=30000` — Интервал опроса (по умолчанию 30s)
- `APP_NOTIFICATION_BATCH_SIZE=50` — Макс. уведомлений за раз
- `APP_NOTIFICATION_MAX_RETRIES=3` — Макс. попыток повтора

## Как это работает

1. **Scheduled задача** запускается каждые `poll-interval-ms`:
   ```sql
   SELECT id, telegram_chat_id, payload_type, payload
   FROM scheduled_notifications
   WHERE fire_at <= NOW() AND sent = false AND retry_count < 3
   ORDER BY fire_at
   LIMIT 50
   FOR UPDATE SKIP LOCKED
   ```

2. **Отправить сообщение** в Telegram:
   ```
   POST https://api.telegram.org/bot{TOKEN}/sendMessage
   chat_id: {chat_id}
   text: "📌 Напоминание: {taskTitle}\nДедлайн: {deadline}"
   parse_mode: HTML
   ```

3. **Обновить статус**:
   - При успехе: `UPDATE scheduled_notifications SET sent = true, sent_at = NOW() WHERE id = ?`
   - При ошибке: `UPDATE scheduled_notifications SET retry_count = retry_count + 1 WHERE id = ?`

## Схема БД

Таблица: `scheduled_notifications`
```
id             UUID PK
task_id        UUID FK
user_id        UUID FK
telegram_chat_id BIGINT
fire_at        TIMESTAMP
payload_type   VARCHAR(32) — "TASK_REMINDER"
payload        JSONB — {"taskTitle": "...", "deadline": "..."}
sent           BOOLEAN
sent_at        TIMESTAMP
retry_count    INT
created_at     TIMESTAMP
```

## Сборка

```bash
./gradlew :notification-worker:build
```

Docker:
```bash
docker build -f notification-worker/Dockerfile -t taskflow-notification-worker:latest .
```

## Мониторинг

Проверка здоровья:
```bash
curl http://localhost:8082/actuator/health
```

Метрики доступны на `/actuator/prometheus`
