package ru.spbstu.cryptoadvisor.health;

import org.springframework.stereotype.Component;

@Component
public class HealthCheckModule {

    public String getServiceStatus() {
        return "OK";
    }
}
