package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DSLContext dsl;
    private final AuthAdminModule authAdminModule;

    public AdminController(DSLContext dsl, AuthAdminModule authAdminModule) {
        this.dsl = dsl;
        this.authAdminModule = authAdminModule;
    }

    @GetMapping("/users")
    public Flux<Map<String, Object>> getAllUsers(@RequestHeader("Authorization") String authorization) {
        authAdminModule.validateBearerToken(authorization);
        
        return Flux.fromIterable(
            dsl.select(field("u.user_id"), field("u.chat_id"), field("f.symbol").as("fiat"))
                .from(table("\"user\"").as("u"))
                .leftJoin(table("fiat_currency").as("f")).on(field("u.fiat_id").eq(field("f.fiat_id")))
                .fetchMaps()
        );
    }
}
