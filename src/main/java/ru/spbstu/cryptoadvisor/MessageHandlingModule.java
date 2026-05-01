package ru.spbstu.cryptoadvisor;

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

    public Mono<String> analyzeCryptoInvestment(String symbol) {
        String prompt = "Provide an investment analysis for " + symbol + ". Include market context, risk factors, and potential catalysts for the cryptocurrency market.";
        return openRouteService.askOpenRouter(prompt);
    }
}
