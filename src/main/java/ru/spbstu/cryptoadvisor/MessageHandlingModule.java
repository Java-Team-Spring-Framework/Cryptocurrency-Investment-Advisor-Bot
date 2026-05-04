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
    String prompt = """
        Role: Expert crypto investment analyst.
        Task: Analyze "%s".
        
        RULE 1: If it is NOT a recognized cryptocurrency or token, reply EXACTLY: "Sorry, it is not a cryptocurrency I can analyze."
        RULE 2: If it IS a cryptocurrency, reply EXACTLY in this format (max 200 words total):
        CONTEXT: [2 sentence]
        RISKS: [2 sentence]
        CATALYSTS: [2 sentence]
        VERDICT: INVEST or DO NOT INVEST
        REASON: [2 sentence]
        
        Constraints: Zero extra text. No greetings. No disclaimers. Strict structure only. Base on current market conditions.
        """.formatted(symbol);
        
    return openRouteService.askOpenRouter(prompt);
    }
}
