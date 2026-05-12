package ru.spbstu.cryptoadvisor.dto;

public class UserAlertInfo {
    private final Integer id;
    private final String symbol;
    private final String type;
    private final Double targetValue;
    private final String fiatSymbol;

    public UserAlertInfo(Integer id, String symbol, String type, Double targetValue, String fiatSymbol) {
        this.id = id;
        this.symbol = symbol;
        this.type = type;
        this.targetValue = targetValue;
        this.fiatSymbol = fiatSymbol;
    }

    public Integer getId() { return id; }
    public String getSymbol() { return symbol; }
    public String getType() { return type; }
    public Double getTargetValue() { return targetValue; }
    public String getFiatSymbol() { return fiatSymbol; }
}
