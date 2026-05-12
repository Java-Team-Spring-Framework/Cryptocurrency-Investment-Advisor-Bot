package ru.spbstu.cryptoadvisor.service;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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
