package ru.spbstu.cryptoadvisor.dto;

public class TrackedCurrencyRow {
    private final Integer trackedCurrencyId;
    private final Long userId;
    private final String chatId;
    private final String symbol;

    public TrackedCurrencyRow(Integer trackedCurrencyId, Long userId, String chatId, String symbol) {
        this.trackedCurrencyId = trackedCurrencyId;
        this.userId = userId;
        this.chatId = chatId;
        this.symbol = symbol;
    }

    public Integer getTrackedCurrencyId() { return trackedCurrencyId; }
    public Long getUserId() { return userId; }
    public String getChatId() { return chatId; }
    public String getSymbol() { return symbol; }
}
