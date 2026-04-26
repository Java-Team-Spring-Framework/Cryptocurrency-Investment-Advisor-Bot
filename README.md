# Cryptocurrency-Investment-Advisor-Bot
Telegram bot for analyzing and advising cryptocurrency

## Docs
В папке `docs` находятся:
* Требования к проекту
* Описание архитектуры проекта

## Сборка и запуск
Проект использует Gradle и Java 25. Корневой `build.gradle.kts` собирает приложение как fat JAR через Shadow Jar.

### Сборка fat JAR
Очистите и пересоздайте файл fatJar:
```bash
gradlew clean fatJar
```
После этого fat JAR появится в `build/libs/crypto-investment-advisor-bot-1.0.0.jar`.

### Запуск локально
```bash
gradlew run
```
