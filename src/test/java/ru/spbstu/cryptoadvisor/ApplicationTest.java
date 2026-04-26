package ru.spbstu.cryptoadvisor;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ApplicationTest {

    @Test
    public void contextLoads() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext("ru.spbstu.cryptoadvisor")) {
            assertNotNull(context.getBean(HealthController.class));
        }
    }
}
