package ru.spbstu.cryptoadvisor;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RabbitMQService {

    private final RabbitTemplate rabbitTemplate;

    public RabbitMQService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void logActivity(String userId, String activity) {
        rabbitTemplate.convertAndSend("crypto.logs", Map.of(
                "userId", userId,
                "activity", activity,
                "timestamp", System.currentTimeMillis()
        ));
    }
}
