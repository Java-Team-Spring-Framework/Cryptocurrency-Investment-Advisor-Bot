package ru.spbstu.cryptoadvisor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/healthcheck")
    public Mono<Map<String, Object>> healthCheck() {
        return Mono.just(Map.of(
                "status", "UP",
                "server", "Cryptocurrency Investment Advisor Bot",
                "environment", System.getenv().getOrDefault("SERVER_PORT", "8080")
        ));
    }
}
