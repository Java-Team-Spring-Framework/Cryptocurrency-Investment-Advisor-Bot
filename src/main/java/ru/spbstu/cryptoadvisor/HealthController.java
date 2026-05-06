package ru.spbstu.cryptoadvisor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
public class HealthController {

    private static final List<String> AUTHORS = List.of(
            "Autors",
            "Java-Team-Spring-Framework"
    );

    @GetMapping("/healthcheck")
    public Mono<Map<String, Object>> healthCheck() {
        return Mono.just(Map.of(
                "status", "UP",
                "authors", AUTHORS
        ));
    }
}
