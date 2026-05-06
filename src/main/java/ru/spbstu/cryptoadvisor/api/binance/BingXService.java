package ru.spbstu.cryptoadvisor.api.binance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import ru.spbstu.cryptoadvisor.crypto.FiatConversionService;

@Service
public class BingXService {

    private static final Logger log = LoggerFactory.getLogger(
        BingXService.class
    );

    private final WebClient webClient;
    private final FiatConversionService fiatConversionService;

    private static final String BASE_URL = "https://api.binance.com";

    public BingXService(
        WebClient webClient,
        FiatConversionService fiatConversionService
    ) {
        this.webClient = webClient;
        this.fiatConversionService = fiatConversionService;
    }

    private WebClient binanceWebClient() {
        return webClient.mutate().baseUrl(BASE_URL).build();
    }

    // ===================== PRICE =====================

    public Mono<Double> getPrice(String symbol, String fiat) {
        String normalizedFiat = fiat.toUpperCase();

        if ("USD".equals(normalizedFiat)) {
            return getPriceInQuote(symbol, "USDT");
        }

        return Mono.zip(
            getPriceInQuote(symbol, "USDT"),
            fiatConversionService.getFiatRate("USD", normalizedFiat)
        ).map(tuple -> tuple.getT1() * tuple.getT2());
    }

    private Mono<Double> getPriceInQuote(String symbol, String quote) {
        String pair = symbol.toUpperCase() + quote.toUpperCase();

        return binanceWebClient()
            .get()
            .uri(uriBuilder ->
                uriBuilder
                    .path("/api/v3/ticker/price")
                    .queryParam("symbol", pair)
                    .build()
            )
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(Duration.ofSeconds(5))
            .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(1)))
            .map(response -> {
                if (response == null || !response.containsKey("price")) {
                    throw new RuntimeException(
                        "Invalid response from Binance: " + response
                    );
                }
                return Double.parseDouble(response.get("price").toString());
            })
            .doOnNext(price -> log.debug("Price {} = {}", pair, price))
            .doOnError(e ->
                log.error(
                    "Error fetching price for {}: {}",
                    pair,
                    e.getMessage()
                )
            );
    }

    // ===================== 24H CHANGE =====================

    public Mono<Double> get24hChange(String symbol) {
        String pair = symbol.toUpperCase() + "USDT";

        return binanceWebClient()
            .get()
            .uri(uriBuilder ->
                uriBuilder
                    .path("/api/v3/ticker/24hr")
                    .queryParam("symbol", pair)
                    .build()
            )
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(Duration.ofSeconds(5))
            .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(1)))
            .map(response -> {
                if (
                    response == null ||
                    !response.containsKey("priceChangePercent")
                ) {
                    throw new RuntimeException(
                        "Invalid 24h response: " + response
                    );
                }
                return Double.parseDouble(
                    response.get("priceChangePercent").toString()
                );
            })
            .doOnError(e ->
                log.error(
                    "Error fetching 24h change for {}: {}",
                    pair,
                    e.getMessage()
                )
            );
    }

    // ===================== HISTORY =====================

    /**
     * Returns today's open price (at 00:00 UTC) and the latest close price
     * for the given symbol in the specified fiat currency.
     * Result: double[]{openPrice, currentPrice}
     */
    public Mono<double[]> getTodayOpenAndCurrentPrice(
        String symbol,
        String fiat
    ) {
        String pair = symbol.toUpperCase() + "USDT";
        String normalizedFiat = fiat.toUpperCase();

        Mono<double[]> usdMono = binanceWebClient()
            .get()
            .uri(uriBuilder ->
                uriBuilder
                    .path("/api/v3/klines")
                    .queryParam("symbol", pair)
                    .queryParam("interval", "1d")
                    .queryParam("limit", "1")
                    .build()
            )
            .retrieve()
            .bodyToMono(List.class)
            .timeout(Duration.ofSeconds(5))
            .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(1)))
            .map(response -> {
                if (response == null || response.isEmpty()) {
                    return new double[] { 0.0, 0.0 };
                }
                List<?> kline = (List<?>) response.get(0);
                double openPrice = Double.parseDouble(kline.get(1).toString()); // open
                double closePrice = Double.parseDouble(kline.get(4).toString()); // close (current)
                return new double[] { openPrice, closePrice };
            })
            .doOnError(e ->
                log.error(
                    "Error fetching today's candle for {}: {}",
                    pair,
                    e.getMessage()
                )
            );

        if ("USD".equals(normalizedFiat)) {
            return usdMono;
        }

        return Mono.zip(
            usdMono,
            fiatConversionService.getFiatRate("USD", normalizedFiat)
        ).map(tuple -> {
            double[] prices = tuple.getT1();
            double rate = tuple.getT2() != null ? tuple.getT2() : 1.0;
            return new double[] { prices[0] * rate, prices[1] * rate };
        });
    }

    public Mono<List<Double>> getHistory(
        String symbol,
        String interval,
        int limit
    ) {
        String pair = symbol.toUpperCase() + "USDT";

        return binanceWebClient()
            .get()
            .uri(uriBuilder ->
                uriBuilder
                    .path("/api/v3/klines")
                    .queryParam("symbol", pair)
                    .queryParam("interval", interval)
                    .queryParam("limit", limit)
                    .build()
            )
            .retrieve()
            .bodyToMono(List.class)
            .timeout(Duration.ofSeconds(5))
            .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(1)))
            .map(response -> {
                List<Double> prices = new ArrayList<>();

                for (Object item : response) {
                    List<?> kline = (List<?>) item;

                    if (kline.size() > 4) {
                        prices.add(Double.parseDouble(kline.get(4).toString()));
                    }
                }

                return prices;
            })
            .doOnError(e ->
                log.error(
                    "Error fetching history for {}: {}",
                    pair,
                    e.getMessage()
                )
            );
    }
}
