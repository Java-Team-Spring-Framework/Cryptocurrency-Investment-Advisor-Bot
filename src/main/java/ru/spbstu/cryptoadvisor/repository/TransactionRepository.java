package ru.spbstu.cryptoadvisor.repository;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.table;

@Repository
public class TransactionRepository {

    private final DSLContext dsl;

    public TransactionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(Long userId, Long cryptoId, double amount, double price,
                       Timestamp date, String type) {
        dsl.insertInto(table("transaction"),
                        field("user_id"),
                        field("crypto_currency_id"),
                        field("amount"),
                        field("price"),
                        field("date"),
                        field("type"))
                .values(userId, cryptoId, amount, price, date, type)
                .execute();
    }

    public Map<String, Double> findFirstPurchasePrices(Long userId) {
        var minDateSubquery = dsl.select(
                        field("crypto_currency_id"),
                        min(field("date")).as("min_date"))
                .from(table("transaction"))
                .where(field("user_id").eq(userId))
                .and(field("type").eq("BUY"))
                .groupBy(field("crypto_currency_id"))
                .asTable("t_min");

        return dsl.select(field("c.symbol"), field("t.price"))
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
                        r -> r.get(field("t.price"), Double.class)));
    }

    public Map<String, LocalDateTime> findFirstPurchaseDates(Long userId) {
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
                            Timestamp ts = r.get(field("min_date"), Timestamp.class);
                            return ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
                        }));
    }
}
