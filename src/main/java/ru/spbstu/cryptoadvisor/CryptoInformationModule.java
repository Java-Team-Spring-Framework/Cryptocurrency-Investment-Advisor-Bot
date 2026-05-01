package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Component
public class CryptoInformationModule {

    private final BingXService bingXService;
    private final DSLContext dsl;

    public CryptoInformationModule(BingXService bingXService, DSLContext dsl) {
        this.bingXService = bingXService;
        this.dsl = dsl;
    }

    public Mono<Double> getCurrentPrice(String symbol, String fiat) {
        return bingXService.getPrice(symbol, fiat);
    }

    public boolean addTrackedCurrency(Long userId, String symbol, Double targetPrice) {
        if (targetPrice == null || targetPrice < 0) {
            return false;
        }

        String normalizedSymbol = symbol.toUpperCase();
        Long cryptoId = dsl.select(field("crypto_currency_id"))
                .from(table("crypto_currency"))
                .where(field("symbol").eq(normalizedSymbol))
                .fetchOne(0, Long.class);

        if (cryptoId == null) {
            return false;
        }

        Long exists = dsl.select(field("tracked_currency_id"))
                .from(table("tracked_currency"))
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .fetchOne(0, Long.class);

        if (exists != null) {
            return false;
        }

        dsl.insertInto(table("tracked_currency"),
                field("user_id"), field("crypto_currency_id"), field("target_price"))
                .values(userId, cryptoId, targetPrice)
                .execute();
        return true;
    }

    public boolean removeTrackedCurrency(Long userId, String symbol) {
        String normalizedSymbol = symbol.toUpperCase();
        Long cryptoId = dsl.select(field("crypto_currency_id"))
                .from(table("crypto_currency"))
                .where(field("symbol").eq(normalizedSymbol))
                .fetchOne(0, Long.class);

        if (cryptoId == null) {
            return false;
        }

        int deleted = dsl.deleteFrom(table("tracked_currency"))
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .execute();

        return deleted > 0;
    }

    public List<String> getTrackedCurrencies(Long userId) {
        return dsl.select(field("c.symbol"))
                .from(table("tracked_currency").as("t"))
                .join(table("crypto_currency").as("c"))
                .on(field("t.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .where(field("t.user_id").eq(userId))
                .fetch()
                .map(record -> record.get(field("c.symbol"), String.class));
    }

    public List<TrackedCryptoInfo> getTrackedCurrencyInfo(Long userId) {
        return dsl.select(field("c.symbol"), field("t.target_price"))
                .from(table("tracked_currency").as("t"))
                .join(table("crypto_currency").as("c"))
                .on(field("t.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .where(field("t.user_id").eq(userId))
                .fetch()
                .map(record -> new TrackedCryptoInfo(
                        record.get(field("c.symbol"), String.class),
                        record.get(field("t.target_price"), Double.class)));
    }

    public void ensureDefaultTrackedCurrency(Long userId) {
        List<String> tracked = getTrackedCurrencies(userId);
        if (tracked.isEmpty()) {
            addTrackedCurrency(userId, "BTC", 0.0);
        }
    }

    public static class TrackedCryptoInfo {
        private final String symbol;
        private final Double targetPrice;

        public TrackedCryptoInfo(String symbol, Double targetPrice) {
            this.symbol = symbol;
            this.targetPrice = targetPrice;
        }

        public String getSymbol() {
            return symbol;
        }

        public Double getTargetPrice() {
            return targetPrice;
        }
    }
}
