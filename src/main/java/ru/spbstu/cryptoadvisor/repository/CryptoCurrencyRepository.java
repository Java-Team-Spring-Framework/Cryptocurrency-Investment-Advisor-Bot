package ru.spbstu.cryptoadvisor.repository;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Repository
public class CryptoCurrencyRepository {

    private final DSLContext dsl;

    public CryptoCurrencyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Long findIdBySymbol(String symbol) {
        return dsl.select(field("crypto_currency_id"))
                .from(table("crypto_currency"))
                .where(field("symbol").eq(symbol.toUpperCase()))
                .fetchOne(0, Long.class);
    }
}
