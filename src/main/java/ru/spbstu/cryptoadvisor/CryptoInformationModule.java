package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Component;

@Component
public class CryptoInformationModule {

    public String getDefaultCurrency() {
        return "BTC";
    }

    public String getDefaultFiat() {
        return "USD";
    }

    public void addTrackedCurrency(String symbol) {
        // TODO: добавить отслеживаемую криптовалюту
    }
}
