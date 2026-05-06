package ru.spbstu.cryptoadvisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.netty.http.server.HttpServer;

import ru.spbstu.cryptoadvisor.server.ApiHandler;

public class Application {

    private static final Logger log = LoggerFactory.getLogger(
        Application.class
    );

    public static void main(String[] args) {
        log.info("Starting Cryptocurrency Investment Advisor Bot...");

        // Поднимаем Spring-контекст (DI, JooQ, RabbitMQ, Telegram-бот и т.д.)
        AnnotationConfigApplicationContext context =
            new AnnotationConfigApplicationContext("ru.spbstu.cryptoadvisor");
        context.start();
        Runtime.getRuntime().addShutdownHook(
            new Thread(context::close, "spring-context-shutdown")
        );
        log.info("Spring context started successfully.");

        // Получаем наш HTTP-обработчик из контекста
        ApiHandler apiHandler = context.getBean(ApiHandler.class);

        int port = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080")
        );
        log.info("Starting HTTP server on port {}...", port);

        HttpServer.create()
            .host("0.0.0.0")
            .port(port)
            .handle(apiHandler::handle)
            .bindNow()
            .onDispose()
            .block();
    }
}
