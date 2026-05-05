package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.jooq.Record;
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
    private final RabbitMQService rabbitMQService;

    public CryptoInformationModule(BingXService bingXService, DSLContext dsl, RabbitMQService rabbitMQService) {
        this.bingXService = bingXService;
        this.dsl = dsl;
        this.rabbitMQService = rabbitMQService;
    }

    public Mono<Double> getCurrentPrice(String symbol, String fiat) {
        return bingXService.getPrice(symbol, fiat);
    }

    public boolean addTrackedCurrency(Long userId, String symbol, Double targetPrice, String fiatSymbol) {
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

        int trackedCurrencyId;
        if (exists == null) {
            trackedCurrencyId = dsl.insertInto(table("tracked_currency"),
                    field("user_id"), field("crypto_currency_id"), field("target_price"))
                    .values(userId, cryptoId, targetPrice)
                    .returning(field("tracked_currency_id"))
                    .fetchOne()
                    .get(field("tracked_currency_id"), Integer.class);

            String chatId = dsl.select(field("chat_id"))
                    .from(table("\"user\""))
                    .where(field("user_id").eq(userId))
                    .fetchOne(0, String.class);

            Double currentPriceUsd = getCurrentPrice(normalizedSymbol, "USD").block();
            if (currentPriceUsd == null) currentPriceUsd = 0.0;
            
            // 1. 24h Percent Change Task
            rabbitMQService.sendAlertCheckDelayed24h(AlertCheckMessage.percentChange(userId, chatId, trackedCurrencyId, null, normalizedSymbol, fiatSymbol, 5.0, currentPriceUsd));
        } else {
            trackedCurrencyId = exists.intValue();
        }

        // Add to user_alert table if targetPrice > 0
        if (targetPrice != null && targetPrice > 0) {
            addUserAlert(userId, normalizedSymbol, "PRICE", targetPrice, fiatSymbol);
        }

        return true;
    }

    public Integer addUserAlert(Long userId, String symbol, String type, Double targetValue, String fiatSymbol) {
        String normalizedSymbol = symbol.toUpperCase();
        Double basePrice = null;
        if ("PERCENT".equalsIgnoreCase(type)) {
            try {
                basePrice = getCurrentPrice(normalizedSymbol, fiatSymbol).block();
            } catch (Exception e) {
                // Log and ignore, will be updated by consumer later
            }
            if (basePrice == null) basePrice = 0.0;
        }
        
        Integer alertId = dsl.insertInto(table("user_alert"),
                field("user_id"), field("symbol"), field("alert_type"), field("target_value"), field("base_price"), field("fiat_symbol"))
                .values(userId, normalizedSymbol, type, targetValue, basePrice, fiatSymbol)
                .returning(field("alert_id"))
                .fetchOne()
                .get(field("alert_id"), Integer.class);

        String chatId = dsl.select(field("chat_id"))
                .from(table("\"user\""))
                .where(field("user_id").eq(userId))
                .fetchOne(0, String.class);

        if ("PRICE".equalsIgnoreCase(type)) {
            Double currentPriceFiat = 0.0;
            try {
                Double current = getCurrentPrice(normalizedSymbol, fiatSymbol).block();
                if (current != null) currentPriceFiat = current;
            } catch (Exception e) {
                // Log and ignore
            }
            AlertCheckMessage.Direction direction = (currentPriceFiat < targetValue) ? AlertCheckMessage.Direction.UP : AlertCheckMessage.Direction.DOWN;
            rabbitMQService.sendAlertCheck(AlertCheckMessage.threshold(userId, chatId, null, alertId, normalizedSymbol, fiatSymbol, targetValue, direction));
        } else if ("PERCENT".equalsIgnoreCase(type)) {
            rabbitMQService.sendAlertCheck(AlertCheckMessage.percentChange(userId, chatId, null, alertId, normalizedSymbol, fiatSymbol, targetValue, basePrice));
        }

        return alertId;
    }

    public boolean removeUserAlert(Long userId, Integer alertId) {
        int deleted = dsl.deleteFrom(table("user_alert"))
                .where(field("user_id").eq(userId))
                .and(field("alert_id").eq(alertId))
                .execute();
        return deleted > 0;
    }

    public List<UserAlertInfo> getUserAlerts(Long userId) {
        return dsl.select(field("alert_id"), field("symbol"), field("alert_type"), field("target_value"), field("fiat_symbol"))
                .from(table("user_alert"))
                .where(field("user_id").eq(userId))
                .fetch()
                .map(record -> new UserAlertInfo(
                        record.get(field("alert_id"), Integer.class),
                        record.get(field("symbol"), String.class),
                        record.get(field("alert_type"), String.class),
                        record.get(field("target_value"), Double.class),
                        record.get(field("fiat_symbol"), String.class)
                ));
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

    public boolean updateTargetPrice(Long userId, String symbol, Double targetPrice, String fiatSymbol) {
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

        org.jooq.Record record = dsl.update(table("tracked_currency"))
                .set(field("target_price"), targetPrice)
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .returning(field("tracked_currency_id"))
                .fetchOne();

        if (record != null) {
            addUserAlert(userId, normalizedSymbol, "PRICE", targetPrice, fiatSymbol);
            return true;
        }

        return false;
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
            addTrackedCurrency(userId, "BTC", 0.0, "USD");
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

    public static class UserAlertInfo {
        private final Integer id;
        private final String symbol;
        private final String type;
        private final Double targetValue;
        private final String fiatSymbol;

        public UserAlertInfo(Integer id, String symbol, String type, Double targetValue, String fiatSymbol) {
            this.id = id;
            this.symbol = symbol;
            this.type = type;
            this.targetValue = targetValue;
            this.fiatSymbol = fiatSymbol;
        }

        public Integer getId() { return id; }
        public String getSymbol() { return symbol; }
        public String getType() { return type; }
        public Double getTargetValue() { return targetValue; }
        public String getFiatSymbol() { return fiatSymbol; }
    }
}
