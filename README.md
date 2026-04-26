# Cryptocurrency-Investment-Advisor-Bot
Telegram bot for analyzing and advising cryptocurrency

## Docs
В папке `docs` находятся:
* Требования к проекту
* Описание архитектуры проекта

## Сборка и запуск
Проект использует Gradle и Java 25. Корневой `build.gradle.kts` собирает приложение как fat JAR через Shadow Jar.

### Генерация Gradle Wrapper
Если в проекте ещё нет Gradle wrapper, выполните:
```bash
gradle wrapper
```
Это создаст:
* `gradlew`
* `gradlew.bat`
* `gradle/wrapper/gradle-wrapper.properties`
* `gradle/wrapper/gradle-wrapper.jar`

### Сборка fat JAR
Выполните:
```bash
./gradlew shadowJar
```
После этого fat JAR появится в `build/libs/crypto-investment-advisor-bot-1.0.0.jar`.

### Запуск локально
```bash
./gradlew run
```

### Запуск через Docker Compose
```bash
docker compose up --build
```

### Образы Docker
Dockerfile собирает приложение в два этапа и копирует fat JAR в контейнер на базе Java 25.
