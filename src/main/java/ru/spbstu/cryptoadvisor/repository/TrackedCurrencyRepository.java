package ru.spbstu.cryptoadvisor.repository;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.spbstu.cryptoadvisor.dto.TrackedCryptoInfo;
import ru.spbstu.cryptoadvisor.dto.TrackedCurrencyRow;
import ru.spbstu.cryptoadvisor.dto.TrackedCurrencySummary;

import java.util.List;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Repository
public class TrackedCurrencyRepository {

    private final DSLContext dsl;

    public TrackedCurrencyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Long findIdByUserAndCrypto(Long userId, Long cryptoId) {
        return dsl.select(field("tracked_currency_id"))
                .from(table("tracked_currency"))
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .fetchOne(0, Long.class);
    }

    public Integer insert(Long userId, Long cryptoId, Double targetPriceUsd) {
        return dsl.insertInto(table("tracked_currency"),
                        field("user_id"), field("crypto_currency_id"), field("target_price"))
                .values(userId, cryptoId, targetPriceUsd)
                .returning(field("tracked_currency_id"))
                .fetchOne()
                .get(field("tracked_currency_id"), Integer.class);
    }

    public int deleteByUserAndCrypto(Long userId, Long cryptoId) {
        return dsl.deleteFrom(table("tracked_currency"))
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .execute();
    }

    public Integer updateTargetPrice(Long userId, Long cryptoId, Double targetPriceUsd) {
        org.jooq.Record record = dsl.update(table("tracked_currency"))
                .set(field("target_price"), targetPriceUsd)
                .where(field("user_id").eq(userId))
                .and(field("crypto_currency_id").eq(cryptoId))
                .returning(field("tracked_currency_id"))
                .fetchOne();
        return record != null ? record.get(field("tracked_currency_id"), Integer.class) : null;
    }

    public List<String> findSymbolsByUser(Long userId) {
        return dsl.select(field("c.symbol"))
                .from(table("tracked_currency").as("t"))
                .join(table("crypto_currency").as("c"))
                .on(field("t.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .where(field("t.user_id").eq(userId))
                .fetch()
                .map(record -> record.get(field("c.symbol"), String.class));
    }

    public List<TrackedCryptoInfo> findInfoByUser(Long userId) {
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

    public List<TrackedCurrencyRow> findAllForAlertInit() {
        return dsl.select(
                        field("tc.tracked_currency_id"),
                        field("u.user_id"),
                        field("u.chat_id"),
                        field("c.symbol"))
                .from(table("tracked_currency").as("tc"))
                .join(table("\"user\"").as("u"))
                .on(field("tc.user_id").eq(field("u.user_id")))
                .join(table("crypto_currency").as("c"))
                .on(field("tc.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .fetch()
                .map(record -> new TrackedCurrencyRow(
                        record.get(field("tc.tracked_currency_id"), Integer.class),
                        record.get(field("u.user_id"), Long.class),
                        record.get(field("u.chat_id"), String.class),
                        record.get(field("c.symbol"), String.class)));
    }

    public boolean existsById(Integer trackedCurrencyId) {
        Integer count = dsl.selectCount()
                .from(table("tracked_currency"))
                .where(field("tracked_currency_id").eq(trackedCurrencyId))
                .fetchOne(0, Integer.class);
        return count != null && count > 0;
    }

    public List<TrackedCurrencySummary> findAllSummaries() {
        return dsl.select(field("tc.tracked_currency_id"), field("c.symbol"))
                .from(table("tracked_currency").as("tc"))
                .join(table("crypto_currency").as("c"))
                .on(field("tc.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .fetch()
                .map(record -> new TrackedCurrencySummary(
                        record.get(field("tc.tracked_currency_id"), Integer.class),
                        record.get(field("c.symbol"), String.class)));
    }
}
