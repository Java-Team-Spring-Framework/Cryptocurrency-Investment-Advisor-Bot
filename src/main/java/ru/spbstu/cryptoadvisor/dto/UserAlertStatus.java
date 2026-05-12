package ru.spbstu.cryptoadvisor.dto;

public class UserAlertStatus {
    private final Integer alertId;
    private final String symbol;
    private final String alertType;
    private final Double targetValue;
    private final Double basePrice;
    private final String fiatSymbol;

    public UserAlertStatus(Integer alertId, String symbol, String alertType,
                           Double targetValue, Double basePrice, String fiatSymbol) {
        this.alertId = alertId;
        this.symbol = symbol;
        this.alertType = alertType;
        this.targetValue = targetValue;
        this.basePrice = basePrice;
        this.fiatSymbol = fiatSymbol;
    }

    public Integer getAlertId() { return alertId; }
    public String getSymbol() { return symbol; }
    public String getAlertType() { return alertType; }
    public Double getTargetValue() { return targetValue; }
    public Double getBasePrice() { return basePrice; }
    public String getFiatSymbol() { return fiatSymbol; }
}
