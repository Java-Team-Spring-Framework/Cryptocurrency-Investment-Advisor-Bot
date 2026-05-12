package ru.spbstu.cryptoadvisor.repository;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.spbstu.cryptoadvisor.dto.UserAlertInfo;
import ru.spbstu.cryptoadvisor.dto.UserAlertRow;
import ru.spbstu.cryptoadvisor.dto.UserAlertStatus;

import java.util.List;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Repository
public class UserAlertRepository {

    private final DSLContext dsl;

    public UserAlertRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Integer insert(Long userId, String symbol, String type, Double targetValue,
                          Double basePrice, String fiatSymbol) {
        return dsl.insertInto(table("user_alert"),
                        field("user_id"), field("symbol"), field("alert_type"),
                        field("target_value"), field("base_price"), field("fiat_symbol"))
                .values(userId, symbol, type, targetValue, basePrice, fiatSymbol)
                .returning(field("alert_id"))
                .fetchOne()
                .get(field("alert_id"), Integer.class);
    }

    public int deleteByUserAndId(Long userId, Integer alertId) {
        return dsl.deleteFrom(table("user_alert"))
                .where(field("user_id").eq(userId))
                .and(field("alert_id").eq(alertId))
                .execute();
    }

    public List<UserAlertInfo> findByUserId(Long userId) {
        return dsl.select(field("alert_id"), field("symbol"), field("alert_type"),
                        field("target_value"), field("fiat_symbol"))
                .from(table("user_alert"))
                .where(field("user_id").eq(userId))
                .fetch()
                .map(record -> new UserAlertInfo(
                        record.get(field("alert_id"), Integer.class),
                        record.get(field("symbol"), String.class),
                        record.get(field("alert_type"), String.class),
                        record.get(field("target_value"), Double.class),
                        record.get(field("fiat_symbol"), String.class)));
    }

    public boolean existsById(Integer alertId) {
        Integer count = dsl.selectCount()
                .from(table("user_alert"))
                .where(field("alert_id").eq(alertId))
                .fetchOne(0, Integer.class);
        return count != null && count > 0;
    }

    public int deleteById(Integer alertId) {
        return dsl.deleteFrom(table("user_alert"))
                .where(field("alert_id").eq(alertId))
                .execute();
    }

    public int updateBasePrice(Integer alertId, Double basePrice) {
        return dsl.update(table("user_alert"))
                .set(field("base_price"), basePrice)
                .where(field("alert_id").eq(alertId))
                .execute();
    }

    public List<UserAlertRow> findAllForAlertInit() {
        return dsl.select(
                        field("ua.alert_id"),
                        field("u.user_id"),
                        field("u.chat_id"),
                        field("ua.symbol"),
                        field("ua.alert_type"),
                        field("ua.target_value"),
                        field("ua.base_price"),
                        field("ua.fiat_symbol"))
                .from(table("user_alert").as("ua"))
                .join(table("\"user\"").as("u"))
                .on(field("ua.user_id").eq(field("u.user_id")))
                .fetch()
                .map(record -> new UserAlertRow(
                        record.get(field("ua.alert_id"), Integer.class),
                        record.get(field("u.user_id"), Long.class),
                        record.get(field("u.chat_id"), String.class),
                        record.get(field("ua.symbol"), String.class),
                        record.get(field("ua.alert_type"), String.class),
                        record.get(field("ua.target_value"), Double.class),
                        record.get(field("ua.base_price"), Double.class),
                        record.get(field("ua.fiat_symbol"), String.class)));
    }

    public List<UserAlertStatus> findAllForStatus() {
        return dsl.select(
                        field("ua.alert_id"),
                        field("ua.symbol"),
                        field("ua.alert_type"),
                        field("ua.target_value"),
                        field("ua.base_price"),
                        field("ua.fiat_symbol"))
                .from(table("user_alert").as("ua"))
                .fetch()
                .map(record -> new UserAlertStatus(
                        record.get(field("ua.alert_id"), Integer.class),
                        record.get(field("ua.symbol"), String.class),
                        record.get(field("ua.alert_type"), String.class),
                        record.get(field("ua.target_value"), Double.class),
                        record.get(field("ua.base_price"), Double.class),
                        record.get(field("ua.fiat_symbol"), String.class)));
    }
}
