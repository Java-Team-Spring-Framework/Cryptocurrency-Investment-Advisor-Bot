package ru.spbstu.cryptoadvisor.portfolio;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.*;

/**
 * Модуль управления портфелем криптовалют.
 * Реализует добавление/удаление активов, просмотр портфеля,
 * получение цен первой покупки для расчёта изменения стоимости.
 */
@Component
public class PortfolioManagementModule {

    private static final Logger log = LoggerFactory.getLogger(PortfolioManagementModule.class);

    private final DSLContext dsl;

    public PortfolioManagementModule(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Добавление актива в портфель.
     * Если криптовалюта уже есть в портфеле — увеличивается количество.
     * Записывается транзакция покупки с текущей ценой.
     *
     * @param userId  ID пользователя
     * @param symbol  тикер криптовалюты (например, BTC)
     * @param amount  количество монет (должно быть > 0)
     * @param price   цена покупки за единицу в USD
     */
    public void addAsset(Long userId, String symbol, double amount, double price) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        Long cryptoId = getCryptoId(symbol);
        if (cryptoId == null) {
            throw new IllegalArgumentException("Cryptocurrency " + symbol + " not found");
        }

        // Upsert: вставляем новую запись или обновляем количество (PostgreSQL ON CONFLICT)
        dsl.insertInto(table("portfel"),
                        field("user_id"),
                        field("crypto_currency_id"),
                        field("amount"))
                .values(userId, cryptoId, amount)
                .onConflict(field("user_id"), field("crypto_currency_id"))
                .doUpdate()
                .set(field("amount", Double.class),
                     field("portfel.amount", Double.class).plus(amount))
                .execute();

        // Запись транзакции покупки
        dsl.insertInto(table("transaction"),
                        field("user_id"),
                        field("crypto_currency_id"),
                        field("amount"),
                        field("price"),
                        field("date"),
                        field("type"))
                .values(userId, cryptoId, amount, price,
                        Timestamp.valueOf(LocalDateTime.now()), "BUY")
                .execute();

        log.info("Added {} {} to portfolio for user {}, price={}", amount, symbol, userId, price);
    }

    /**
     * Удаление (продажа) актива из портфеля.
     * Уменьшает количество монет; если количество становится <= 0, запись удаляется.
     * Записывается транзакция продажи с текущей ценой.
     *
     * @param userId         ID пользователя
     * @param symbol         тикер криптовалюты
     * @param amountToRemove количество монет для удаления (должно быть > 0)
     * @param currentPrice   текущая цена для записи транзакции
     */
    public void removeAsset(Long userId, String symbol, double amountToRemove, double currentPrice) {
        if (amountToRemove <= 0) {
            throw new IllegalArgumentException("Amount to remove must be positive");
        }

        Long cryptoId = getCryptoId(symbol);
        if (cryptoId == null) {
            throw new IllegalArgumentException("Cryptocurrency " + symbol + " not found");
        }

        // Получаем текущее количество монет в портфеле
        Double currentAmount = dsl.select(field("amount", Double.class))
                .from(table("portfel"))
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .fetchOneInto(Double.class);

        if (currentAmount == null || currentAmount < amountToRemove) {
            throw new IllegalArgumentException("Not enough " + symbol + " in portfolio. Available: " +
                                               (currentAmount != null ? currentAmount : 0));
        }

        double newAmount = currentAmount - amountToRemove;

        if (newAmount <= 0.0) {
            // Полное удаление из портфеля
            dsl.deleteFrom(table("portfel"))
                    .where(field("user_id").eq(userId))
                    .and(field("crypto_currency_id").eq(cryptoId))
                    .execute();
        } else {
            // Уменьшение количества
            dsl.update(table("portfel"))
                    .set(field("amount", Double.class), newAmount)
                    .where(field("user_id").eq(userId))
                    .and(field("crypto_currency_id").eq(cryptoId))
                    .execute();
        }

        // Запись транзакции продажи
        dsl.insertInto(table("transaction"),
                        field("user_id"),
                        field("crypto_currency_id"),
                        field("amount"),
                        field("price"),
                        field("date"),
                        field("type"))
                .values(userId, cryptoId, amountToRemove, currentPrice,
                        Timestamp.valueOf(LocalDateTime.now()), "SELL")
                .execute();

        log.info("Removed {} {} from portfolio for user {}, price={}", amountToRemove, symbol, userId, currentPrice);
    }

    /**
     * Получение текущего состава портфеля.
     *
     * @param userId ID пользователя
     * @return Map: тикер → количество монет
     */
    public Map<String, Double> getPortfolio(Long userId) {
        return dsl.select(field("c.symbol"), field("p.amount"))
                .from(table("portfel").as("p"))
                .join(table("crypto_currency").as("c"))
                .on(field("p.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .where(field("p.user_id").eq(userId))
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        r -> r.get(field("c.symbol"), String.class),
                        r -> r.get(field("p.amount"), Double.class)
                ));
    }

    /**
     * Получение цены первой покупки (в USD) для каждой криптовалюты в портфеле.
     * Используется для расчёта изменения стоимости с момента первого добавления.
     *
     * @param userId ID пользователя
     * @return Map: тикер → цена первой покупки в USD
     */
    public Map<String, Double> getFirstPurchasePrices(Long userId) {
        // Подзапрос: минимальная дата покупки для каждой криптовалюты
        var minDateSubquery = dsl.select(
                        field("crypto_currency_id"),
                        min(field("date")).as("min_date"))
                .from(table("transaction"))
                .where(field("user_id").eq(userId))
                .and(field("type").eq("BUY"))
                .groupBy(field("crypto_currency_id"))
                .asTable("t_min");

        return dsl.select(
                        field("c.symbol"),
                        field("t.price"))
                .from(table("transaction").as("t"))
                .join(minDateSubquery)
                .on(field("t.crypto_currency_id").eq(minDateSubquery.field("crypto_currency_id")))
                .and(field("t.date").eq(minDateSubquery.field("min_date")))
                .and(field("t.type").eq("BUY"))
                .join(table("crypto_currency").as("c"))
                .on(field("t.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .where(field("t.user_id").eq(userId))
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        r -> r.get(field("c.symbol"), String.class),
                        r -> r.get(field("t.price"), Double.class)
                ));
    }

    /**
     * Получение даты первой покупки для каждой криптовалюты в портфеле.
     * Используется для сравнения длины истории удержания с запрашиваемым периодом.
     *
     * @param userId ID пользователя
     * @return Map: тикер → дата первой покупки
     */
    public Map<String, java.time.LocalDateTime> getFirstPurchaseDates(Long userId) {
        return dsl.select(
                        field("c.symbol"),
                        min(field("t.date")).as("min_date"))
                .from(table("transaction").as("t"))
                .join(table("crypto_currency").as("c"))
                .on(field("t.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .where(field("t.user_id").eq(userId))
                .and(field("t.type").eq("BUY"))
                .groupBy(field("c.symbol"))
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        r -> r.get(field("c.symbol"), String.class),
                        r -> {
                            java.sql.Timestamp ts = r.get(field("min_date"), java.sql.Timestamp.class);
                            return ts != null ? ts.toLocalDateTime() : java.time.LocalDateTime.now();
                        }
                ));
    }

    /**
     * Поиск ID криптовалюты по тикеру.
     */
    private Long getCryptoId(String symbol) {
        return dsl.select(field("crypto_currency_id"))
                .from(table("crypto_currency"))
                .where(field("symbol").eq(symbol.toUpperCase()))
                .fetchOne(0, Long.class);
    }
}
