package ru.spbstu.cryptoadvisor.dto;

public class UserAlertRow {
    private final Integer alertId;
    private final Long userId;
    private final String chatId;
    private final String symbol;
    private final String alertType;
    private final Double targetValue;
    private final Double basePrice;
    private final String fiatSymbol;

    public UserAlertRow(Integer alertId, Long userId, String chatId, String symbol,
                        String alertType, Double targetValue, Double basePrice, String fiatSymbol) {
        this.alertId = alertId;
        this.userId = userId;
        this.chatId = chatId;
        this.symbol = symbol;
        this.alertType = alertType;
        this.targetValue = targetValue;
        this.basePrice = basePrice;
        this.fiatSymbol = fiatSymbol;
    }

    public Integer getAlertId() { return alertId; }
    public Long getUserId() { return userId; }
    public String getChatId() { return chatId; }
    public String getSymbol() { return symbol; }
    public String getAlertType() { return alertType; }
    public Double getTargetValue() { return targetValue; }
    public Double getBasePrice() { return basePrice; }
    public String getFiatSymbol() { return fiatSymbol; }
}
