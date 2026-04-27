package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;

import java.util.Map;

@Service
public class BingXService {

    private final WebClient webClient;
    private static final String BASE_URL = "https://open-api.bingx.com";

    public BingXService(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Double> getPrice(String symbol, String fiat) {
        // BingX symbol format is BTC-USDT
        String bingXSymbol = symbol.toUpperCase() + "-" + (fiat.equalsIgnoreCase("USD") ? "USDT" : fiat.toUpperCase());
        
        return webClient.get()
                .uri(BASE_URL + "/openApi/spot/v1/ticker/price?symbol={symbol}", bingXSymbol)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    if (response != null && response.containsKey("data")) {
                        Map<String, Object> data = (Map<String, Object>) response.get("data");
                        return Double.parseDouble(data.get("price").toString());
                    }
                    return 0.0;
                })
                .onErrorReturn(0.0);
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
