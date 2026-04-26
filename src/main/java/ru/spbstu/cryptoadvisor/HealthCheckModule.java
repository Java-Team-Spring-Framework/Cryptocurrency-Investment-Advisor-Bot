package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Component;

@Component
public class HealthCheckModule {

    public String getServiceStatus() {
        return "OK";
    }
}
