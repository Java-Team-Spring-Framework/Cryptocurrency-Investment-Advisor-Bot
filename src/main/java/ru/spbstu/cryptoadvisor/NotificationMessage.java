package ru.spbstu.cryptoadvisor;

import java.io.Serializable;

public class NotificationMessage implements Serializable {
    private Long userId;
    private String chatId;
    private Integer trackedCurrencyId;
    private String symbol;
    private String message;
    private String reason;

    public NotificationMessage() {}

    public NotificationMessage(Long userId, String chatId, Integer trackedCurrencyId, String symbol, String message, String reason) {
        this.userId = userId;
        this.chatId = chatId;
        this.trackedCurrencyId = trackedCurrencyId;
        this.symbol = symbol;
        this.message = message;
        this.reason = reason;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public Integer getTrackedCurrencyId() { return trackedCurrencyId; }
    public void setTrackedCurrencyId(Integer trackedCurrencyId) { this.trackedCurrencyId = trackedCurrencyId; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
