package ru.spbstu.cryptoadvisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class FiatConversionService {

    private static final Logger log = LoggerFactory.getLogger(FiatConversionService.class);
    private final WebClient webClient;

    public FiatConversionService(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Double> getFiatRate(String fromFiat, String toFiat) {
        if (fromFiat.equalsIgnoreCase(toFiat)) {
            return Mono.just(1.0);
        }

        return webClient.get()
                .uri("https://api.frankfurter.app/latest?from={from}&to={to}", fromFiat.toUpperCase(), toFiat.toUpperCase())
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    log.debug("Fiat conversion response for {} to {}: {}", fromFiat, toFiat, response);
                    if (response != null && response.containsKey("rates")) {
                        Map<String, Object> rates = (Map<String, Object>) response.get("rates");
                        Object rate = rates.get(toFiat.toUpperCase());
                        if (rate != null) {
                            return Double.parseDouble(rate.toString());
                        }
                    }
                    return 1.0; // fallback
                })
                .onErrorReturn(1.0);
    }
}