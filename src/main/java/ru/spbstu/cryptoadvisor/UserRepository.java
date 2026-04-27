package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    public void updateFiat(Long userId, String fiatSymbol) {
        Long fiatId = dsl.select(field("fiat_id"))
                .from(table("fiat_currency"))
                .where(field("symbol").eq(fiatSymbol))
                .fetchOne(0, Long.class);
        
        if (fiatId != null) {
            dsl.update(table("\"user\""))
                    .set(field("fiat_id"), fiatId)
                    .where(field("user_id").eq(userId))
                    .execute();
        }
    }

    private User mapToUser(Record record) {
        return new User(
                record.get(field("user_id"), Long.class),
                record.get(field("chat_id"), String.class),
                null // Username not stored in DB as per schema
        );
    }
}
