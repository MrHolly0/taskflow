# miniapp

Адаптивное веб-приложение (Telegram Mini App + самостоятельный сайт) для TaskFlow. Построено на Vite, React 18, TypeScript, Tailwind CSS и shadcn/ui. Работает на всех экранах (мобилка, планшет, ПК).

## Возможности

- **FocusPage** — Ассистент-first: показывает 1-3 приоритетных задачи (не выполненные, дедлайн ≤ конец сегодня, по приоритету)
- **AllTasksPage** — Полный список задач с фильтром контекста (Сейчас / Сегодня / Неделя / Всё)
- **BoardPage** — Канбан с drag-and-drop (колонки статусов)
- **GroupsPage** — Управление группами/категориями задач
- **SettingsPage** — Настройки уведомлений и пользователя
- **QuickInputModal** — Быстрое создание задачи с поддержкой голоса
- **Адаптив** — Работает на 375px (мобилка) до 1920px (ПК)

## Быстрый старт

Требует: Node.js 18+, pnpm

```bash
# Установить зависимости
pnpm install

# Запустить dev сервер
pnpm dev

# Собрать для production
pnpm build

# Предпросмотр production сборки
pnpm preview
```

Доступно по адресу `http://localhost:5173` (или показано в терминале)

## Настройка Telegram Mini App

1. В [@BotFather](https://t.me/botfather), установите Mini App:
   ```
   /mybotcommand
   → Выберите вашего бота
   → Edit commands
   → Добавить app: https://yourdomain.com/miniapp
   ```

2. Тестируйте в Telegram:
   - Откройте бота
   - Нажмите на иконку приложения
   - Приложение загружается в Telegram WebView

## Конфигурация

Переменные окружения (`.env.local`):
```
VITE_API_URL=/api/v1
VITE_TELEGRAM_BOT_USERNAME=YourBotUsername
```

По умолчанию `VITE_API_URL` не задана — используется относительный путь `/api/v1`, который в dev режиме проксируется Vite (`server.proxy`), а в Docker идёт через nginx.

## Структура проекта

```
src/
├── app/
│   ├── pages/
│   │   ├── FocusPage.tsx
│   │   ├── AllTasksPage.tsx
│   │   ├── BoardPage.tsx
│   │   ├── GroupsPage.tsx
│   │   ├── StatsPage.tsx
│   │   ├── SettingsPage.tsx
│   │   └── AuthPage.tsx
│   ├── components/
│   │   ├── QuickInputModal.tsx
│   │   ├── TaskDetailModal.tsx
│   │   ├── GroupDetailModal.tsx
│   │   └── ui/               shadcn/ui компоненты
│   └── App.tsx
├── lib/
│   ├── api.ts           Axios клиент с JWT интерцепторами
│   ├── auth.ts          Авторизация: initData / Login Widget / demo
│   ├── hooks/
│   │   └── useTasks.ts  TanStack Query хуки (задачи, группы, NLP)
│   └── store.ts         Zustand (auth state, user)
└── main.tsx
```

## Основные библиотеки

- **Vite** — Build tool
- **React 18** — UI фреймворк
- **TypeScript** — Типизация
- **TanStack Query v5** — Управление серверным состоянием
- **Zustand** — Клиентское состояние (auth, UI)
- **React Hook Form** — Обработка форм
- **Zod** — Валидация схем
- **Tailwind CSS** — Стилизация
- **shadcn/ui** — Доступные компоненты
- **@dnd-kit** — Drag-and-drop для Канбана
- **Tabler Icons** — Библиотека иконок

## Интеграция с API

Axios клиент (`lib/api.ts`) имеет:
- JWT токен в Authorization header
- Авто-обновление токена при истечении
- Интерцепторы для request/response
- Обработка ошибок

Поток авторизации:
- **Внутри Telegram:** `initData` → `POST /api/v1/auth/telegram-miniapp` → JWT
- **В браузере (Telegram Login Widget):** `POST /api/v1/auth/telegram-login` → JWT
- **Demo/dev:** `POST /api/v1/auth/dev-token` → JWT

JWT хранится в `localStorage`, refresh токен в Redis.

## Сборка для production

```bash
# Собрать
pnpm build

# Вывод: dist/
# Deploy на nginx/cdn с:
# - Gzip сжатием
# - Cache-Control для assets
# - index.html: Cache-Control: no-cache

# Пример nginx конфига:
# location ~\.js$ { add_header Cache-Control "max-age=31536000"; }
# location / { try_files $uri /index.html; }
```

## Docker

```bash
# Собрать образ
docker build -f miniapp/Dockerfile -t taskflow-miniapp:latest miniapp/

# Запустить
docker run -p 3000:3000 taskflow-miniapp:latest
```

Dockerfile использует multi-stage: сборка в Node 20, запуск через `serve` (node:20-alpine).

## Тестирование

```bash
# Unit тесты
pnpm test

# Покрытие
pnpm test:coverage
```

(Настройка тестов опциональна; проект использует ручное тестирование сейчас)

## Целевые показатели производительности

- **LCP** (Largest Contentful Paint): < 2.5s
- **FID** (First Input Delay): < 100ms
- **CLS** (Cumulative Layout Shift): < 0.1
- Размер бандла: < 300KB gzipped

Проверьте с помощью:
```bash
pnpm build && pnpm preview
# Откройте DevTools → Lighthouse
```
