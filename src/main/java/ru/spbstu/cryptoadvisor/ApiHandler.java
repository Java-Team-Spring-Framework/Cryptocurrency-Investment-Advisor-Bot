package ru.spbstu.cryptoadvisor;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Обработчик HTTP-запросов — работает напрямую с Reactor Netty,
 * минуя Spring WebFlux (WebFilter / RouterFunction / DispatcherHandler).
 *
 * Это необходимо потому что в Spring 7 (non-Boot) WebHttpHandlerBuilder
 * не работает надёжно без Spring Boot auto-configuration.
 */
@Component
public class ApiHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);

    private static final List<String> AUTHORS = List.of(
        "Vasyuk Marina",
        "Kartsev Sergey",
        "Martynenko Anna",
        "Tolchina Alena"
    );

    private final ObjectMapper objectMapper;
    private final AuthAdminModule authAdminModule;
    private final DSLContext dsl;

    public ApiHandler(
        ObjectMapper objectMapper,
        AuthAdminModule authAdminModule,
        DSLContext dsl
    ) {
        this.objectMapper = objectMapper;
        this.authAdminModule = authAdminModule;
        this.dsl = dsl;
    }

    /**
     * Главный точка входа — вызывается Reactor Netty для каждого запроса.
     */
    public Publisher<Void> handle(
        HttpServerRequest request,
        HttpServerResponse response
    ) {
        String path = normalizePath(request.uri());
        String method = request.method().name();

        log.info("HTTP {} {}", method, path);

        if ("GET".equalsIgnoreCase(method)) {
            if ("/healthcheck".equals(path)) {
                return handleHealthCheck(response);
            }
            if ("/users".equals(path)) {
                return handleUsers(request, response);
            }
        }

        log.warn(
            "No HTTP route matched: method={}, path={}, rawUri={}",
            method,
            path,
            request.uri()
        );
        return writeJson(
            response,
            HttpStatus.NOT_FOUND.value(),
            Map.of("status", 404, "message", "Not Found", "path", path)
        );
    }

    private String normalizePath(String uri) {
        if (uri == null || uri.isBlank()) {
            return "/";
        }
        int queryIndex = uri.indexOf('?');
        String path = queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
        if (path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    // ─── GET /healthcheck ─────────────────────────────────────────────────────

    private Publisher<Void> handleHealthCheck(HttpServerResponse response) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("authors", AUTHORS);
        return writeJson(response, 200, body);
    }

    // ─── GET /users ───────────────────────────────────────────────────────────

    private Publisher<Void> handleUsers(
        HttpServerRequest request,
        HttpServerResponse response
    ) {
        String authorization = request.requestHeaders().get("Authorization");

        try {
            authAdminModule.validateBearerToken(authorization);
        } catch (ResponseStatusException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", e.getStatusCode().value());
            err.put("error", "Unauthorized");
            err.put(
                "message",
                e.getReason() != null ? e.getReason() : "Invalid token"
            );
            return writeJson(response, e.getStatusCode().value(), err);
        }

        // JOOQ-запрос — на boundedElastic, чтобы не блокировать Netty event loop
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
        )
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(users -> Mono.from(writeJson(response, 200, users)));
    }

    // ─── Запись JSON ответа через Reactor Netty API ───────────────────────────

    private Publisher<Void> writeJson(
        HttpServerResponse response,
        int statusCode,
        Object body
    ) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return response
                .status(statusCode)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Content-Length", String.valueOf(bytes.length))
                .sendByteArray(Mono.just(bytes))
                .then();
        } catch (Exception e) {
            log.error("Failed to serialize JSON response", e);
            return response.status(500).send().then();
        }
    }
}
