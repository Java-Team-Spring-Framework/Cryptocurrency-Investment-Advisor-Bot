package ru.spbstu.cryptoadvisor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class OpenRouteService {


    private final WebClient webClient;
    
    @Value("${openroute.api.key}")
    private String apiKey;

    public OpenRouteService(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<String> getInvestmentAnalysis(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            return Mono.just("OpenRoute API key not configured.");
        }

        return webClient.post()
                .uri("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(Map.of(
                        "model", "google/gemini-pro-1.5",
                        "messages", List.of(Map.of("role", "user", "content", prompt))
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    try {
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        return (String) message.get("content");
                    } catch (Exception e) {
                        return "Error parsing LLM response.";
                    }
                })
                .onErrorResume(e -> Mono.just("Error communicating with LLM: " + e.getMessage()));
    }
}
