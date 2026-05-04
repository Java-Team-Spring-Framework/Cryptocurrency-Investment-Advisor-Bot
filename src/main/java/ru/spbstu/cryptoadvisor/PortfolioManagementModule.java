package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.*;

@Component
public class PortfolioManagementModule {

    private final DSLContext dsl;

    public PortfolioManagementModule(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void addAsset(Long userId, String symbol, double amount, double price) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Количество должно быть положительным числом");
        }

        Long cryptoId = getCryptoId(symbol);
        if (cryptoId == null) {
            throw new IllegalArgumentException("Криптовалюта " + symbol + " не найдена");
        }

        dsl.insertInto(table("portfel"),
                        field("user_id"),
                        field("crypto_currency_id"),
                        field("amount"))
                .values(userId, cryptoId, amount)
                .onDuplicateKeyUpdate()
                .set(field("amount", Double.class),
                     field("portfel.amount", Double.class).plus(amount))
                .execute();

        // Запись транзакции покупки в таблицу transaction
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
    }


    public void removeAsset(Long userId, String symbol, double amountToRemove, double currentPrice) {
        if (amountToRemove <= 0) {
            throw new IllegalArgumentException("Количество для удаления должно быть положительным");
        }

        Long cryptoId = getCryptoId(symbol);
        if (cryptoId == null) {
            throw new IllegalArgumentException("Криптовалюта " + symbol + " не найдена");
        }

       
        Double currentAmount = dsl.select(field("amount", Double.class))
                .from(table("portfel"))
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .fetchOneInto(Double.class);

        if (currentAmount == null || currentAmount < amountToRemove) {
            throw new IllegalArgumentException("Недостаточно монет " + symbol + " в портфеле. Доступно: " +
                                               (currentAmount != null ? currentAmount : 0));
        }

        double newAmount = currentAmount - amountToRemove;

        if (newAmount <= 0.0) {
            dsl.deleteFrom(table("portfel"))
                    .where(field("user_id").eq(userId))
                    .and(field("crypto_currency_id").eq(cryptoId))
                    .execute();
        } else {
       
            dsl.update(table("portfel"))
                    .set(field("amount", Double.class), newAmount)
                    .where(field("user_id").eq(userId))
                    .and(field("crypto_currency_id").eq(cryptoId))
                    .execute();
        }

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
    }

 
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


    public Map<String, Double> getFirstPurchasePrices(Long userId) {
 
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

 
    private Long getCryptoId(String symbol) {
        return dsl.select(field("crypto_currency_id"))
                .from(table("crypto_currency"))
                .where(field("symbol").eq(symbol.toUpperCase()))
                .fetchOne(0, Long.class);
    }
}