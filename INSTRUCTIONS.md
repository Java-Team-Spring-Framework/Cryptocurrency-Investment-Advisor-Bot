# Инструкции по запуску проекта Cryptocurrency Investment Advisor Bot

## Требования
- Docker и Docker Compose
- Java 25 (для локальной сборки без Docker)
- Токен Telegram-бота (получите у @BotFather)
- API-ключ OpenRoute (для функций ИИ)

## Настройка переменных окружения
Создайте файл `.env` в корневом каталоге проекта на основе `.env.example` или установите следующие переменные окружения:

- `TELEGRAM_BOT_TOKEN`: Токен вашего Telegram-бота.
- `OPENROUTE_API_KEY`: Ваш API-ключ от OpenRouter (для работы LLM).
- `API_TOKEN`: Токен для доступа к админ-панели (Bearer token).
- `POSTGRES_PASSWORD`: Пароль для базы данных PostgreSQL (по умолчанию `postgres`).
- `RABBITMQ_PASSWORD`: Пароль для RabbitMQ (по умолчанию `guest`).

## Запуск с помощью Docker Compose (Рекомендуется)
Это самый простой способ запустить всё окружение (приложение, БД, RabbitMQ).

```bash
docker-compose up --build
```

После этого бот будет доступен в Telegram, а админ-панель — по адресу `http://localhost:8080/admin/users`.

## Локальная сборка и запуск
Если вы хотите собрать проект локально:

1. Соберите проект с помощью Gradle:
   ```bash
   ./gradlew build
   ```
   (Будет создан fat jar в `build/libs/crypto-investment-advisor-bot-1.0-SNAPSHOT-fat.jar`)

2. Запустите fat jar:
   ```bash
   java -jar build/libs/crypto-investment-advisor-bot-1.0-SNAPSHOT-fat.jar
   ```
   *Примечание: Убедитесь, что PostgreSQL и RabbitMQ запущены и доступны по адресам, указанным в `application.properties`.*

## Где указать API токен?
- **Telegram Token:** В переменную окружения `TELEGRAM_BOT_TOKEN` или в файл `.env`. Также можно прописать напрямую в `src/main/resources/application.properties` в поле `telegram.bot.token`.
- **LLM Token:** В переменную `OPENROUTE_API_KEY`.
- **Admin Token:** В переменную `API_TOKEN`.

## Основные команды бота
- `/start` - Регистрация пользователя.
- `/set_fiat <символ>` - Установка фиатной валюты (USD, EUR, RUB...).
- `/add_tracked_crypto <символ> <цена>` - Добавление монеты в список отслеживания.
- `/price_crypto <символ>` - Текущая цена монеты.
- `/portfolio_add <символ> <количество> <цена>` - Добавление актива в портфель.
- `/portfolio` - Просмотр содержимого портфеля.
- `/llm_analyze <символ>` - Инвестиционный анализ от ИИ.
- `/help` - Список всех команд.
