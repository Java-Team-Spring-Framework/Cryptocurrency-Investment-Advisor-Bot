package ru.spbstu.cryptoadvisor.api.rabbitmq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import ru.spbstu.cryptoadvisor.alerts.AlertCheckMessage;
import ru.spbstu.cryptoadvisor.alerts.NotificationMessage;
import ru.spbstu.cryptoadvisor.api.rabbitmq.RabbitConfig;

import java.util.Map;

@Service
public class RabbitMQService {

    private final RabbitTemplate rabbitTemplate;

    public RabbitMQService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void logActivity(String userId, String activity) {
        rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_LOGS, Map.of(
                "userId", userId,
                "activity", activity,
                "timestamp", System.currentTimeMillis()
        ));
    }

    public void sendAlertCheck(AlertCheckMessage message) {
        rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_ALERTS_CHECK, message);
    }

    public void sendAlertCheckDelayed(AlertCheckMessage message) {
        rabbitTemplate.convertAndSend(RabbitConfig.DELAY_QUEUE, message);
    }
    
    public void sendAlertCheckDelayed24h(AlertCheckMessage message) {
        rabbitTemplate.convertAndSend(RabbitConfig.DELAY_QUEUE_24H, message);
    }

    public void sendNotification(NotificationMessage message) {
        rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_NOTIFICATIONS, message);
    }

    public RabbitTemplate getRabbitTemplate() {
        return rabbitTemplate;
    }
}
