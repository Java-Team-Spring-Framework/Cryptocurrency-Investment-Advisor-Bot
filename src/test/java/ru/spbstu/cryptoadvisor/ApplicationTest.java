package ru.spbstu.cryptoadvisor;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class ApplicationTest {

    @Test
    public void contextLoads() {
        try (
            AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(
                    "ru.spbstu.cryptoadvisor"
                )
        ) {
            assertNotNull(context.getBean(ApiHandler.class));
        }
    }
}
