package ru.spbstu.cryptoadvisor.dto;

import java.io.Serializable;
import java.util.UUID;

public class AlertCheckMessage implements Serializable {
    public enum Type { THRESHOLD, PERCENT_CHANGE }
    public enum Direction { UP, DOWN, BOTH }

    private String id;
    private Long userId;
    private String chatId;
    private Integer trackedCurrencyId;
    private Integer alertId;
    private String symbol;
    private String fiatSymbol;
    private Type type;
    private Double thresholdValue;
    private Direction direction;
    private Boolean active;
    private Double basePrice;
    private Long createdAt;

    public AlertCheckMessage() {
        this.createdAt = System.currentTimeMillis();
    }

    public AlertCheckMessage(Long userId, String chatId, Integer trackedCurrencyId, Integer alertId, String symbol, String fiatSymbol,
                             Type type, Double thresholdValue, Direction direction, Double basePrice) {
        this();
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.chatId = chatId;
        this.trackedCurrencyId = trackedCurrencyId;
        this.alertId = alertId;
        this.symbol = symbol;
        this.fiatSymbol = fiatSymbol;
        this.type = type;
        this.thresholdValue = thresholdValue;
        this.direction = direction;
        this.active = true;
        this.basePrice = basePrice;
    }

    // Constructor with defaults for common cases
    public static AlertCheckMessage percentChange(Long userId, String chatId, Integer trackedCurrencyId, Integer alertId, String symbol, String fiatSymbol, Double percent, Double basePrice) {
        return new AlertCheckMessage(userId, chatId, trackedCurrencyId, alertId, symbol, fiatSymbol, Type.PERCENT_CHANGE, percent, Direction.BOTH, basePrice);
    }

    public static AlertCheckMessage threshold(Long userId, String chatId, Integer trackedCurrencyId, Integer alertId, String symbol, String fiatSymbol, Double price, Direction direction) {
        return new AlertCheckMessage(userId, chatId, trackedCurrencyId, alertId, symbol, fiatSymbol, Type.THRESHOLD, price, direction, null);
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    
    public Integer getTrackedCurrencyId() { return trackedCurrencyId; }
    public void setTrackedCurrencyId(Integer trackedCurrencyId) { this.trackedCurrencyId = trackedCurrencyId; }

    public Integer getAlertId() { return alertId; }
    public void setAlertId(Integer alertId) { this.alertId = alertId; }
    
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getFiatSymbol() { return fiatSymbol; }
    public void setFiatSymbol(String fiatSymbol) { this.fiatSymbol = fiatSymbol; }
    
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    
    public Double getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(Double thresholdValue) { this.thresholdValue = thresholdValue; }
    
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    
    public Double getBasePrice() { return basePrice; }
    public void setBasePrice(Double basePrice) { this.basePrice = basePrice; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "AlertCheckMessage{" +
                "id='" + id + '\'' +
                ", userId=" + userId +
                ", symbol='" + symbol + '\'' +
                ", type=" + type +
                ", thresholdValue=" + thresholdValue +
                ", direction=" + direction +
                ", basePrice=" + basePrice +
                ", createdAt=" + createdAt +
                '}';
    }
}
