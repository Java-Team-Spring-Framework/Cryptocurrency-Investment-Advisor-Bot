package ru.spbstu.cryptoadvisor.dto;

public class TrackedCurrencySummary {
    private final Integer trackedCurrencyId;
    private final String symbol;

    public TrackedCurrencySummary(Integer trackedCurrencyId, String symbol) {
        this.trackedCurrencyId = trackedCurrencyId;
        this.symbol = symbol;
    }

    public Integer getTrackedCurrencyId() { return trackedCurrencyId; }
    public String getSymbol() { return symbol; }
}
