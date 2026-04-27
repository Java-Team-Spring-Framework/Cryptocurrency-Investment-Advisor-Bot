package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Component
public class AlertsHandlingModule {

    private final DSLContext dsl;
    private final BingXService bingXService;
    private final TelegramBotService telegramBotService;

    public AlertsHandlingModule(DSLContext dsl, BingXService bingXService, TelegramBotService telegramBotService) {
        this.dsl = dsl;
        this.bingXService = bingXService;
        this.telegramBotService = telegramBotService;
    }

    @Scheduled(fixedRate = 60000) // Every minute
    public void checkPrices() {
        dsl.select(field("u.chat_id"), field("c.symbol"), field("tc.target_price"))
                .from(table("tracked_currency").as("tc"))
                .join(table("\"user\"").as("u")).on(field("tc.user_id").eq(field("u.user_id")))
                .join(table("crypto_currency").as("c")).on(field("tc.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .fetch()
                .forEach(record -> {
                    String chatId = record.get(field("u.chat_id"), String.class);
                    String symbol = record.get(field("c.symbol"), String.class);
                    Double targetPrice = record.get(field("tc.target_price"), Double.class);

                    bingXService.getPrice(symbol, "USD")
                            .subscribe(currentPrice -> {
                                if (currentPrice >= targetPrice) {
                                    telegramBotService.sendMessage(chatId, "ALERT! " + symbol + " reached target price: " + currentPrice);
                                }
                            });

                    bingXService.get24hChange(symbol, "USD")
                            .subscribe(changePercent -> {
                                if (Math.abs(changePercent) >= 5.0) {
                                    telegramBotService.sendMessage(chatId, "ALERT! " + symbol + " price changed by " + changePercent + "% in 24h");
                                }
                            });
                });
    }
}
