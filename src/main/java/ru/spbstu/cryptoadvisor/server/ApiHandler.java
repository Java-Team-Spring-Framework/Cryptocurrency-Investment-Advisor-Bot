package ru.spbstu.cryptoadvisor.server;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class ApiHandler {

    private static final Logger log =
        LoggerFactory.getLogger(ApiHandler.class);

    private static final List<String> AUTHORS = List.of(
        "Vasyuk Marina",
        "Kartsev Sergey",
        "Martynenko Anna",
        "Tolchina Alena"
    );

    private final DSLContext dsl;

    public ApiHandler(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ─────────────────────────────────────────────────────────

    public Mono<ServerResponse> healthcheck(ServerRequest request) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("authors", AUTHORS);

        return ServerResponse.ok()
            .bodyValue(body)
            .doOnError(e -> log.error("Healthcheck error", e))
            .onErrorResume(e ->
                ServerResponse.status(500)
                    .bodyValue(Map.of(
                        "status", "DOWN",
                        "error", e.getMessage()
                    ))
            );
    }

    // ─────────────────────────────────────────────────────────

    /**
     * Admin-only endpoint: the caller is authenticated by Spring Security
     * (HTTP Basic, ROLE_ADMIN) before reaching this handler — see
     * {@link ru.spbstu.cryptoadvisor.auth.SecurityConfig}.
     */
    public Mono<ServerResponse> users(ServerRequest request) {
        return Mono.fromCallable(() ->
                dsl.select(
                        field("u.user_id"),
                        field("u.chat_id"),
                        field("f.symbol").as("fiat")
                    )
                    .from(table("\"user\"").as("u"))
                    .leftJoin(table("fiat_currency").as("f"))
                    .on(field("u.fiat_id").eq(field("f.fiat_id")))
                    .fetchMaps()
            )
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(e -> log.error("DB error in /users", e))
            .flatMap(users -> ServerResponse.ok().bodyValue(users))
            .onErrorResume(e ->
                ServerResponse.status(500)
                    .bodyValue(Map.of(
                        "error", "internal_error",
                        "message", e.getMessage()
                    ))
            );
    }
}
