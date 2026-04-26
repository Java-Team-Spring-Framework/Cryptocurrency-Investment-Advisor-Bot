package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class MessageHandlingModule {

    private final OpenRouteService openRouteService;

    public MessageHandlingModule(OpenRouteService openRouteService) {
        this.openRouteService = openRouteService;
    }

    public Mono<String> processFreeTextRequest(String requestText) {
        return openRouteService.getInvestmentAnalysis(requestText);
    }
}
