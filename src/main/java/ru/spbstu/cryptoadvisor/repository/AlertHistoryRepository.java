package ru.spbstu.cryptoadvisor.repository;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.spbstu.cryptoadvisor.dto.AlertHistoryItem;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Repository
public class AlertHistoryRepository {

    private final DSLContext dsl;

    public AlertHistoryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(Integer trackedCurrencyId, Long userId, String symbol, String reason) {
        dsl.insertInto(table("alert_history"),
                        field("tracked_currency_id"),
                        field("user_id"),
                        field("symbol"),
                        field("date_record"),
                        field("alert_reason"))
                .values(trackedCurrencyId, userId, symbol,
                        Timestamp.valueOf(LocalDateTime.now()), reason)
                .execute();
    }

    public int deleteOlderThan(Timestamp expiryDate) {
        return dsl.deleteFrom(table("alert_history"))
                .where(field("date_record").lessThan(expiryDate))
                .execute();
    }

    public List<AlertHistoryItem> findRecentByUserId(Long userId, int days) {
        return dsl.select(
                        field("ah.symbol"),
                        field("ah.date_record"),
                        field("ah.alert_reason"),
                        field("c.symbol"))
                .from(table("alert_history").as("ah"))
                .leftJoin(table("tracked_currency").as("tc"))
                .on(field("ah.tracked_currency_id").eq(field("tc.tracked_currency_id")))
                .leftJoin(table("crypto_currency").as("c"))
                .on(field("tc.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .where(field("ah.user_id").eq(userId)
                        .or(field("tc.user_id").eq(userId)))
                .and(field("ah.date_record").greaterThan(
                        Timestamp.valueOf(LocalDateTime.now().minusDays(days))))
                .orderBy(field("ah.date_record").desc())
                .fetch()
                .map(r -> new AlertHistoryItem(
                        r.get(field("ah.symbol"), String.class),
                        r.get(field("ah.date_record"), Timestamp.class),
                        r.get(field("ah.alert_reason"), String.class),
                        r.get(field("c.symbol"), String.class)));
    }
}
