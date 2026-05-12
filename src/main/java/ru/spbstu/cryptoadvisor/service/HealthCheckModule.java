package ru.spbstu.cryptoadvisor.service;

import org.springframework.stereotype.Component;

@Component
public class HealthCheckModule {

    public String getServiceStatus() {
        return "OK";
    }
}
