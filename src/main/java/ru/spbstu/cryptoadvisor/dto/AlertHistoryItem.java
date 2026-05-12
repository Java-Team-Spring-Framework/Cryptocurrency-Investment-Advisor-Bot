package ru.spbstu.cryptoadvisor.dto;

import java.sql.Timestamp;

public class AlertHistoryItem {
    private final String symbol;
    private final Timestamp dateRecord;
    private final String reason;
    private final String joinedSymbol;

    public AlertHistoryItem(String symbol, Timestamp dateRecord, String reason, String joinedSymbol) {
        this.symbol = symbol;
        this.dateRecord = dateRecord;
        this.reason = reason;
        this.joinedSymbol = joinedSymbol;
    }

    public String getSymbol() { return symbol; }
    public Timestamp getDateRecord() { return dateRecord; }
    public String getReason() { return reason; }
    public String getJoinedSymbol() { return joinedSymbol; }
}
