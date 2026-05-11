# Build stage: Temurin JDK 25 on Ubuntu (glibc) — нужен glibc, потому что
# native-библиотеки Netty (io.netty.handler.codec.quic и др.) слинкованы
# против glibc; на alpine/musl они падают с "Error loading shared library
# libgcc_s.so.1: No such file or directory".
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Устанавливаем базовые утилиты и очищаем кэш apt
RUN apt-get update \
    && apt-get install -y --no-install-recommends dos2unix \
    && rm -rf /var/lib/apt/lists/*

# Gradle wrapper
COPY gradlew .
COPY gradle gradle

# Убираем Windows-переносы строк и даём права на выполнение
RUN dos2unix gradlew && chmod +x gradlew

# Скрипты сборки
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Прогрев (скачиваем обёртку и зависимости)
RUN ./gradlew --version

# Исходники и сборка fat-jar
COPY src src
RUN ./gradlew fatJar -x test

# Runtime stage: Temurin JRE 25 на той же glibc-платформе
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/crypto-investment-advisor-bot-1.0-SNAPSHOT-fat.jar app.jar

# Порт по умолчанию (переопределяется через .env / docker-compose)
ENV SERVER_PORT=8080
EXPOSE 8080

# --enable-native-access=ALL-UNNAMED убирает warning Java 25 про native access
# от Netty; без него функциональность не ломается, но засоряется лог.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
