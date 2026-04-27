package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Component
public class CryptoInformationModule {

    private final BingXService bingXService;
    private final DSLContext dsl;

    public CryptoInformationModule(BingXService bingXService, DSLContext dsl) {
        this.bingXService = bingXService;
        this.dsl = dsl;
    }

    public Mono<Double> getCurrentPrice(String symbol, String fiat) {
        return bingXService.getPrice(symbol, fiat);
    }

    public void addTrackedCurrency(Long userId, String symbol, Double targetPrice) {
        Long cryptoId = dsl.select(field("crypto_currency_id"))
                .from(table("crypto_currency"))
                .where(field("symbol").eq(symbol.toUpperCase()))
                .fetchOne(0, Long.class);

        if (cryptoId != null) {
            dsl.insertInto(table("tracked_currency"), 
                    field("user_id"), field("crypto_currency_id"), field("target_price"))
                    .values(userId, cryptoId, targetPrice)
                    .onDuplicateKeyUpdate()
                    .set(field("target_price"), targetPrice)
                    .execute();
        }
    }

    public void removeTrackedCurrency(Long userId, String symbol) {
        Long cryptoId = dsl.select(field("crypto_currency_id"))
                .from(table("crypto_currency"))
                .where(field("symbol").eq(symbol.toUpperCase()))
                .fetchOne(0, Long.class);

        if (cryptoId != null) {
            dsl.deleteFrom(table("tracked_currency"))
                    .where(field("user_id").eq(userId))
                    .and(field("crypto_currency_id").eq(cryptoId))
                    .execute();
        }
    }
}
