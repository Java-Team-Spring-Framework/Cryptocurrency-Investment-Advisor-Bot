package ru.spbstu.cryptoadvisor.message;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import ru.spbstu.cryptoadvisor.api.openroute.OpenRouteService;

@Component
public class MessageHandlingModule {

    private final OpenRouteService openRouteService;

    public MessageHandlingModule(OpenRouteService openRouteService) {
        this.openRouteService = openRouteService;
    }

    public Mono<String> askOpenRouter(String question) {
        return openRouteService.askOpenRouter(question);
    }

    public Mono<String> sendPrompt(String prompt) {
        return openRouteService.askOpenRouter(prompt);
    }
}
