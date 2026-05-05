package ru.spbstu.cryptoadvisor;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Component
public class AlertsHandlingModule implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AlertsHandlingModule.class);
    private static final double CHANGE_THRESHOLD_PERCENT = 5.0;
    private static final int ALERT_EXPIRY_DAYS = 7;

    private final DSLContext dsl;
    private final BingXService bingXService;
    private final TelegramBotService telegramBotService;
    private final RabbitMQService rabbitMQService;
    private final RabbitAdmin rabbitAdmin;

    // Track active alerts for logging to file
    private final Map<String, String> activeAlertsStatus = new ConcurrentHashMap<>();

    public AlertsHandlingModule(DSLContext dsl, BingXService bingXService, 
                                TelegramBotService telegramBotService, 
                                RabbitMQService rabbitMQService,
                                RabbitAdmin rabbitAdmin) {
        this.dsl = dsl;
        this.bingXService = bingXService;
        this.telegramBotService = telegramBotService;
        this.rabbitMQService = rabbitMQService;
        this.rabbitAdmin = rabbitAdmin;
        telegramBotService.setAlertsHandlingModule(this);
    }

    @Override
    public void afterPropertiesSet() {
        log.info("Initializing Alerts Module...");
        try {
            rabbitAdmin.initialize();
            log.info("RabbitMQ Admin initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize RabbitMQ Admin: {}", e.getMessage());
        }
        initQueueFromDb();
    }

    private void initQueueFromDb() {
        log.info("Checking database for active tracked currencies to sync with RabbitMQ...");
        
        // 1. Default 24h 5% alerts for tracked currencies
        var trackedRecords = dsl.select(
                        field("tc.tracked_currency_id"),
                        field("u.user_id"),
                        field("u.chat_id"),
                        field("c.symbol"))
                .from(table("tracked_currency").as("tc"))
                .join(table("\"user\"").as("u")).on(field("tc.user_id").eq(field("u.user_id")))
                .join(table("crypto_currency").as("c")).on(field("tc.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .fetch();

        for (Record record : trackedRecords) {
            Long userId = record.get(field("u.user_id"), Long.class);
            String chatId = record.get(field("u.chat_id"), String.class);
            String symbol = record.get(field("c.symbol"), String.class);
            Integer trackedCurrencyId = record.get(field("tc.tracked_currency_id"), Integer.class);

            Double currentPrice = 0.0;
            try {
                Double price = bingXService.getPrice(symbol, "USD").block();
                if (price != null) currentPrice = price;
            } catch (Exception e) {
                log.warn("Failed to get price for {} during init: {}", symbol, e.getMessage());
            }

            AlertCheckMessage msg = AlertCheckMessage.percentChange(userId, chatId, trackedCurrencyId, null, symbol, "USD", CHANGE_THRESHOLD_PERCENT, currentPrice);
            rabbitMQService.sendAlertCheckDelayed24h(msg);
            activeAlertsStatus.put(msg.getId(), String.format(java.util.Locale.US, "DEFAULT 5%% 24h - Symbol: %s, User: %d, BasePrice: %.4f USD", symbol, userId, currentPrice));
        }

        // 2. Custom User Alerts
        var alertRecords = dsl.select(
                field("ua.alert_id"),
                field("u.user_id"),
                field("u.chat_id"),
                field("ua.symbol"),
                field("ua.alert_type"),
                field("ua.target_value"),
                field("ua.base_price"),
                field("ua.fiat_symbol")
        ).from(table("user_alert").as("ua"))
         .join(table("\"user\"").as("u")).on(field("ua.user_id").eq(field("u.user_id")))
         .fetch();

        for (Record record : alertRecords) {
            Integer alertId = record.get(field("ua.alert_id"), Integer.class);
            Long userId = record.get(field("u.user_id"), Long.class);
            String chatId = record.get(field("u.chat_id"), String.class);
            String symbol = record.get(field("ua.symbol"), String.class);
            String type = record.get(field("ua.alert_type"), String.class);
            Double targetValue = record.get(field("ua.target_value"), Double.class);
            Double basePrice = record.get(field("ua.base_price"), Double.class);
            String fiatSymbol = record.get(field("ua.fiat_symbol"), String.class);

            AlertCheckMessage msg;
            if ("PRICE".equalsIgnoreCase(type)) {
                Double currentPriceFiat = 0.0;
                try {
                    Double price = bingXService.getPrice(symbol, fiatSymbol).block();
                    if (price != null) currentPriceFiat = price;
                } catch (Exception e) {
                    log.warn("Failed to get price for {} during init: {}", symbol, e.getMessage());
                }
                AlertCheckMessage.Direction direction = (currentPriceFiat < targetValue) ? AlertCheckMessage.Direction.UP : AlertCheckMessage.Direction.DOWN;
                msg = AlertCheckMessage.threshold(userId, chatId, null, alertId, symbol, fiatSymbol, targetValue, direction);
                rabbitMQService.sendAlertCheck(msg);
            } else {
                msg = AlertCheckMessage.percentChange(userId, chatId, null, alertId, symbol, fiatSymbol, targetValue, basePrice);
                rabbitMQService.sendAlertCheck(msg);
            }
            activeAlertsStatus.put(msg.getId(), String.format(java.util.Locale.US, "CUSTOM %s - Symbol: %s, User: %d, Target: %.4f %s", type, symbol, userId, targetValue, fiatSymbol));
        }
        
        log.info("Synchronized {} tracked currencies and {} custom alerts with RabbitMQ", trackedRecords.size(), alertRecords.size());
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_ALERTS_CHECK, containerFactory = "rabbitListenerContainerFactory")
    public void processAlertCheck(AlertCheckMessage task) {
        log.info("Processing Alert Check: id={}, type={}, symbol={}", task.getId(), task.getType(), task.getSymbol());
        
        if (!Boolean.TRUE.equals(task.getActive())) {
            log.debug("Task {} is inactive, skipping", task.getId());
            activeAlertsStatus.remove(task.getId());
            return;
        }

        // Verify if tracking or alert still exists in DB
        if (task.getAlertId() != null) {
            Integer exists = dsl.selectCount().from(table("user_alert")).where(field("alert_id").eq(task.getAlertId())).fetchOne(0, Integer.class);
            if (exists == null || exists == 0) {
                log.info("Custom alert {} no longer exists in DB, dropping task {}", task.getAlertId(), task.getId());
                activeAlertsStatus.remove(task.getId());
                return;
            }
        } else if (task.getTrackedCurrencyId() != null) {
            Integer exists = dsl.selectCount().from(table("tracked_currency")).where(field("tracked_currency_id").eq(task.getTrackedCurrencyId())).fetchOne(0, Integer.class);
            if (exists == null || exists == 0) {
                log.info("Tracked currency {} no longer exists in DB, dropping task {}", task.getTrackedCurrencyId(), task.getId());
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
            log.error("Error processing alert task {}: {}", task.getId(), e.getMessage(), e);
            // Re-queue with delay on failure
            if (task.getType() == AlertCheckMessage.Type.PERCENT_CHANGE && task.getTrackedCurrencyId() != null) {
                rabbitMQService.sendAlertCheckDelayed24h(task);
            } else {
                rabbitMQService.sendAlertCheckDelayed(task);
            }
        }
    }

    private void checkThreshold(AlertCheckMessage task) {
        Double currentPrice = null;
        try {
            currentPrice = bingXService.getPrice(task.getSymbol(), task.getFiatSymbol()).block();
        } catch (Exception e) {
            log.error("Error getting price for threshold check: {}", e.getMessage());
        }
        
        if (currentPrice != null) {
            activeAlertsStatus.put(task.getId(), String.format(java.util.Locale.US, "CUSTOM PRICE - Symbol: %s, Fiat: %s, Current: %.4f, Target: %.4f", task.getSymbol(), task.getFiatSymbol(), currentPrice, task.getThresholdValue()));
            boolean triggered = false;
            if (task.getDirection() == AlertCheckMessage.Direction.UP && currentPrice >= task.getThresholdValue()) {
                triggered = true;
            } else if (task.getDirection() == AlertCheckMessage.Direction.DOWN && currentPrice <= task.getThresholdValue()) {
                triggered = true;
            }

            if (triggered) {
                log.info("Threshold ALERT triggered for {}: current={}, target={}", task.getSymbol(), currentPrice, task.getThresholdValue());
                String reason = String.format(java.util.Locale.US, "Price %s threshold %.4f %s reached: %.4f", task.getDirection(), task.getThresholdValue(), task.getFiatSymbol(), currentPrice);
                String text = String.format(java.util.Locale.US, "🔔 ALERT: %s reached target price of %.4f %s (Current: %.4f)", task.getSymbol(), task.getThresholdValue(), task.getFiatSymbol(), currentPrice);
                rabbitMQService.sendNotification(new NotificationMessage(task.getUserId(), task.getChatId(), task.getTrackedCurrencyId(), task.getSymbol(), text, reason));
                
                // Delete from DB and stop
                if (task.getAlertId() != null) {
                    dsl.deleteFrom(table("user_alert")).where(field("alert_id").eq(task.getAlertId())).execute();
                }
                task.setActive(false); 
                activeAlertsStatus.remove(task.getId());
                return;
            }
        } else {
            // Keep previous status in memory if current price fetch fails, just re-queue
            activeAlertsStatus.put(task.getId(), String.format(java.util.Locale.US, "CUSTOM PRICE - Symbol: %s, Fiat: %s, Target: %.4f (Waiting for API)", task.getSymbol(), task.getFiatSymbol(), task.getThresholdValue()));
        }
        // Not triggered, retry later
        rabbitMQService.sendAlertCheckDelayed(task);
    }

    private void checkPercentChange(AlertCheckMessage task) {
        Double currentPrice = null;
        try {
            currentPrice = bingXService.getPrice(task.getSymbol(), task.getFiatSymbol()).block();
        } catch (Exception e) {
            log.error("Error getting price for percent check: {}", e.getMessage());
        }
        
        if (currentPrice != null && task.getBasePrice() != null && task.getBasePrice() > 0) {
            double diff = currentPrice - task.getBasePrice();
            double percentChange = (diff / task.getBasePrice()) * 100.0;
            
            activeAlertsStatus.put(task.getId(), String.format(java.util.Locale.US, "PERCENT - Symbol: %s, Base: %.4f, Current: %.4f, Change: %.4f%%, Target: %.4f%%", 
                task.getSymbol(), task.getBasePrice(), currentPrice, percentChange, task.getThresholdValue()));

            if (Math.abs(percentChange) >= task.getThresholdValue()) {
                String directionStr = percentChange > 0 ? "increased" : "decreased";
                String reason = String.format(java.util.Locale.US, "Change %.4f%% (from %.4f to %.4f %s)", percentChange, task.getBasePrice(), currentPrice, task.getFiatSymbol());
                String text = String.format(java.util.Locale.US, "🔔 %s price has %s by %.4f%%! (Now %.4f %s)", task.getSymbol(), directionStr, Math.abs(percentChange), currentPrice, task.getFiatSymbol());
                rabbitMQService.sendNotification(new NotificationMessage(task.getUserId(), task.getChatId(), task.getTrackedCurrencyId(), task.getSymbol(), text, reason));
                
                if (task.getAlertId() != null) {
                    dsl.deleteFrom(table("user_alert")).where(field("alert_id").eq(task.getAlertId())).execute();
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
                dsl.update(table("user_alert"))
                   .set(field("base_price"), currentPrice)
                   .where(field("alert_id").eq(task.getAlertId()))
                   .execute();
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

    @RabbitListener(queues = RabbitConfig.QUEUE_NOTIFICATIONS, containerFactory = "rabbitListenerContainerFactory")
    public void processNotification(NotificationMessage notification) {
        log.info("Received notification for chatId {}: {}", notification.getChatId(), notification.getMessage());
        try {
            // Record to history
            dsl.insertInto(table("alert_history"), field("tracked_currency_id"), field("user_id"), field("symbol"), field("date_record"), field("alert_reason"))
                    .values(notification.getTrackedCurrencyId(), notification.getUserId(), notification.getSymbol(), Timestamp.valueOf(LocalDateTime.now()), notification.getReason())
                    .execute();

            telegramBotService.sendMessage(notification.getChatId(), notification.getMessage());
            log.info("Notification sent successfully to {}", notification.getChatId());
        } catch (Exception e) {
            log.error("Failed to process notification for {}: {}", notification.getChatId(), e.getMessage());
        }
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_LOGS, containerFactory = "rabbitListenerContainerFactory")
    public void processLogs(java.util.Map<String, Object> logMessage) {
        log.info("System Log Received: {}", logMessage);
    }

    @Scheduled(fixedRate = 3600000)
    public void cleanExpiredAlerts() {
        Timestamp expiryDate = Timestamp.valueOf(LocalDateTime.now().minusDays(ALERT_EXPIRY_DAYS));
        dsl.deleteFrom(table("alert_history")).where(field("date_record").lessThan(expiryDate)).execute();
    }

    @Scheduled(fixedRate = 60000)
    public void writeActiveAlertsToFile() {
        try (PrintWriter writer = new PrintWriter(new FileWriter("alerts_status.log", false))) {
            writer.println("=== Active Alerts Status ===");
            writer.println("Timestamp: " + LocalDateTime.now());
            if (activeAlertsStatus.isEmpty()) {
                writer.println("No active alerts.");
            } else {
                for (Map.Entry<String, String> entry : activeAlertsStatus.entrySet()) {
                    writer.println(entry.getKey() + " -> " + entry.getValue());
                }
            }
        } catch (IOException e) {
            log.error("Failed to write alerts status to file", e);
        }
    }

    public String getActiveAlertsStatusString() {
        if (activeAlertsStatus.isEmpty()) {
            return "No active alerts in memory.";
        }
        StringBuilder sb = new StringBuilder("=== Active Alerts Status ===\n");
        sb.append("Timestamp: ").append(LocalDateTime.now()).append("\n\n");
        for (Map.Entry<String, String> entry : activeAlertsStatus.entrySet()) {
            sb.append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    public List<String> getRecentAlerts(Long userId) {
        // Query alert_history directly, joining with tracked_currency if tracked_currency_id is present,
        // or just using user_id/symbol columns if populated.
        // We use COALESCE or CASE to get symbol
        return dsl.select(field("ah.symbol"), field("ah.date_record"), field("ah.alert_reason"), field("c.symbol"))
                .from(table("alert_history").as("ah"))
                .leftJoin(table("tracked_currency").as("tc")).on(field("ah.tracked_currency_id").eq(field("tc.tracked_currency_id")))
                .leftJoin(table("crypto_currency").as("c")).on(field("tc.crypto_currency_id").eq(field("c.crypto_currency_id")))
                .where(field("ah.user_id").eq(userId).or(field("tc.user_id").eq(userId)))
                .and(field("ah.date_record").greaterThan(Timestamp.valueOf(LocalDateTime.now().minusDays(ALERT_EXPIRY_DAYS))))
                .orderBy(field("ah.date_record").desc())
                .fetch()
                .map(r -> {
                    String sym = r.get(field("ah.symbol"), String.class);
                    if (sym == null) {
                        sym = r.get(field("c.symbol"), String.class);
                    }
                    return String.format("[%s] %s: %s", r.get(field("ah.date_record"), Timestamp.class), sym != null ? sym : "Unknown", r.get(field("ah.alert_reason"), String.class));
                });
    }
}
