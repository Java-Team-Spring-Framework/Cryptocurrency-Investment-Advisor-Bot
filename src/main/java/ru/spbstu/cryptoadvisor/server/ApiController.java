package ru.spbstu.cryptoadvisor.server;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Legacy annotation-based controller kept for reference / Spring REST Docs.
 * <p>
 * The live HTTP routes are served by {@link ApiHandler} through
 * {@link RouterConfig}. Authorization for admin endpoints is handled by
 * Spring Security (HTTP Basic, ROLE_ADMIN) configured in
 * {@link ru.spbstu.cryptoadvisor.auth.SecurityConfig}.
 */
@RestController
public class ApiController {

    private static final List<String> AUTHORS = List.of(
        "Vasyuk Marina",
        "Kartsev Sergey",
        "Martynenko Anna",
        "Tolchina Alena"
    );

    private final DSLContext dsl;

    public ApiController(DSLContext dsl) {
        this.dsl = dsl;
    }

    @GetMapping("/healthcheck")
    public Mono<Map<String, Object>> healthcheck() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("authors", AUTHORS);
        return Mono.just(response);
    }

    @GetMapping("/users")
    public Mono<List<Map<String, Object>>> users() {
        return Mono.fromCallable(() ->
            dsl
                .select(
                    field("u.user_id"),
                    field("u.chat_id"),
                    field("f.symbol").as("fiat")
                )
                .from(table("\"user\"").as("u"))
                .leftJoin(table("fiat_currency").as("f"))
                .on(field("u.fiat_id").eq(field("f.fiat_id")))
                .fetchMaps()
        ).subscribeOn(Schedulers.boundedElastic());
    }
}
