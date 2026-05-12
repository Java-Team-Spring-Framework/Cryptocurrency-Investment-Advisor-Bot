package ru.spbstu.cryptoadvisor.dto;

public class TrackedCryptoInfo {
    private final String symbol;
    private final Double targetPrice;

    public TrackedCryptoInfo(String symbol, Double targetPrice) {
        this.symbol = symbol;
        this.targetPrice = targetPrice;
    }

    public String getSymbol() {
        return symbol;
    }

    public Double getTargetPrice() {
        return targetPrice;
    }
}
