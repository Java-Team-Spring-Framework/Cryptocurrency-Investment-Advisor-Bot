package ru.spbstu.cryptoadvisor;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public Queue logQueue() {
        return new Queue("crypto.logs");
    }
}
