package ru.spbstu.cryptoadvisor.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class RouterConfig {

    @Bean
    public RouterFunction<ServerResponse> routes(ApiHandler handler) {
        System.out.println("ROUTES LOADED");
        return route()
            .GET("/healthcheck", handler::healthcheck)
            .GET("/users", handler::users)
            .build();
    }
}