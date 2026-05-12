package ru.spbstu.cryptoadvisor.repository;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Repository
public class PortfolioRepository {

    private final DSLContext dsl;

    public PortfolioRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void upsertAdd(Long userId, Long cryptoId, double amount) {
        dsl.insertInto(table("portfel"),
                        field("user_id"), field("crypto_currency_id"), field("amount"))
                .values(userId, cryptoId, amount)
                .onConflict(field("user_id"), field("crypto_currency_id"))
                .doUpdate()
                .set(field("amount", Double.class),
                        field("portfel.amount", Double.class).plus(amount))
                .execute();
    }

    public Double findAmount(Long userId, Long cryptoId) {
        return dsl.select(field("amount", Double.class))
                .from(table("portfel"))
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .fetchOneInto(Double.class);
    }

    public void delete(Long userId, Long cryptoId) {
        dsl.deleteFrom(table("portfel"))
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .execute();
    }

    public void updateAmount(Long userId, Long cryptoId, double newAmount) {
        dsl.update(table("portfel"))
                .set(field("amount", Double.class), newAmount)
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .execute();
    }

    public Map<String, Double> findPortfolioByUser(Long userId) {
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
}
