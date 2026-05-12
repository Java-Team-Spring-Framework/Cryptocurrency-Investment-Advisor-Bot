package ru.spbstu.cryptoadvisor.service;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.spbstu.cryptoadvisor.config.RabbitConfig;
import ru.spbstu.cryptoadvisor.controller.TelegramBotService;
import ru.spbstu.cryptoadvisor.dto.AlertCheckMessage;
import ru.spbstu.cryptoadvisor.dto.AlertHistoryItem;
import ru.spbstu.cryptoadvisor.dto.TrackedCurrencyRow;
import ru.spbstu.cryptoadvisor.dto.TrackedCurrencySummary;
import ru.spbstu.cryptoadvisor.dto.UserAlertRow;
import ru.spbstu.cryptoadvisor.dto.UserAlertStatus;
import ru.spbstu.cryptoadvisor.repository.AlertHistoryRepository;
import ru.spbstu.cryptoadvisor.repository.TrackedCurrencyRepository;
import ru.spbstu.cryptoadvisor.repository.UserAlertRepository;

@Component
public class AlertsHandlingModule implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(
        AlertsHandlingModule.class
    );
    private static final double CHANGE_THRESHOLD_PERCENT = 5.0;
    private static final int ALERT_EXPIRY_DAYS = 7;

    private final BingXService bingXService;
    private final TelegramBotService telegramBotService;
    private final RabbitMQService rabbitMQService;
    private final RabbitAdmin rabbitAdmin;
    private final org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;
    private final TrackedCurrencyRepository trackedCurrencyRepository;
    private final UserAlertRepository userAlertRepository;
    private final AlertHistoryRepository alertHistoryRepository;

    // Track active alerts for logging to file
    private final Map<String, String> activeAlertsStatus =
        new ConcurrentHashMap<>();

    public AlertsHandlingModule(
        BingXService bingXService,
        TelegramBotService telegramBotService,
        RabbitMQService rabbitMQService,
        RabbitAdmin rabbitAdmin,
        org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry,
        TrackedCurrencyRepository trackedCurrencyRepository,
        UserAlertRepository userAlertRepository,
        AlertHistoryRepository alertHistoryRepository
    ) {
        this.bingXService = bingXService;
        this.telegramBotService = telegramBotService;
        this.rabbitMQService = rabbitMQService;
        this.rabbitAdmin = rabbitAdmin;
        this.rabbitListenerEndpointRegistry = rabbitListenerEndpointRegistry;
        this.trackedCurrencyRepository = trackedCurrencyRepository;
        this.userAlertRepository = userAlertRepository;
        this.alertHistoryRepository = alertHistoryRepository;
        telegramBotService.setAlertsHandlingModule(this);
    }

    @Override
    public void afterPropertiesSet() {
        log.info("Scheduling Alerts Module initialization in background...");

        Thread alertsInitThread = new Thread(
            () -> {
                log.info("Initializing Alerts Module...");
                try {
                    rabbitAdmin.initialize();
                    log.info("RabbitMQ Admin initialized successfully");
                } catch (Exception e) {
                    log.error(
                        "Failed to initialize RabbitMQ Admin: {}",
                        e.getMessage()
                    );
                }
                try {
                    rabbitListenerEndpointRegistry.start();
                    log.info("RabbitListenerEndpointRegistry manually started");
                } catch (Exception e) {
                    log.error(
                        "Failed to start listener registry: {}",
                        e.getMessage()
                    );
                }

                try {
                    initQueueFromDb();
                } catch (Exception e) {
                    log.error(
                        "Failed to initialize alerts from DB: {}",
                        e.getMessage(),
                        e
                    );
                }

                startManualRabbitPoller();
            },
            "alerts-module-init"
        );

        alertsInitThread.setDaemon(true);
        alertsInitThread.start();
    }

    private void startManualRabbitPoller() {
        Thread pollerThread = new Thread(
            () -> {
                log.info("Starting manual RabbitMQ poller for debugging...");
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        org.springframework.amqp.core.Message message =
                            rabbitMQService
                                .getRabbitTemplate()
                                .receive(RabbitConfig.QUEUE_ALERTS_CHECK, 2000);
                        if (message != null) {
                            String body = new String(message.getBody());
                            log.error(
                                "!!! MANUAL POLLER RECEIVED MESSAGE: {}",
                                body
                            );
                            AlertCheckMessage task =
                                new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                                    body,
                                    AlertCheckMessage.class
                                );
                            processAlertCheck(task);
                        }
                    } catch (Exception e) {
                        log.error("Manual poller error: {}", e.getMessage());
                    }
                }
            },
            "alerts-rabbit-poller"
        );

        pollerThread.setDaemon(true);
        pollerThread.start();
    }

    private void initQueueFromDb() {
        log.info(
            "Checking database for active tracked currencies to sync with RabbitMQ..."
        );

        // 1. Default 24h 5% alerts for tracked currencies
        List<TrackedCurrencyRow> trackedRecords = trackedCurrencyRepository.findAllForAlertInit();

        for (TrackedCurrencyRow record : trackedRecords) {
            Long userId = record.getUserId();
            String chatId = record.getChatId();
            String symbol = record.getSymbol();
            Integer trackedCurrencyId = record.getTrackedCurrencyId();

            Double currentPrice = 0.0;
            try {
                Double price = bingXService.getPrice(symbol, "USD").block();
                if (price != null) currentPrice = price;
            } catch (Exception e) {
                log.warn(
                    "Failed to get price for {} during init: {}",
                    symbol,
                    e.getMessage()
                );
            }

            AlertCheckMessage msg = AlertCheckMessage.percentChange(
                userId,
                chatId,
                trackedCurrencyId,
                null,
                symbol,
                "USD",
                CHANGE_THRESHOLD_PERCENT,
                currentPrice
            );
            rabbitMQService.sendAlertCheckDelayed24h(msg);
            activeAlertsStatus.put(
                msg.getId(),
                String.format(
                    java.util.Locale.US,
                    "DEFAULT 5%% 24h - Symbol: %s, User: %d, BasePrice: %.4f USD",
                    symbol,
                    userId,
                    currentPrice
                )
            );
        }

        // 2. Custom User Alerts
        List<UserAlertRow> alertRecords = userAlertRepository.findAllForAlertInit();

        for (UserAlertRow record : alertRecords) {
            Integer alertId = record.getAlertId();
            Long userId = record.getUserId();
            String chatId = record.getChatId();
            String symbol = record.getSymbol();
            String type = record.getAlertType();
            Double targetValue = record.getTargetValue();
            Double basePrice = record.getBasePrice();
            String fiatSymbol = record.getFiatSymbol();

            AlertCheckMessage msg;
            if ("PRICE".equalsIgnoreCase(type)) {
                Double currentPriceFiat = 0.0;
                try {
                    Double price = bingXService
                        .getPrice(symbol, fiatSymbol)
                        .block();
                    if (price != null) currentPriceFiat = price;
                } catch (Exception e) {
                    log.warn(
                        "Failed to get price for {} during init: {}",
                        symbol,
                        e.getMessage()
                    );
                }
                AlertCheckMessage.Direction direction = (currentPriceFiat <
                    targetValue)
                    ? AlertCheckMessage.Direction.UP
                    : AlertCheckMessage.Direction.DOWN;
                msg = AlertCheckMessage.threshold(
                    userId,
                    chatId,
                    null,
                    alertId,
                    symbol,
                    fiatSymbol,
                    targetValue,
                    direction
                );
                rabbitMQService.sendAlertCheck(msg);
                activeAlertsStatus.put(
                    msg.getId(),
                    String.format(
                        java.util.Locale.US,
                        "CUSTOM %s - Symbol: %s, User: %d, Target: %.4f %s",
                        type,
                        symbol,
                        userId,
                        targetValue,
                        fiatSymbol
                    )
                );
            } else {
                msg = AlertCheckMessage.percentChange(
                    userId,
                    chatId,
                    null,
                    alertId,
                    symbol,
                    fiatSymbol,
                    targetValue,
                    basePrice
                );
                rabbitMQService.sendAlertCheck(msg);
                activeAlertsStatus.put(
                    msg.getId(),
                    String.format(
                        java.util.Locale.US,
                        "CUSTOM %s - Symbol: %s, User: %d, Target: %.4f%%",
                        type,
                        symbol,
                        userId,
                        targetValue
                    )
                );
            }
        }

        log.info(
            "Synchronized {} tracked currencies and {} custom alerts with RabbitMQ",
            trackedRecords.size(),
            alertRecords.size()
        );
    }

    @RabbitListener(
        queues = RabbitConfig.QUEUE_ALERTS_CHECK,
        containerFactory = "rabbitListenerContainerFactory"
    )
    public void processAlertCheck(AlertCheckMessage task) {
        System.out.println(
            ">>> CONSUMER TRIGGERED FOR " + task.getSymbol() + " <<<"
        );
        log.error(
            ">>> CONSUMER TRIGGERED FOR: id={}, type={}, symbol={} <<<",
            task.getId(),
            task.getType(),
            task.getSymbol()
        );

        if (!Boolean.TRUE.equals(task.getActive())) {
            log.debug("Task {} is inactive, skipping", task.getId());
            activeAlertsStatus.remove(task.getId());
            return;
        }

        // Verify if tracking or alert still exists in DB
        if (task.getAlertId() != null) {
            if (!userAlertRepository.existsById(task.getAlertId())) {
                log.info(
                    "Custom alert {} no longer exists in DB, dropping task {}",
                    task.getAlertId(),
                    task.getId()
                );
                activeAlertsStatus.remove(task.getId());
                return;
            }
        } else if (task.getTrackedCurrencyId() != null) {
            if (!trackedCurrencyRepository.existsById(task.getTrackedCurrencyId())) {
                log.info(
                    "Tracked currency {} no longer exists in DB, dropping task {}",
                    task.getTrackedCurrencyId(),
                    task.getId()
                );
                activeAlertsStatus.remove(task.getId());
                return;
            }
        }

        try {
            if (task.getType() == AlertCheckMessage.Type.THRESHOLD) {
                checkThreshold(task);
            } else {
                checkPercentChange(task);
            }
        } catch (Exception e) {
            log.error(
                "Error processing alert task {}: {}",
                task.getId(),
                e.getMessage(),
                e
            );
            // Re-queue with delay on failure
            if (
                task.getType() == AlertCheckMessage.Type.PERCENT_CHANGE &&
                task.getTrackedCurrencyId() != null
            ) {
                rabbitMQService.sendAlertCheckDelayed24h(task);
            } else {
                rabbitMQService.sendAlertCheckDelayed(task);
            }
        }
    }

    private void checkThreshold(AlertCheckMessage task) {
        Double currentPrice = null;
        try {
            currentPrice = bingXService
                .getPrice(task.getSymbol(), task.getFiatSymbol())
                .block();
        } catch (Exception e) {
            log.error(
                "Error getting price for threshold check: {}",
                e.getMessage()
            );
        }

        if (currentPrice != null) {
            activeAlertsStatus.put(
                task.getId(),
                String.format(
                    java.util.Locale.US,
                    "CUSTOM PRICE - Symbol: %s, Fiat: %s, Current: %.4f, Target: %.4f",
                    task.getSymbol(),
                    task.getFiatSymbol(),
                    currentPrice,
                    task.getThresholdValue()
                )
            );
            boolean triggered = false;
            if (
                task.getDirection() == AlertCheckMessage.Direction.UP &&
                currentPrice >= task.getThresholdValue()
            ) {
                triggered = true;
            } else if (
                task.getDirection() == AlertCheckMessage.Direction.DOWN &&
                currentPrice <= task.getThresholdValue()
            ) {
                triggered = true;
            }

            if (triggered) {
                log.info(
                    "Threshold ALERT triggered for {}: current={}, target={}",
                    task.getSymbol(),
                    currentPrice,
                    task.getThresholdValue()
                );
                String reason = String.format(
                    java.util.Locale.US,
                    "Price %s threshold %.4f %s reached: %.4f",
                    task.getDirection(),
                    task.getThresholdValue(),
                    task.getFiatSymbol(),
                    currentPrice
                );
                String msgText = String.format(
                    java.util.Locale.US,
                    "🔔 ALERT: %s reached target price of %.4f %s (Current: %.4f)",
                    task.getSymbol(),
                    task.getThresholdValue(),
                    task.getFiatSymbol(),
                    currentPrice
                );
                sendDirectNotification(
                    task.getChatId(),
                    task.getUserId(),
                    task.getTrackedCurrencyId(),
                    task.getSymbol(),
                    msgText,
                    reason
                );

                // Delete from DB and stop
                if (task.getAlertId() != null) {
                    userAlertRepository.deleteById(task.getAlertId());
                }
                task.setActive(false);
                activeAlertsStatus.remove(task.getId());
                return;
            }
        } else {
            // Keep previous status in memory if current price fetch fails, just re-queue
            activeAlertsStatus.put(
                task.getId(),
                String.format(
                    java.util.Locale.US,
                    "CUSTOM PRICE - Symbol: %s, Fiat: %s, Target: %.4f (Waiting for API)",
                    task.getSymbol(),
                    task.getFiatSymbol(),
                    task.getThresholdValue()
                )
            );
        }
        // Not triggered, retry later
        rabbitMQService.sendAlertCheckDelayed(task);
    }

    private void checkPercentChange(AlertCheckMessage task) {
        Double currentPrice = null;
        try {
            currentPrice = bingXService
                .getPrice(task.getSymbol(), task.getFiatSymbol())
                .block();
        } catch (Exception e) {
            log.error(
                "Error getting price for percent check: {}",
                e.getMessage()
            );
        }

        if (
            currentPrice != null &&
            task.getBasePrice() != null &&
            task.getBasePrice() > 0
        ) {
            double diff = currentPrice - task.getBasePrice();
            double percentChange = (diff / task.getBasePrice()) * 100.0;

            activeAlertsStatus.put(
                task.getId(),
                String.format(
                    java.util.Locale.US,
                    "PERCENT - Symbol: %s, Base: %.4f, Current: %.4f, Change: %.4f%%, Target: %.4f%%",
                    task.getSymbol(),
                    task.getBasePrice(),
                    currentPrice,
                    percentChange,
                    task.getThresholdValue()
                )
            );

            if (Math.abs(percentChange) >= task.getThresholdValue()) {
                String directionStr =
                    percentChange > 0 ? "increased" : "decreased";
                String reason = String.format(
                    java.util.Locale.US,
                    "Change %.4f%% (from %.4f to %.4f %s)",
                    percentChange,
                    task.getBasePrice(),
                    currentPrice,
                    task.getFiatSymbol()
                );
                String msgText = String.format(
                    java.util.Locale.US,
                    "🔔 %s price has %s by %.4f%%! (Now %.4f %s)",
                    task.getSymbol(),
                    directionStr,
                    Math.abs(percentChange),
                    currentPrice,
                    task.getFiatSymbol()
                );
                sendDirectNotification(
                    task.getChatId(),
                    task.getUserId(),
                    task.getTrackedCurrencyId(),
                    task.getSymbol(),
                    msgText,
                    reason
                );

                if (task.getAlertId() != null) {
                    userAlertRepository.deleteById(task.getAlertId());
                    task.setActive(false);
                    activeAlertsStatus.remove(task.getId());
                    return;
                } else if (task.getTrackedCurrencyId() != null) {
                    task.setBasePrice(currentPrice);
                    rabbitMQService.sendAlertCheckDelayed24h(task);
                    return;
                }
            }

            if (task.getTrackedCurrencyId() != null) {
                rabbitMQService.sendAlertCheckDelayed24h(task);
            } else {
                rabbitMQService.sendAlertCheckDelayed(task);
            }
        } else if (currentPrice != null) {
            task.setBasePrice(currentPrice);
            if (task.getAlertId() != null) {
                userAlertRepository.updateBasePrice(task.getAlertId(), currentPrice);
            }
            if (task.getTrackedCurrencyId() != null) {
                rabbitMQService.sendAlertCheckDelayed24h(task);
            } else {
                rabbitMQService.sendAlertCheckDelayed(task);
            }
        } else {
            rabbitMQService.sendAlertCheckDelayed(task);
        }
    }

    @RabbitListener(
        queues = RabbitConfig.QUEUE_LOGS,
        containerFactory = "rabbitListenerContainerFactory"
    )
    public void processLogs(java.util.Map<String, Object> logMessage) {
        log.info("System Log Received: {}", logMessage);
    }

    @Scheduled(fixedRate = 3600000)
    public void cleanExpiredAlerts() {
        Timestamp expiryDate = Timestamp.valueOf(
            LocalDateTime.now().minusDays(ALERT_EXPIRY_DAYS)
        );
        alertHistoryRepository.deleteOlderThan(expiryDate);
    }

    @Scheduled(fixedRate = 60000)
    public void writeActiveAlertsToFile() {
        try (
            PrintWriter writer = new PrintWriter(
                new FileWriter("alerts_status.log", false)
            )
        ) {
            writer.println("=== Active Alerts Status ===");
            writer.println("Timestamp: " + LocalDateTime.now());
            if (activeAlertsStatus.isEmpty()) {
                writer.println("No active alerts.");
            } else {
                for (Map.Entry<
                    String,
                    String
                > entry : activeAlertsStatus.entrySet()) {
                    writer.println(entry.getKey() + " -> " + entry.getValue());
                }
            }
        } catch (IOException e) {
            log.error("Failed to write alerts status to file", e);
        }
    }

    public String getActiveAlertsStatusString() {
        StringBuilder sb = new StringBuilder(
            "=== Active Alerts Detailed Status ===\n"
        );
        sb.append("Current Time: ").append(LocalDateTime.now()).append("\n\n");

        sb.append("--- Custom User Alerts ---\n");
        List<UserAlertStatus> alertRecords = userAlertRepository.findAllForStatus();

        if (alertRecords.isEmpty()) {
            sb.append("No active custom alerts.\n");
        }

        for (UserAlertStatus r : alertRecords) {
            Integer id = r.getAlertId();
            String symbol = r.getSymbol();
            String type = r.getAlertType();
            Double target = r.getTargetValue();
            Double base = r.getBasePrice();
            String fiat = r.getFiatSymbol();

            Double currentPrice = null;
            try {
                currentPrice = bingXService.getPrice(symbol, fiat).block();
            } catch (Exception e) {
                log.error(
                    "Status check: failed to get price for {}",
                    symbol,
                    e
                );
            }

            if ("PERCENT".equalsIgnoreCase(type)) {
                if (base == null || base == 0.0) {
                    sb.append(
                        String.format(
                            java.util.Locale.US,
                            "ID %d: [PERCENT] %s | Target: %.4f%% | Base Price: WAITING FOR CONSUMER | Current Price: %s\n",
                            id,
                            symbol,
                            target,
                            currentPrice != null
                                ? String.format(
                                      java.util.Locale.US,
                                      "%.4f %s",
                                      currentPrice,
                                      fiat
                                  )
                                : "ERROR"
                        )
                    );
                } else {
                    double percentChange =
                        currentPrice != null
                            ? ((currentPrice - base) / base) * 100.0
                            : 0.0;
                    sb.append(
                        String.format(
                            java.util.Locale.US,
                            "ID %d: [PERCENT] %s | Target: %.4f%% | Base Price (at setup): %.4f %s | Current Price: %s | Current Change: %+.4f%%\n",
                            id,
                            symbol,
                            target,
                            base,
                            fiat,
                            currentPrice != null
                                ? String.format(
                                      java.util.Locale.US,
                                      "%.4f %s",
                                      currentPrice,
                                      fiat
                                  )
                                : "ERROR",
                            percentChange
                        )
                    );
                }
            } else {
                sb.append(
                    String.format(
                        java.util.Locale.US,
                        "ID %d: [PRICE] %s | Target Price: %.4f %s | Current Price: %s\n",
                        id,
                        symbol,
                        target,
                        fiat,
                        currentPrice != null
                            ? String.format(
                                  java.util.Locale.US,
                                  "%.4f %s",
                                  currentPrice,
                                  fiat
                              )
                            : "ERROR"
                    )
                );
            }
        }

        sb.append("\n--- Default 5% 24h Alerts ---\n");
        List<TrackedCurrencySummary> trackedRecords = trackedCurrencyRepository.findAllSummaries();

        if (trackedRecords.isEmpty()) {
            sb.append("No default alerts.\n");
        } else {
            sb
                .append(trackedRecords.size())
                .append(" default alerts active in background.\n");
            // Optionally, we could list them too, but it might be too long.
        }

        return sb.toString();
    }

    /**
     * Отправляет уведомление напрямую через TelegramBotService, минуя очередь RabbitMQ.
     * Также записывает факт срабатывания в alert_history.
     */
    private void sendDirectNotification(
        String chatId,
        Long userId,
        Integer trackedCurrencyId,
        String symbol,
        String message,
        String reason
    ) {
        // 1. Записываем в историю оповещений
        try {
            alertHistoryRepository.insert(trackedCurrencyId, userId, symbol, reason);
        } catch (Exception e) {
            log.warn(
                "Failed to record alert_history for user {}: {}",
                userId,
                e.getMessage()
            );
        }

        // 2. Формируем текст для пользователя
        StringBuilder sb = new StringBuilder();
        if (reason != null && !reason.isEmpty()) {
            sb.append("🚨 ").append(reason).append("\n\n");
        }
        if (symbol != null && !symbol.isEmpty()) {
            sb.append("📊 Currency: ").append(symbol).append("\n");
        }
        sb.append(message);

        // 3. Отправляем напрямую через Telegram
        if (chatId == null || chatId.isEmpty()) {
            log.warn(
                "sendDirectNotification: chatId is empty for user {}, cannot send Telegram message",
                userId
            );
            return;
        }
        log.info(
            "Sending direct Telegram notification to chatId={}, userId={}: {}",
            chatId,
            userId,
            sb
        );
        try {
            telegramBotService.sendMessage(chatId, sb.toString());
            log.info(
                "Direct Telegram notification sent successfully to chatId={}",
                chatId
            );
        } catch (Exception e) {
            log.error(
                "Failed to send direct Telegram notification to chatId={}: {}",
                chatId,
                e.getMessage(),
                e
            );
        }
    }

    public List<String> getRecentAlerts(Long userId) {
        List<AlertHistoryItem> items = alertHistoryRepository.findRecentByUserId(userId, ALERT_EXPIRY_DAYS);
        return items.stream()
                .map(item -> {
                    String sym = item.getSymbol();
                    if (sym == null) {
                        sym = item.getJoinedSymbol();
                    }
                    return String.format(
                            "[%s] %s: %s",
                            item.getDateRecord(),
                            sym != null ? sym : "Unknown",
                            item.getReason()
                    );
                })
                .toList();
    }
}
