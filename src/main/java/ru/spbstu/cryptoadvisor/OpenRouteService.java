package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class OpenRouteService {

    private final WebClient webClient;

    public OpenRouteService(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<String> getInvestmentAnalysis(String symbol) {
        return webClient.get()
                .uri("https://api.openroute.ai/v1/analysis?symbol={symbol}", symbol)
                .retrieve()
                .bodyToMono(String.class);
    }
}
