package ru.spbstu.cryptoadvisor.service;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import ru.spbstu.cryptoadvisor.controller.TelegramBotService;
import ru.spbstu.cryptoadvisor.dto.NotificationMessage;
import ru.spbstu.cryptoadvisor.config.RabbitConfig;
import ru.spbstu.cryptoadvisor.repository.AlertHistoryRepository;

@Service
public class NotificationListenerService {

    private static final Logger log = LoggerFactory.getLogger(
        NotificationListenerService.class
    );

    private final TelegramBotService telegramBotService;
    private final AlertHistoryRepository alertHistoryRepository;

    public NotificationListenerService(
        TelegramBotService telegramBotService,
        AlertHistoryRepository alertHistoryRepository
    ) {
        this.telegramBotService = telegramBotService;
        this.alertHistoryRepository = alertHistoryRepository;
        log.info("NotificationListenerService initialized with dependencies");
    }

    @PostConstruct
    public void afterInit() {
        log.info(
            "NotificationListenerService PostConstruct called - RabbitListener should be registered"
        );
    }

    /**
     * Слушает очередь уведомлений и отправляет сообщения пользователям через Telegram
     */
    @RabbitListener(
        queues = RabbitConfig.QUEUE_NOTIFICATIONS,
        containerFactory = "rabbitListenerContainerFactory"
    )
    public void processNotification(NotificationMessage notification) {
        log.error(">>> NOTIFICATION LISTENER TRIGGERED <<<");
        try {
            log.info(
                "Processing notification for user {}: {}",
                notification.getUserId(),
                notification.getMessage()
            );

            if (
                notification.getChatId() == null ||
                notification.getChatId().isEmpty()
            ) {
                log.warn(
                    "Notification has no chat ID. User ID: {}",
                    notification.getUserId()
                );
                return;
            }

            // Записываем в историю оповещений
            recordNotificationHistory(notification);

            // Формируем сообщение для пользователя
            String messageText = formatNotificationMessage(notification);

            // Отправляем сообщение через Telegram
            log.info(
                "Sending message to Telegram - chat ID: {}, message: {}",
                notification.getChatId(),
                messageText
            );
            telegramBotService.sendMessage(
                notification.getChatId(),
                messageText
            );

            log.info(
                "✅ Notification sent successfully to user {} (chat ID: {})",
                notification.getUserId(),
                notification.getChatId()
            );
        } catch (Exception e) {
            log.error(
                "❌ Error processing notification for user {}: {}",
                notification.getUserId(),
                e.getMessage(),
                e
            );
            // Не бросаем исключение, чтобы сообщение не потерялось при defaultRequeueRejected=false
            // Пробуем отправить напрямую по chatId, если он известен
            if (
                notification.getChatId() != null &&
                !notification.getChatId().isEmpty()
            ) {
                try {
                    telegramBotService.sendMessage(
                        notification.getChatId(),
                        notification.getMessage()
                    );
                    log.info(
                        "Fallback: sent notification directly to chatId={}",
                        notification.getChatId()
                    );
                } catch (Exception ex) {
                    log.error(
                        "Fallback send also failed for chatId={}: {}",
                        notification.getChatId(),
                        ex.getMessage()
                    );
                }
            }
        }
    }

    /**
     * Записывает уведомление в историю оповещений
     */
    private void recordNotificationHistory(NotificationMessage notification) {
        try {
            alertHistoryRepository.insert(
                    notification.getTrackedCurrencyId(),
                    notification.getUserId(),
                    notification.getSymbol(),
                    notification.getReason()
            );
            log.debug(
                "Notification recorded in alert_history for user {}",
                notification.getUserId()
            );
        } catch (Exception e) {
            log.warn(
                "Failed to record notification history for user {}: {}",
                notification.getUserId(),
                e.getMessage()
            );
            // Не прерываем процесс отправки, если запись в БД не удалась
        }
    }

    /**
     * Форматирует сообщение уведомления для пользователя
     */
    private String formatNotificationMessage(NotificationMessage notification) {
        StringBuilder sb = new StringBuilder();

        if (
            notification.getReason() != null &&
            !notification.getReason().isEmpty()
        ) {
            sb.append("🚨 ").append(notification.getReason()).append("\n\n");
        }

        if (
            notification.getSymbol() != null &&
            !notification.getSymbol().isEmpty()
        ) {
            sb
                .append("📊 Currency: ")
                .append(notification.getSymbol())
                .append("\n");
        }

        sb.append(notification.getMessage());

        return sb.toString();
    }
}
