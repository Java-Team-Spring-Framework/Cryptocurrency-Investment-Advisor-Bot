package ru.spbstu.cryptoadvisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;

import java.util.Map;

@Service
public class BingXService {

    private static final Logger log = LoggerFactory.getLogger(BingXService.class);
    private final WebClient webClient;
    private final FiatConversionService fiatConversionService;
    private static final String BASE_URL = "https://bingx-api.github.io";

    public BingXService(WebClient webClient, FiatConversionService fiatConversionService) {
        this.webClient = webClient;
        this.fiatConversionService = fiatConversionService;
    }

    public Mono<Double> getPrice(String symbol, String fiat) {
        String normalizedFiat = fiat.toUpperCase();
        if (normalizedFiat.equals("USD")) {
            return getPriceInQuote(symbol, "USDT");
        }

        return Mono.zip(
                getPriceInQuote(symbol, "USDT"),
                fiatConversionService.getFiatRate("USD", normalizedFiat)
        ).map(tuple -> {
            double cryptoUsdt = tuple.getT1();
            double fiatRate = tuple.getT2();
            if (cryptoUsdt <= 0 || fiatRate <= 0) {
                return 0.0;
            }
            return cryptoUsdt * fiatRate;
        });
    }

    private Mono<Double> getPriceInQuote(String symbol, String quote) {
        String bingXSymbol = symbol.toUpperCase() + "-" + quote.toUpperCase();
        return webClient.get()
                .uri(BASE_URL + "/spot/v1/public/ticker?symbol={symbol}", bingXSymbol)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnNext(response -> log.debug("BingX price response for {}: {}", bingXSymbol, response))
                .map(response -> {
                    if (response != null) {
                        // Try multiple possible response structures
                        if (response.containsKey("data")) {
                            Object dataObj = response.get("data");
                            if (dataObj instanceof Map) {
                                Map<String, Object> data = (Map<String, Object>) dataObj;
                                if (data.containsKey("lastPrice")) {
                                    return Double.parseDouble(data.get("lastPrice").toString());
                                } else if (data.containsKey("price")) {
                                    return Double.parseDouble(data.get("price").toString());
                                }
                            } else if (dataObj instanceof List) {
                                List<Map<String, Object>> dataList = (List<Map<String, Object>>) dataObj;
                                if (!dataList.isEmpty()) {
                                    Map<String, Object> item = dataList.get(0);
                                    if (item.containsKey("lastPrice")) {
                                        return Double.parseDouble(item.get("lastPrice").toString());
                                    } else if (item.containsKey("price")) {
                                        return Double.parseDouble(item.get("price").toString());
                                    }
                                }
                            }
                        } else if (response.containsKey("price")) {
                            return Double.parseDouble(response.get("price").toString());
                        } else if (response.containsKey("lastPrice")) {
                            return Double.parseDouble(response.get("lastPrice").toString());
                        }
                    }
                    log.warn("Could not parse price from BingX response for {}", bingXSymbol);
                    return 0.0;
                })
                .onErrorResume(e -> {
                    log.error("Error fetching price from BingX for {}: {}", bingXSymbol, e.getMessage());
                    return Mono.just(0.0);
                });
    }

    public Mono<Double> get24hChange(String symbol, String fiat) {
        String bingXSymbol = symbol.toUpperCase() + "-" + (fiat.equalsIgnoreCase("USD") ? "USDT" : fiat.toUpperCase());
        
        return webClient.get()
                .uri(BASE_URL + "/openApi/spot/v1/ticker/24hr?symbol={symbol}", bingXSymbol)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    if (response != null && response.containsKey("data")) {
                        List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.get("data");
                        if (!dataList.isEmpty()) {
                            return Double.parseDouble(dataList.get(0).get("priceChangePercent").toString());
                        }
                    }
                    return 0.0;
                })
                .onErrorReturn(0.0);
    }

    public Mono<List<Double>> getHistory(String symbol, String fiat, String interval, int limit) {
        String bingXSymbol = symbol.toUpperCase() + "-" + (fiat.equalsIgnoreCase("USD") ? "USDT" : fiat.toUpperCase());
        
        return webClient.get()
                .uri(BASE_URL + "/openApi/spot/v1/market/klines?symbol={symbol}&interval={interval}&limit={limit}", 
                        bingXSymbol, interval, limit)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    List<Double> prices = new ArrayList<>();
                    if (response != null && response.containsKey("data")) {
                        List<List<Object>> data = (List<List<Object>>) response.get("data");
                        for (List<Object> kline : data) {
                            prices.add(Double.parseDouble(kline.get(4).toString())); // 4 is close price
                        }
                    }
                    return prices;
                })
                .onErrorReturn(new ArrayList<>());
    }
}

// Added missing import
