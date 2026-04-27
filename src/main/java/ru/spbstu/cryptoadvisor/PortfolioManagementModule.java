package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Component
public class PortfolioManagementModule {

    private final DSLContext dsl;

    public PortfolioManagementModule(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void addAsset(Long userId, String symbol, double amount, double price) {
        Long cryptoId = getCryptoId(symbol);
        if (cryptoId == null) return;

        dsl.insertInto(table("portfel"), field("user_id"), field("crypto_currency_id"), field("amount"))
                .values(userId, cryptoId, amount)
                .onDuplicateKeyUpdate()
                .set(field("amount", Double.class), field("portfel.amount", Double.class).plus(amount))
                .execute();

        dsl.insertInto(table("transaction"), field("user_id"), field("crypto_currency_id"), field("amount"), field("price"), field("date"), field("type"))
                .values(userId, cryptoId, amount, price, Timestamp.valueOf(LocalDateTime.now()), "BUY")
                .execute();
    }

    public void removeAsset(Long userId, String symbol, double amount, double price) {
        Long cryptoId = getCryptoId(symbol);
        if (cryptoId == null) return;

        dsl.update(table("portfel"))
                .set(field("amount", Double.class), field("amount", Double.class).minus(amount))
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .execute();

        dsl.insertInto(table("transaction"), field("user_id"), field("crypto_currency_id"), field("amount"), field("price"), field("date"), field("type"))
                .values(userId, cryptoId, amount, price, Timestamp.valueOf(LocalDateTime.now()), "SELL")
                .execute();
    }

    public Map<String, Double> getPortfolio(Long userId) {
        return dsl.select(field("c.symbol"), field("p.amount"))
                .from(table("portfel").as("p"))
                .join(table("crypto_currency").as("c")).on(field("p.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .where(field("p.user_id").eq(userId))
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        r -> r.get(field("c.symbol"), String.class),
                        r -> r.get(field("p.amount"), Double.class)
                ));
    }

    private Long getCryptoId(String symbol) {
        return dsl.select(field("crypto_currency_id"))
                .from(table("crypto_currency"))
                .where(field("symbol").eq(symbol.toUpperCase()))
                .fetchOne(0, Long.class);
    }
}
