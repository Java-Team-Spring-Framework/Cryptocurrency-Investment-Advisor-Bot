package ru.spbstu.cryptoadvisor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class OpenRouteService {

    private final WebClient webClient;

    @Value("${openroute.api.key}")
    private String apiKey;

    @Value("${openroute.base.url:https://openrouter.ai/api/v1}")
    private String baseUrl;

    @Value("${openroute.model:gpt-4o-mini}")
    private String model;

    public OpenRouteService(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<String> askOpenRouter(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            return Mono.just("OpenRoute API key not configured.");
        }

        return webClient.post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of("role", "system", "content", "You are a cryptocurrency market advisor."),
                                Map.of("role", "user", "content", prompt)
                        )
                ))
                .retrieve()
                .onStatus(status -> status.isError(), (ClientResponse clientResponse) -> clientResponse.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new RuntimeException("OpenRoute API error: " + clientResponse.statusCode() + " " + body))))
                .bodyToMono(Map.class)
                .map(response -> {
                    try {
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                        if (choices == null || choices.isEmpty()) {
                            return "Empty LLM response.";
                        }
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        return (String) message.get("content");
                    } catch (Exception e) {
                        return "Error parsing LLM response.";
                    }
                })
                .onErrorResume(e -> Mono.just("Error communicating with LLM: " + e.getMessage()));
    }
}
