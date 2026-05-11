package ru.spbstu.cryptoadvisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

public class Application {

    private static final Logger log =
        LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {

        log.info("Starting Cryptocurrency Investment Advisor Bot...");

        System.setProperty(
            "reactor.netty.http.server.accessLogEnabled",
            "true"
        );

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("UNCAUGHT ERROR IN THREAD " + t.getName());
            e.printStackTrace();
        });

        AnnotationConfigApplicationContext context =
            new AnnotationConfigApplicationContext(AppConfig.class);

        log.info("Context started");

        Runtime.getRuntime().addShutdownHook(
            new Thread(context::close)
        );

        // Builds the full reactive pipeline:
        //   WebFilter beans (incl. Spring Security's WebFilterChainProxy)
        //     -> WebHandler bean named "webHandler"
        //        (see RouterConfig#webHandler, which wraps our RouterFunction)
        HttpHandler httpHandler =
            WebHttpHandlerBuilder.applicationContext(context).build();

        log.info("HTTP handler assembled (WebFilterChain + router)");

        int port = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8081")
        );

        DisposableServer server =
            HttpServer.create()
                .host("0.0.0.0")
                .port(port)
                .wiretap(true)
                .handle(new ReactorHttpHandlerAdapter(httpHandler))
                .bindNow();

        log.info("HTTP server started on port {}", port);

        server.onDispose().block();
    }
}
