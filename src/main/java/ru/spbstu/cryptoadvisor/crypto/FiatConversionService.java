package ru.spbstu.cryptoadvisor.crypto;

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
        String from = fromFiat.toUpperCase();
        String to = toFiat.toUpperCase();
        if (from.equals(to)) {
            return Mono.just(1.0);
        }

        return webClient.get()
                .uri("https://api.exchangerate-api.com/v4/latest/EUR")
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    log.debug("Fiat conversion response for EUR rates: {}", response);
                    if (response != null && response.containsKey("rates")) {
                        Map<String, Object> rates = (Map<String, Object>) response.get("rates");
                        if (from.equals("EUR")) {
                            Object rate = rates.get(to);
                            if (rate != null) {
                                return Double.parseDouble(rate.toString());
                            }
                        } else if (to.equals("EUR")) {
                            Object rate = rates.get(from);
                            if (rate != null) {
                                return 1.0 / Double.parseDouble(rate.toString());
                            }
                        } else {
                            Object rateFrom = rates.get(from);
                            Object rateTo = rates.get(to);
                            if (rateFrom != null && rateTo != null) {
                                double rf = Double.parseDouble(rateFrom.toString());
                                double rt = Double.parseDouble(rateTo.toString());
                                return rt / rf;
                            }
                        }
                    }
                    return 1.0; // fallback
                })
                .onErrorReturn(1.0);
    }
}