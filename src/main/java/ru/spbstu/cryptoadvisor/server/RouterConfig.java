package ru.spbstu.cryptoadvisor.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

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

    /**
     * Exposes the router as a {@link WebHandler} under the well-known
     * bean name {@code "webHandler"} so that
     * {@link WebHttpHandlerBuilder#applicationContext(org.springframework.context.ApplicationContext)}
     * can pick it up and wrap it with all registered {@link org.springframework.web.server.WebFilter}
     * beans (in particular Spring Security's {@code WebFilterChainProxy}).
     */
    @Bean(name = WebHttpHandlerBuilder.WEB_HANDLER_BEAN_NAME)
    public WebHandler webHandler(RouterFunction<ServerResponse> routes) {
        return RouterFunctions.toWebHandler(routes);
    }
}
