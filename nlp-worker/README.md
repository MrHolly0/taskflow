# nlp-worker

Отдельный Spring Boot сервис для обработки естественного языка. Обрабатывает текст и голос, парсит через LLM (Groq или YandexGPT), возвращает структурированные данные задачи.

## Что делает

- **Парсинг текста**: Превращает "купить молоко завтра в 18:00" в структурированную задачу с заголовком, дедлайном, приоритетом
- **Распознавание речи**: Получает файлы `.ogg`, преобразует в текст через Groq Whisper, затем парсит как текст
- **Маршрутизация LLM**: Основной: Groq API; fallback: YandexGPT
- **Кеширование**: Ответы в Redis (TTL 24ч) для избежания дублирующихся LLM запросов

## Быстрый старт

Требует: Java 21, Redis 7, API ключ Groq

```bash
# Запустить Redis
docker compose -f infra/docker-compose.yml up -d redis

# Установить env
export GROQ_API_KEY=ваш_ключ_здесь
export GROQ_WHISPER_API_KEY=ваш_ключ_здесь

# Запустить
./gradlew :nlp-worker:bootRun
```

## Эндпоинты

**POST /nlp/parse-text**
```json
{
  "text": "купить молоко завтра в 18:00",
  "userId": "uuid"
}
```

Ответ:
```json
{
  "tasks": [
    {
      "title": "Купить молоко",
      "deadline": "2026-04-27T18:00:00Z",
      "priority": "HIGH",
      "groupId": "покупки"
    }
  ]
}
```

**POST /nlp/parse-voice**
```
multipart/form-data:
  audio: file.ogg
  userId: uuid
```

## Конфигурация

Переменные окружения:
- `NLP_PORT=8081` — Порт сервиса
- `REDIS_HOST=localhost` — Хост Redis
- `REDIS_PORT=6379` — Порт Redis
- `GROQ_API_KEY` — API ключ Groq (обязательный)
- `GROQ_WHISPER_API_KEY` — Groq Whisper для голоса
- `YANDEX_GPT_API_KEY` — YandexGPT fallback (опционально)
- `YANDEX_GPT_FOLDER_ID` — ID папки YandexGPT

## LLM Промпт

Системный промпт инструктирует модель:
- Извлечение заголовка, дедлайна, приоритета
- Автоматический выбор группы из контекста (работа, дом, покупки и т.д.)
- Установка приоритета из контекстных подсказок
- Оценка длительности выполнения

Поддерживаемые поля вывода: `title`, `description`, `deadline`, `priority`, `estimateMinutes`, `groupId`

## Сборка

```bash
./gradlew :nlp-worker:build
```

Docker образ:
```bash
docker build -f nlp-worker/Dockerfile -t taskflow-nlp-worker:latest .
```
