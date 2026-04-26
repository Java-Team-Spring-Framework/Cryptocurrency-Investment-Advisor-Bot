package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Component;

@Component
public class AlertsHandlingModule {

    public void addAlert(String userId, String symbol, double threshold) {
        // TODO: реализовать добавление уведомления
    }

    public void removeAlert(String alertId) {
        // TODO: реализовать удаление уведомления
    }
}
