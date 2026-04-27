FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Установка необходимых утилит (tr)
RUN apk add --no-cache bash

# Копируем файлы обертки Gradle
COPY gradlew .
COPY gradle gradle

# ИСПРАВЛЕНИЕ: Удаляем Windows-переносы строк и даем права на выполнение
RUN tr -d '\r' < gradlew > gradlew.unix && \
    mv gradlew.unix gradlew && \
    chmod +x gradlew

# Копируем настройки сборки
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Предварительная загрузка зависимостей
RUN ./gradlew --version

# Копируем исходный код и собираем fatJar
COPY src src
RUN ./gradlew fatJar -x test

# Финальный образ
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/build/libs/crypto-investment-advisor-bot-1.0-SNAPSHOT-fat.jar app.jar

# Настройка переменных по умолчанию (можно переопределить в .env)
ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
