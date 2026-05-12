package ru.spbstu.cryptoadvisor.repository;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import ru.spbstu.cryptoadvisor.model.User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Repository
public class UserRepository {

    private final DSLContext dsl;

    public UserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<User> findAll() {
        return dsl.selectFrom(table("\"user\""))
                .fetch()
                .map(this::mapToUser);
    }

    public Optional<User> findByChatId(String chatId) {
        Record record = dsl.selectFrom(table("\"user\""))
                .where(field("chat_id").eq(chatId))
                .fetchOne();
        return Optional.ofNullable(record).map(this::mapToUser);
    }

    public User save(User user) {
        if (user.getId() == null) {
            Long id = dsl.insertInto(table("\"user\""), field("chat_id"), field("fiat_id"))
                    .values(user.getTelegramId(), 1L) // Default fiat 1 (USD)
                    .returning(field("user_id"))
                    .fetchOne()
                    .getValue(field("user_id"), Long.class);
            user.setId(id);
        } else {
            dsl.update(table("\"user\""))
                    .set(field("chat_id"), user.getTelegramId())
                    .where(field("user_id").eq(user.getId()))
                    .execute();
        }
        return user;
    }

    public boolean updateFiat(Long userId, String fiatSymbol) {
        Long fiatId = dsl.select(field("fiat_id"))
                .from(table("fiat_currency"))
                .where(field("symbol").eq(fiatSymbol.toUpperCase()))
                .fetchOne(0, Long.class);
        
        if (fiatId != null) {
            dsl.update(table("\"user\""))
                    .set(field("fiat_id"), fiatId)
                    .where(field("user_id").eq(userId))
                    .execute();
            return true;
        }
        return false;
    }

    public Optional<String> getFiatSymbolByUserId(Long userId) {
        return Optional.ofNullable(dsl.select(field("f.symbol"))
                .from(table("\"user\"").as("u"))
                .leftJoin(table("fiat_currency").as("f"))
                .on(field("u.fiat_id").eq(field("f.fiat_id")))
                .where(field("u.user_id").eq(userId))
                .fetchOne(0, String.class));
    }

    public Optional<String> findChatIdByUserId(Long userId) {
        return Optional.ofNullable(dsl.select(field("chat_id"))
                .from(table("\"user\""))
                .where(field("user_id").eq(userId))
                .fetchOne(0, String.class));
    }

    public List<Map<String, Object>> findAllWithFiat() {
        return dsl.select(
                        field("u.user_id"),
                        field("u.chat_id"),
                        field("f.symbol").as("fiat"))
                .from(table("\"user\"").as("u"))
                .leftJoin(table("fiat_currency").as("f"))
                .on(field("u.fiat_id").eq(field("f.fiat_id")))
                .fetchMaps();
    }

    private User mapToUser(Record record) {
        return new User(
                record.get(field("user_id"), Long.class),
                record.get(field("chat_id"), String.class),
                null // Username not stored in DB as per schema
        );
    }
}
