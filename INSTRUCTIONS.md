# Инструкции по запуску проекта Cryptocurrency Investment Advisor Bot

## Требования
- Docker и Docker Compose
- Java 25 (для локальной сборки без Docker)
- Токен Telegram-бота (получите у @BotFather)
- API-ключ OpenRoute (для функций ИИ)

## Настройка переменных окружения
Создайте файл `.env` в корневом каталоге проекта на основе `.env.example` или установите следующие переменные окружения:

- `TELEGRAM_BOT_TOKEN` — токен вашего Telegram-бота.
- `OPENROUTE_API_KEY` — API-ключ OpenRouter (для работы LLM).
- `ADMIN_USERNAME` — имя администратора для HTTP API. По умолчанию `admin`.
- `ADMIN_PASSWORD` — пароль администратора. **Обязательно** замените на сильное значение в продакшене. По умолчанию `admin`.
- `POSTGRES_PASSWORD` — пароль PostgreSQL (по умолчанию `postgres`).
- `RABBITMQ_PASSWORD` — пароль RabbitMQ (по умолчанию `guest`).

> Bearer-токен (`API_TOKEN`) больше не используется. Авторизация HTTP API
> выполняется через Spring Security (HTTP Basic). Пароль хранится в памяти
> только как BCrypt-хэш и никогда не логируется в открытом виде.

## Запуск с помощью Docker Compose (рекомендуется)
Это самый простой способ запустить всё окружение (приложение, БД, RabbitMQ).

```powershell
docker-compose up --build -d
```

После этого бот будет доступен в Telegram, а HTTP API — по адресу `http://localhost:8081` (порт пробрасывается из docker-compose).

## Локальная сборка и запуск
1. Соберите проект с помощью Gradle:
   ```powershell
   .\gradlew.bat build -x test
   ```
   (Будет создан fat jar в `build/libs/crypto-investment-advisor-bot-1.0-SNAPSHOT-fat.jar`)

2. Запустите fat jar:
   ```powershell
   java -jar build\libs\crypto-investment-advisor-bot-1.0-SNAPSHOT-fat.jar
   ```
   *Убедитесь, что PostgreSQL и RabbitMQ запущены и доступны по адресам из `application.properties`.*

## Где указать API-токены
- **Telegram Token:** в переменной `TELEGRAM_BOT_TOKEN` (или в `.env`, или напрямую в `src/main/resources/application.properties` — `telegram.bot.token`).
- **OpenRoute Token:** в переменной `OPENROUTE_API_KEY`.
- **Admin HTTP credentials:** в переменных `ADMIN_USERNAME` / `ADMIN_PASSWORD`.

## Как обращаться к защищённым эндпоинтам без ввода пароля в командной строке

Эндпоинт `GET /users` требует роли `ROLE_ADMIN` (HTTP Basic). Чтобы пароль
не попадал в историю терминала и в `ps`-логи, используйте один из
следующих способов.

### Вариант A. `.netrc` / `_netrc`
Создайте файл `%USERPROFILE%\_netrc` (Windows) или `~/.netrc` (Linux/macOS):
```
machine localhost
  login admin
  password <ваш_пароль>
```
Затем:
```powershell
curl.exe --netrc http://localhost:8081/users
```
В команде больше нет секретов — пароль читается из файла.

### Вариант B. PowerShell-хелпер
В проекте есть готовый скрипт `scripts/Invoke-AdminApi.ps1`. Он берёт
учётные данные из `$env:ADMIN_USERNAME` / `$env:ADMIN_PASSWORD`, а если
переменные не заданы — запрашивает их интерактивно (пароль маскируется).

```powershell
# Интерактивно (пароль не будет виден)
.\scripts\Invoke-AdminApi.ps1

# Через переменные окружения текущей сессии
$env:ADMIN_USERNAME = 'admin'
$env:ADMIN_PASSWORD = 'super-secret'
.\scripts\Invoke-AdminApi.ps1 -Path /users
```

### Вариант C. Стандартный curl с `-u`
Прямой способ, но пароль попадает в команду (подойдёт для одноразовых
проверок, **не** для автоматизации):
```powershell
curl.exe -u admin:<пароль> http://localhost:8081/users
```

## Основные команды бота
- `/start` — регистрация пользователя.
- `/set_fiat <символ>` — установка фиатной валюты (USD, EUR, RUB…).
- `/add_tracked_crypto <символ> <цена>` — добавление монеты в список отслеживания.
- `/price_crypto <символ>` — текущая цена монеты.
- `/portfolio_add <символ> <количество> <цена>` — добавление актива в портфель.
- `/portfolio` — просмотр содержимого портфеля.
- `/llm_analyze <символ>` — инвестиционный анализ от ИИ.
- `/help` — список всех команд.
