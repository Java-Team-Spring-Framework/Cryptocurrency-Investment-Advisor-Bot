package ru.spbstu.cryptoadvisor.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import ru.spbstu.cryptoadvisor.repository.UserRepository;

/**
 * Legacy annotation-based controller kept for reference / Spring REST Docs.
 * <p>
 * The live HTTP routes are served by {@link ApiHandler} through
 * {@link ru.spbstu.cryptoadvisor.config.RouterConfig}. Authorization for admin endpoints is handled by
 * Spring Security (HTTP Basic, ROLE_ADMIN) configured in
 * {@link ru.spbstu.cryptoadvisor.config.SecurityConfig}.
 */
@RestController
public class ApiController {

    private static final List<String> AUTHORS = List.of(
        "Vasyuk Marina",
        "Kartsev Sergey",
        "Martynenko Anna",
        "Tolchina Alena"
    );

    private final UserRepository userRepository;

    public ApiController(UserRepository userRepository) {
        this.userRepository = userRepository;
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
        return Mono.fromCallable(userRepository::findAllWithFiat)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
