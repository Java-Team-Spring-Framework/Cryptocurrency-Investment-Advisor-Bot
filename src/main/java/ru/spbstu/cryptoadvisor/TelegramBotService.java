package ru.spbstu.cryptoadvisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TelegramBotService extends TelegramLongPollingBot implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);
    private static final int MAX_RETRIES = 10;
    private static final long RETRY_DELAY_MS = 5000;

    private static final List<String> FIAT_SYMBOLS = Arrays.asList("USD", "EUR", "JPY", "GBP", "TRY", "RUB", "CNY");
    private static final List<String> CRYPTO_SYMBOLS = Arrays.asList("BTC", "ETH", "SOL", "XRP", "ADA", "DOGE", "AVAX", "NEAR", "LTC");

    private static final String BTN_HELP = "Help";
    private static final String BTN_BACK = "Back";

    private final String botToken;
    private final AuthUserModule authUserModule;
    private final CryptoInformationModule cryptoInformationModule;
    private final PortfolioManagementModule portfolioManagementModule;
    private final MessageHandlingModule messageHandlingModule;
    private final UserRepository userRepository;
    private final BingXService bingXService;
    private final FiatConversionService fiatConversionService;
    private final RabbitMQService rabbitMQService;
    private AlertsHandlingModule alertsHandlingModule;
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    public TelegramBotService(AuthUserModule authUserModule,
                              CryptoInformationModule cryptoInformationModule,
                              PortfolioManagementModule portfolioManagementModule,
                              MessageHandlingModule messageHandlingModule,
                              UserRepository userRepository,
                              BingXService bingXService,
                              FiatConversionService fiatConversionService,
                              RabbitMQService rabbitMQService) {
        this.botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        this.authUserModule = authUserModule;
        this.cryptoInformationModule = cryptoInformationModule;
        this.portfolioManagementModule = portfolioManagementModule;
        this.messageHandlingModule = messageHandlingModule;
        this.userRepository = userRepository;
        this.bingXService = bingXService;
        this.fiatConversionService = fiatConversionService;
        this.rabbitMQService = rabbitMQService;
    }

    /** Setter to avoid circular dependency with AlertsHandlingModule */
    public void setAlertsHandlingModule(AlertsHandlingModule alertsHandlingModule) {
        this.alertsHandlingModule = alertsHandlingModule;
    }

    @Override
    public String getBotUsername() {
        return "CryptoAdvisorBot";
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText().trim();
            String chatId = update.getMessage().getChatId().toString();
            String username = update.getMessage().getFrom().getUserName();

            handleCommand(chatId, messageText, username);
        }
    }

    private void handleCommand(String chatId, String text, String username) {
        String[] parts = text.split("\\s+");
        String command = parts[0];

        Optional<User> optionalUser = userRepository.findByChatId(chatId);
        if (optionalUser.isEmpty()) {
            if ("/start".equals(command)) {
                User newUser = authUserModule.registerUser(chatId, username);
                cryptoInformationModule.ensureDefaultTrackedCurrency(newUser.getId());
                rabbitMQService.logActivity(chatId, "User registered: " + (username != null ? username : "unknown"));
                sendMainMenu(chatId, "Welcome " + (username != null ? username : "") + "! Registration complete. Type /help for commands.");
            } else {
                sendMessage(chatId, "Please /start first.");
            }
            return;
        }

        User user = optionalUser.get();
        PendingCommand pending = pendingCommands.get(chatId);
        if (pending != null) {
            if (BTN_BACK.equals(text)) {
                pendingCommands.remove(chatId);
                sendMainMenu(chatId, "Action cancelled.");
                return;
            }
            if (text.startsWith("/")) {
                pendingCommands.remove(chatId);
            }else{
                switch (pending.action) {
                    case ADD_TRACKED_CHOOSE:
                        handlePendingAddTrackedChoose(chatId, user, text);
                        return;
                    case ADD_TRACKED_PRICE:
                        handlePendingAddTrackedPrice(chatId, user, text, pending.symbol);
                        return;
                    case REMOVE_TRACKED:
                        handlePendingRemoveTracked(chatId, user, text);
                        return;
                    case PORTFOLIO_ADD_CHOOSE_CRYPTO:
                        handlePortfolioAddChoose(chatId, user, text);
                        return;
                    case PORTFOLIO_ADD_AMOUNT:
                        handlePortfolioAddAmount(chatId, user, text, pending.symbol);
                        return;
                    case PORTFOLIO_REMOVE_CHOOSE_CRYPTO:
                        handlePortfolioRemoveChoose(chatId, user, text);
                        return;
                    case PORTFOLIO_REMOVE_AMOUNT:
                        handlePortfolioRemoveAmount(chatId, user, text, pending.symbol);
                        return;
                    case PORTFOLIO_HISTORY_PERIOD:
                        handlePortfolioHistoryPeriod(chatId, user, text);
                        return;
                    case SET_ALERT_CHOOSE:
                        handleSetAlertChoose(chatId, user, text);
                        return;
                    case SET_ALERT_TYPE:
                        handleSetAlertType(chatId, user, text, pending.symbol);
                        return;
                    case SET_ALERT_VALUE:
                        handleSetAlertValue(chatId, user, text, pending.symbol, pending.alertType);
                        return;
                    case DELETE_ALERT_CHOOSE:
                        handleDeleteAlertChoose(chatId, user, text);
                        return;
                    default:
                        break;
                }
            }
        }

        if (BTN_HELP.equals(text)) {
            sendHelp(chatId);
            return;
        }

        switch (command) {
            case "/start":
                sendMainMenu(chatId, "Welcome back, " + (username != null ? username : "") + "!");
                break;
            case "/set_fiat":
                if (parts.length > 1) {
                    handleFiatSelection(chatId, user, parts[1]);
                } else {
                    sendFiatSelection(chatId);
                }
                break;
            case "/current_fiat":
                sendCurrentFiat(chatId, user);
                break;
            case "/add_tracked_crypto":
                if (parts.length > 2) {
                    handleAddTrackedTyped(chatId, user, parts[1], parts[2]);
                } else {
                    sendAddTrackedSelection(chatId);
                }
                break;
            case "/remove_tracked_crypto":
                if (parts.length > 1) {
                    handleRemoveTrackedTyped(chatId, user, parts[1]);
                } else {
                    sendRemoveTrackedSelection(chatId, user);
                }
                break;
            case "/tracked":
                sendTrackedList(chatId, user);
                break;
            case "/price_crypto":
                if (parts.length > 1) {
                    String fiat = getUserFiat(user);
                    Double price = cryptoInformationModule
                    .getCurrentPrice(parts[1], fiat)
                    .block();

                    if (price == null || price <= 0) {
                        sendMessage(chatId, "Could not fetch price for " + parts[1]);
                    } else {
                        sendMessage(chatId,
                            "Current price of " + parts[1].toUpperCase() + ": "
                            + String.format("%.2f", price) + " " + fiat);
                    }
                } else {
                    sendMessage(chatId, "Usage: /price_crypto <symbol>");
                }
                break;
            case "/portfolio_add":
                sendPortfolioAddChooseCrypto(chatId);
                break;
            case "/portfolio_remove":
                handlePortfolioRemoveStart(chatId, user);
                break;
            case "/portfolio":
                handlePortfolioView(chatId, user);
                break;
            case "/llm_analyze":
                if (parts.length > 1) {
                    sendMessage(chatId, "Requesting investment analysis for " + parts[1] + "...");
                    String analysis = messageHandlingModule.analyzeCryptoInvestment(parts[1]).block();
                    sendMessage(chatId, analysis != null ? analysis : "No response received.");
                } else {
                    sendMessage(chatId, "Usage: /llm_analyze <symbol>");
                }
                break;
            case "/llm_portfolio":
                Map<String, Double> p = portfolioManagementModule.getPortfolio(user.getId());
                if (p.isEmpty()) {
                    sendMessage(chatId, "Your portfolio is empty.");
                } else {
                    String portfolioStr = p.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(", "));
                    sendMessage(chatId, "Reviewing your portfolio...");
                    String portfolioReview = messageHandlingModule.askOpenRouter("Review this cryptocurrency portfolio and suggest improvements: " + portfolioStr).block();
                    sendMessage(chatId, portfolioReview != null ? portfolioReview : "No response received.");
                }
                break;
            case "/llm_ask":
                if (parts.length > 1) {
                    String query = text.substring(command.length() + 1);
                    String answer = messageHandlingModule.askOpenRouter(query).block();
                    sendMessage(chatId, answer != null ? answer : "No response received.");
                } else {
                    sendMessage(chatId, "Usage: /llm_ask <your question>");
                }
                break;
            case "/portfolio_amount":
                handlePortfolioAmount(chatId, user);
                break;
            case "/portfolio_history":
                handlePortfolioHistoryStart(chatId, user);
                break;
            case "/portfolio_crypto_history":
                handlePortfolioCryptoHistory(chatId, user);
                break;
            case "/alerts":
                handleAlertsCommand(chatId, user);
                break;
            case "/set_alert":
                handleSetAlertStart(chatId, user);
                break;
            case "/alerts_list":
                handleAlertsList(chatId, user);
                break;
            case "/alerts_status":
                if (alertsHandlingModule != null) {
                    sendMessage(chatId, alertsHandlingModule.getActiveAlertsStatusString());
                } else {
                    sendMessage(chatId, "Alerts module not initialized.");
                }
                break;
            case "/delete_alert":
                handleDeleteAlertStart(chatId, user);
                break;
            case "/compare":
                if (parts.length > 2) {
                    String s1 = parts[1].toUpperCase();
                    String s2 = parts[2].toUpperCase();
                    String supported = String.join(", ", CRYPTO_SYMBOLS);

                    if (!isAllowedCrypto(s1) || !isAllowedCrypto(s2)) {
                        sendMessage(chatId, "Unsupported crypto. Supported: " + supported);
                        break;
                    }

                    String fiat = getUserFiat(user);
                    Double p1 = cryptoInformationModule.getCurrentPrice(s1, fiat).block();
                    Double p2 = cryptoInformationModule.getCurrentPrice(s2, fiat).block();

                    if (p1 == null || p1 <= 0 || p2 == null || p2 <= 0) {
                        sendMessage(chatId, "Could not fetch prices for " + s1 + " and/or " + s2 + ". Supported: " + supported);
                    } else {
                        sendMessage(chatId,
                                s1 + ": " + String.format("%.2f", p1) + " " + fiat + "\n" +
                                s2 + ": " + String.format("%.2f", p2) + " " + fiat + "\nRatio: " + String.format("%.2f", p1 / p2));
                    }
                } else {
                    sendMessage(chatId, "Usage: /compare <symbol1> <symbol2>");
                }
                break;
            case "/price_history":
                if (parts.length > 2) {
                    String s = parts[1].toUpperCase();
                    String supported = String.join(", ", CRYPTO_SYMBOLS);
                    if (!isAllowedCrypto(s)) {
                        sendMessage(chatId, "Unsupported crypto. Supported: " + supported);
                        break;
                    }
                    int days;
                    try {
                        days = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        sendMessage(chatId, "Days must be a positive integer. Supported cryptocurrencies: " + supported);
                        break;
                    }
                    if (days < 1) {
                        sendMessage(chatId, "Days must be at least 1. Supported cryptocurrencies: " + supported);
                        break;
                    }
                    List<Double> prices = bingXService.getHistory(s, "1d", days).block();
                    if (prices == null || prices.isEmpty()) {
                        sendMessage(chatId, "Price history unavailable for " + s + ". Supported: " + supported);
                    } else {
                        String fiat = getUserFiat(user);
                        Double rate = fiatConversionService.getFiatRate("USD", fiat).block();
                        if (rate == null) rate = 1.0;
                        StringBuilder sb = new StringBuilder("Price history for " + s + ":\n");
                        for (int i = 0; i < prices.size(); i++) {
                            double converted = prices.get(i) * rate;
                            String arrow = "";
                            if (i > 0) {
                                double prev = prices.get(i-1) * rate;
                                if (converted > prev) {
                                    arrow = " ↑";
                                } else if (converted < prev) {
                                    arrow = " ↓";
                                }
                            }
                            sb.append("Day ").append(i + 1).append(": ").append(String.format("%.2f", converted)).append(" ").append(fiat).append(arrow).append("\n");
                        }
                        sendMessage(chatId, sb.toString().trim());
                    }
                } else {
                    sendMessage(chatId, "Usage: /price_history <symbol> <days>");
                }
                break;
            case "/help":
                sendHelp(chatId);
                break;
            default:
                if (isAllowedFiat(text)) {
                    handleFiatSelection(chatId, user, text);
                } else {
                    sendMessage(chatId, "Unknown command. Type /help for list of commands.");
                }
        }
    }

    private void handleFiatSelection(String chatId, User user, String fiatSymbol) {
        String normalized = fiatSymbol.toUpperCase();
        if (!isAllowedFiat(normalized)) {
            sendMessage(chatId, "Unsupported fiat currency. Supported: " + String.join(", ", FIAT_SYMBOLS));
            return;
        }

        String currentFiat = getUserFiat(user);
        if (normalized.equals(currentFiat)) {
            sendMainMenu(chatId, "Your fiat currency is already set to " + normalized + ".");
            return;
        }

        if (userRepository.updateFiat(user.getId(), normalized)) {
            sendMainMenu(chatId, "Fiat currency set to " + normalized + ".");
        } else {
            sendMainMenu(chatId, "Failed to set fiat currency. Please try again.");
        }
    }

    private void sendFiatSelection(String chatId) {
        ReplyKeyboardMarkup keyboard = createKeyboard(FIAT_SYMBOLS, 3);
        sendMessage(chatId, "Choose your fiat currency:", keyboard);
    }

    private void handleAddTrackedTyped(String chatId, User user, String symbol, String targetPriceText) {
        String normalized = symbol.toUpperCase();
        if (!isAllowedCrypto(normalized)) {
            sendMessage(chatId, "Unsupported crypto. Supported: " + String.join(", ", CRYPTO_SYMBOLS));
            return;
        }
        try {
            double targetPrice = Double.parseDouble(targetPriceText);
            if (targetPrice < 0) {
                sendMessage(chatId, "Price cannot be negative. Please use a non-negative value.");
                return;
            }
            if (!cryptoInformationModule.addTrackedCurrency(user.getId(), normalized, targetPrice, getUserFiat(user))) {
                sendMessage(chatId, "Currency " + normalized + " is already tracked or unsupported.");
            } else {
                sendMessage(chatId, "Added " + normalized + " to watchlist with target price " + targetPriceText + " " + getUserFiat(user) + ".");
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Please enter a valid numeric price. Usage: /add_tracked_crypto <symbol> <target_price>");
        }
    }

    private void sendAddTrackedSelection(String chatId) {
        ReplyKeyboardMarkup keyboard = createKeyboard(CRYPTO_SYMBOLS, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.ADD_TRACKED_CHOOSE, null));
        sendMessage(chatId, "Choose a crypto to add from the list:", keyboard);
    }

    private void handlePendingAddTrackedChoose(String chatId, User user, String text) {
        String normalized = text.toUpperCase();
        if (!isAllowedCrypto(normalized)) {
            sendMessage(chatId, "Please choose a crypto from the list: " + String.join(", ", CRYPTO_SYMBOLS));
            return;
        }
        List<String> tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        if (tracked.contains(normalized)) {
            sendMessage(chatId, "Currency " + normalized + " is already tracked.");
            pendingCommands.remove(chatId);
            return;
        }
        pendingCommands.put(chatId, new PendingCommand(PendingAction.ADD_TRACKED_PRICE, normalized));
        String fiatTrk = getUserFiat(user);
        Double curPrice = cryptoInformationModule.getCurrentPrice(normalized, fiatTrk).block();
        String priceInfo = (curPrice != null && curPrice > 0)
                ? "Current price of " + normalized + ": " + String.format("%.2f", curPrice) + " " + fiatTrk + "\n"
                : "";
        sendMessage(chatId, priceInfo + "Enter the target price for " + normalized + " in " + fiatTrk + " (enter 0 to skip custom price alert):");
    }

    private void handlePendingAddTrackedPrice(String chatId, User user, String text, String symbol) {
        double targetPrice;
        try {
            targetPrice = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Please enter a valid number for " + symbol + " price (or press Back to cancel).");
            return; 
        }

        if (targetPrice < 0) {
            sendMessage(chatId, "Price cannot be negative. Please try again or press Back.");
            return; 
        }

        pendingCommands.remove(chatId);

        try {
            if (cryptoInformationModule.addTrackedCurrency(user.getId(), symbol, targetPrice, getUserFiat(user))) {
                sendMainMenu(chatId, "Added " + symbol + " to watchlist. Every 24h you will receive notifications for 5% changes.");
                if (targetPrice > 0) {
                    sendMessage(chatId, "Custom price alert for " + symbol + " created with target " + targetPrice + " " + getUserFiat(user) + ".");
                }
            } else {
                sendMainMenu(chatId, "Could not add " + symbol + ". It might already be tracked.");
            }
        } catch (Exception e) {
            sendMainMenu(chatId, "Error adding " + symbol + ": " + e.getMessage());
        }
    }

    private void handleRemoveTrackedTyped(String chatId, User user, String symbol) {
        String normalized = symbol.toUpperCase();
        List<String> tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        if (!tracked.contains(normalized)) {
            sendMessage(chatId, "Currency " + normalized + " is not currently tracked.");
            return;
        }
        if (cryptoInformationModule.removeTrackedCurrency(user.getId(), normalized)) {
            ensureDefaultTrackedAfterRemoval(user, chatId);
        } else {
            sendMessage(chatId, "Failed to remove " + normalized + ".");
        }
    }

    private void sendRemoveTrackedSelection(String chatId, User user) {
        List<String> tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        if (tracked.isEmpty()) {
            cryptoInformationModule.ensureDefaultTrackedCurrency(user.getId());
            tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        }

        ReplyKeyboardMarkup keyboard = createKeyboard(tracked, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.REMOVE_TRACKED, null));
        sendMessage(chatId, "Choose a tracked crypto to remove:", keyboard);
    }

    private void handlePendingRemoveTracked(String chatId, User user, String text) {
        String normalized = text.toUpperCase();
        List<String> tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        if (!tracked.contains(normalized)) {
            sendMessage(chatId, "Please choose a tracked crypto from the list.");
            return;
        }
        if (cryptoInformationModule.removeTrackedCurrency(user.getId(), normalized)) {
            ensureDefaultTrackedAfterRemoval(user, chatId);
        } else {
            sendMessage(chatId, "Failed to remove " + normalized + ".");
        }
        pendingCommands.remove(chatId);
    }

    private void ensureDefaultTrackedAfterRemoval(User user, String chatId) {
        List<String> trackedAfter = cryptoInformationModule.getTrackedCurrencies(user.getId());
        if (trackedAfter.isEmpty()) {
            cryptoInformationModule.ensureDefaultTrackedCurrency(user.getId());
            sendMainMenu(chatId, "No tracked cryptocurrencies left. Default BTC has been added to your watchlist.");
        } else {
            sendMainMenu(chatId, "Tracked crypto removed. Current tracked: " + String.join(", ", trackedAfter));
        }
    }

    private void sendTrackedList(String chatId, User user) {
        List<String> tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        if (tracked.isEmpty()) {
            cryptoInformationModule.ensureDefaultTrackedCurrency(user.getId());
            tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        }
        String fiat = getUserFiat(user);

        List<String> lines = new ArrayList<>();
        for (String symbol : tracked) {
            Double price = cryptoInformationModule.getCurrentPrice(symbol, fiat).block();
            if (price != null && price > 0) {
                lines.add(symbol + ": " + String.format("%.2f", price) + " " + fiat);
            } else {
                lines.add(symbol + ": price unavailable");
            }
        }

        sendMessage(chatId, "Tracked cryptocurrencies:\n" + String.join("\n", lines));
    }

    // ===================== PORTFOLIO: Interactive Add =====================

    private void sendPortfolioAddChooseCrypto(String chatId) {
        ReplyKeyboardMarkup keyboard = createKeyboard(CRYPTO_SYMBOLS, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_ADD_CHOOSE_CRYPTO, null));
        sendMessage(chatId, "Choose a cryptocurrency to add to your portfolio:", keyboard);
    }

    private void handlePortfolioAddChoose(String chatId, User user, String text) {
        String symbol = text.toUpperCase();
        if (!isAllowedCrypto(symbol)) {
            sendMessage(chatId, "Please choose from the list: " + String.join(", ", CRYPTO_SYMBOLS));
            return;
        }
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_ADD_AMOUNT, symbol));
        sendMessage(chatId, "Enter the number of " + symbol + " coins to add:");
    }

    private void handlePortfolioAddAmount(String chatId, User user, String text, String symbol) {
        double amount;
        try {
            amount = Double.parseDouble(text);
            if (amount <= 0) {
                sendMessage(chatId, "Amount must be a positive number.");
                pendingCommands.remove(chatId);
                return;
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Invalid input. Please enter a positive number.");
            pendingCommands.remove(chatId);
            return;
        }
        pendingCommands.remove(chatId);
        Double priceUsd = cryptoInformationModule.getCurrentPrice(symbol, "USD").block();
        if (priceUsd == null || priceUsd <= 0) {
            sendMessage(chatId, "Could not fetch current price for " + symbol + ". Please try again later.");
            return;
        }
        try {
            portfolioManagementModule.addAsset(user.getId(), symbol, amount, priceUsd);
            sendMessage(chatId, "Added " + amount + " " + symbol + " at $" + String.format("%.2f", priceUsd) + " per coin.");
            handlePortfolioView(chatId, user);
        } catch (Exception e) {
            sendMessage(chatId, "Error adding asset: " + e.getMessage());
        }
    }

    // ===================== PORTFOLIO: Interactive Remove =====================

    private void handlePortfolioRemoveStart(String chatId, User user) {
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (portfolio.isEmpty()) {
            sendMessage(chatId, "Your portfolio is empty.");
            return;
        }
        List<String> symbols = new ArrayList<>(portfolio.keySet());
        ReplyKeyboardMarkup keyboard = createKeyboard(symbols, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_REMOVE_CHOOSE_CRYPTO, null));
        sendMessage(chatId, "Choose a cryptocurrency to remove from your portfolio:", keyboard);
    }

    private void handlePortfolioRemoveChoose(String chatId, User user, String text) {
        String symbol = text.toUpperCase();
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (!portfolio.containsKey(symbol)) {
            sendMessage(chatId, "This crypto is not in your portfolio.");
            return;
        }
        double currentAmount = portfolio.get(symbol);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_REMOVE_AMOUNT, symbol));
        sendMessage(chatId, "You have " + currentAmount + " " + symbol + ". How many coins to remove?");
    }

    private void handlePortfolioRemoveAmount(String chatId, User user, String text, String symbol) {
        double amountToRemove;
        try {
            amountToRemove = Double.parseDouble(text);
            if (amountToRemove <= 0) {
                sendMessage(chatId, "Amount must be a positive number.");
                pendingCommands.remove(chatId);
                return;
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Invalid input. Please enter a positive number.");
            pendingCommands.remove(chatId);
            return;
        }
        pendingCommands.remove(chatId);
        Double priceUsd = cryptoInformationModule.getCurrentPrice(symbol, "USD").block();
        if (priceUsd == null || priceUsd <= 0) {
            sendMessage(chatId, "Could not fetch current price. Removal cancelled.");
            return;
        }
        try {
            portfolioManagementModule.removeAsset(user.getId(), symbol, amountToRemove, priceUsd);
            sendMessage(chatId, "Removed " + amountToRemove + " " + symbol + " from portfolio.");
            handlePortfolioView(chatId, user);
        } catch (Exception e) {
            sendMessage(chatId, "Error: " + e.getMessage());
        }
    }

    // ===================== PORTFOLIO: View with prices =====================

    private void handlePortfolioView(String chatId, User user) {
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (portfolio.isEmpty()) {
            sendMessage(chatId, "Your portfolio is empty.");
            return;
        }
        String fiat = getUserFiat(user);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Double> entry : portfolio.entrySet()) {
            String symbol = entry.getKey();
            double amount = entry.getValue();
            Double price = cryptoInformationModule.getCurrentPrice(symbol, fiat).block();
            if (price != null && price > 0) {
                double cost = price * amount;
                lines.add(String.format("%s: %.4f × %.2f %s = %.2f %s", symbol, amount, price, fiat, cost, fiat));
            } else {
                lines.add(symbol + ": " + amount + " coins, price unavailable");
            }
        }
        sendMessage(chatId, "Your portfolio:\n" + String.join("\n", lines));
    }

    // ===================== PORTFOLIO: Total amount =====================

    private void handlePortfolioAmount(String chatId, User user) {
        Map<String, Double> port = portfolioManagementModule.getPortfolio(user.getId());
        if (port.isEmpty()) {
            sendMessage(chatId, "Your portfolio is empty.");
            return;
        }
        String fiat = getUserFiat(user);
        double total = 0;
        for (Map.Entry<String, Double> e : port.entrySet()) {
            Double price = cryptoInformationModule.getCurrentPrice(e.getKey(), fiat).block();
            if (price != null) {
                total += price * e.getValue();
            }
        }
        sendMessage(chatId, "Total portfolio value: " + String.format("%.2f", total) + " " + fiat);
    }

    // ===================== PORTFOLIO: History over period =====================

    private void handlePortfolioHistoryStart(String chatId, User user) {
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (portfolio.isEmpty()) {
            sendMessage(chatId, "Your portfolio is empty.");
            return;
        }
        List<String> periods = Arrays.asList("1 day", "1 month", "1 year");
        ReplyKeyboardMarkup keyboard = createKeyboard(periods, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_HISTORY_PERIOD, null));
        sendMessage(chatId, "Choose a period for portfolio value change:", keyboard);
    }

    private void handlePortfolioHistoryPeriod(String chatId, User user, String text) {
        int days;
        switch (text) {
            case "1 day": days = 1; break;
            case "1 month": days = 30; break;
            case "1 year": days = 365; break;
            default:
                sendMessage(chatId, "Please choose a period from the buttons.");
                return;
        }
        pendingCommands.remove(chatId);
        String fiat = getUserFiat(user);
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        Map<String, Double> firstPrices = portfolioManagementModule.getFirstPurchasePrices(user.getId());
        Map<String, java.time.LocalDateTime> firstDates = portfolioManagementModule.getFirstPurchaseDates(user.getId());

        double currentTotal = 0;
        for (Map.Entry<String, Double> e : portfolio.entrySet()) {
            Double price = cryptoInformationModule.getCurrentPrice(e.getKey(), fiat).block();
            if (price != null) currentTotal += price * e.getValue();
        }

        double historicalTotal = 0;
        Double fiatRate = fiatConversionService.getFiatRate("USD", fiat).block();
        if (fiatRate == null) fiatRate = 1.0;

        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        for (Map.Entry<String, Double> e : portfolio.entrySet()) {
            String symbol = e.getKey();
            double amount = e.getValue();
            Double firstPriceUsd = firstPrices.get(symbol);
            java.time.LocalDateTime firstDate = firstDates.get(symbol);

            boolean useFirstPurchase = false;
            if (firstDate != null) {
                long daysSincePurchase = java.time.Duration.between(firstDate, now).toDays();
                if (daysSincePurchase < days) {
                    useFirstPurchase = true;
                }
            }

            if (useFirstPurchase && firstPriceUsd != null) {
                historicalTotal += firstPriceUsd * fiatRate * amount;
            } else {
                List<Double> klinePrices = bingXService.getHistory(symbol, "1d", days).block();
                if (klinePrices != null && !klinePrices.isEmpty()) {
                    historicalTotal += klinePrices.get(0) * fiatRate * amount;
                } else if (firstPriceUsd != null) {
                    historicalTotal += firstPriceUsd * fiatRate * amount;
                }
            }
        }

        double diff = currentTotal - historicalTotal;
        double percent = historicalTotal != 0 ? (diff / historicalTotal) * 100 : 0;
        String msg = String.format("Portfolio change for %s:\nCurrent value: %.2f %s\nValue %d days ago: %.2f %s\nDifference: %.2f %s (%.2f%%)",
                text, currentTotal, fiat, days, historicalTotal, fiat, diff, fiat, percent);
        sendMessage(chatId, msg);
    }

    // ===================== PORTFOLIO: Per-crypto history since first purchase =====================

    private void handlePortfolioCryptoHistory(String chatId, User user) {
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (portfolio.isEmpty()) {
            sendMessage(chatId, "Your portfolio is empty.");
            return;
        }
        String fiat = getUserFiat(user);
        Map<String, Double> firstPrices = portfolioManagementModule.getFirstPurchasePrices(user.getId());

        List<String> lines = new ArrayList<>();
        for (String symbol : portfolio.keySet()) {
            Double firstPriceUsd = firstPrices.get(symbol);
            if (firstPriceUsd == null) {
                lines.add(symbol + ": no purchase history data");
                continue;
            }
            Double currentPriceUsd = cryptoInformationModule.getCurrentPrice(symbol, "USD").block();
            Double currentPriceFiat = cryptoInformationModule.getCurrentPrice(symbol, fiat).block();
            if (currentPriceUsd == null || currentPriceUsd <= 0 || currentPriceFiat == null || currentPriceFiat <= 0) {
                lines.add(symbol + ": price unavailable");
                continue;
            }
            double diffUsd = currentPriceUsd - firstPriceUsd;
            double percent = firstPriceUsd != 0 ? (diffUsd / firstPriceUsd) * 100 : 0;
            lines.add(String.format("%s: bought at %.2f USD, now %.2f %s (%.2f USD), change %+.2f%%",
                    symbol, firstPriceUsd, currentPriceFiat, fiat, currentPriceUsd, percent));
        }
        sendMessage(chatId, "Per-crypto change since first purchase:\n" + String.join("\n", lines));
    }

    // ===================== ALERTS COMMANDS =====================

    private void handleAlertsCommand(String chatId, User user) {
        if (alertsHandlingModule == null) {
            sendMessage(chatId, "Alerts module is not available.");
            return;
        }
        List<String> alerts = alertsHandlingModule.getRecentAlerts(user.getId());
        if (alerts.isEmpty()) {
            sendMessage(chatId, "No alerts in the last 7 days.");
        } else {
            sendMessage(chatId, "Recent alerts (last 7 days):\n" + String.join("\n", alerts));
        }
    }

    private void handleAlertsList(String chatId, User user) {
        List<CryptoInformationModule.UserAlertInfo> alerts = cryptoInformationModule.getUserAlerts(user.getId());
        if (alerts.isEmpty()) {
            sendMessage(chatId, "You have no active custom alerts.");
            return;
        }

        String userFiat = getUserFiat(user);
        StringBuilder sb = new StringBuilder("Your active custom alerts:\n");
        for (CryptoInformationModule.UserAlertInfo alert : alerts) {
            String formattedTarget = formatAlertTargetValue(alert, userFiat);
            sb.append(String.format(java.util.Locale.US, "ID: %d | %s | %s | Target: %s\n",
                alert.getId(), alert.getSymbol(), alert.getType(), formattedTarget));
        }
        sendMessage(chatId, sb.toString());
    }

    private String formatAlertTargetValue(CryptoInformationModule.UserAlertInfo alert, String userFiat) {
        if (alert.getType().equalsIgnoreCase("PERCENT")) {
            return String.format(java.util.Locale.US, "%.4f%%", alert.getTargetValue());
        }

        String alertFiat = alert.getFiatSymbol() != null ? alert.getFiatSymbol().toUpperCase() : "USD";
        double targetValue = alert.getTargetValue() != null ? alert.getTargetValue() : 0.0;
        if (!userFiat.equalsIgnoreCase(alertFiat)) {
            Double rate = fiatConversionService.getFiatRate(alertFiat, userFiat).block();
            if (rate != null && rate > 0) {
                targetValue = targetValue * rate;
            }
        }
        return String.format(java.util.Locale.US, "%.4f %s", targetValue, userFiat);
    }

    private void handleDeleteAlertStart(String chatId, User user) {
        List<CryptoInformationModule.UserAlertInfo> alerts = cryptoInformationModule.getUserAlerts(user.getId());
        if (alerts.isEmpty()) {
            sendMessage(chatId, "You have no active custom alerts to delete.");
            return;
        }
        List<String> buttons = alerts.stream().map(a -> String.valueOf(a.getId())).collect(Collectors.toList());
        ReplyKeyboardMarkup keyboard = createKeyboard(buttons, 4);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.DELETE_ALERT_CHOOSE, null));
        sendMessage(chatId, "Choose the ID of the alert to delete:", keyboard);
    }

    private void handleDeleteAlertChoose(String chatId, User user, String text) {
        try {
            Integer alertId = Integer.parseInt(text);
            if (cryptoInformationModule.removeUserAlert(user.getId(), alertId)) {
                sendMainMenu(chatId, "Alert ID " + alertId + " deleted successfully.");
            } else {
                sendMessage(chatId, "Alert not found or could not be deleted.");
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Invalid ID. Please enter a numeric ID.");
        }
        pendingCommands.remove(chatId);
    }

    private void handleSetAlertStart(String chatId, User user) {
        ReplyKeyboardMarkup keyboard = createKeyboard(CRYPTO_SYMBOLS, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.SET_ALERT_CHOOSE, null));
        sendMessage(chatId, "Choose a crypto to create an alert for:", keyboard);
    }

    private void handleSetAlertChoose(String chatId, User user, String text) {
        String normalized = text.toUpperCase();
        if (!isAllowedCrypto(normalized)) {
            sendMessage(chatId, "Please choose a valid crypto from the list.");
            return;
        }
        
        List<String> options = Arrays.asList("PRICE", "PERCENT");
        ReplyKeyboardMarkup keyboard = createKeyboard(options, 2);
        
        PendingCommand next = new PendingCommand(PendingAction.SET_ALERT_TYPE, normalized);
        pendingCommands.put(chatId, next);
        
        sendMessage(chatId, "Choose alert type:\nPRICE - trigger when price crosses target.\nPERCENT - trigger when price changes by % from current.", keyboard);
    }

    private void handleSetAlertType(String chatId, User user, String text, String symbol) {
        String type = text.toUpperCase();
        if (!type.equals("PRICE") && !type.equals("PERCENT")) {
            sendMessage(chatId, "Please choose PRICE or PERCENT.");
            return;
        }
        
        PendingCommand next = new PendingCommand(PendingAction.SET_ALERT_VALUE, symbol);
        next.alertType = type;
        pendingCommands.put(chatId, next);
        
        String fiat = getUserFiat(user);
        Double curPrice = cryptoInformationModule.getCurrentPrice(symbol, fiat).block();
        String priceInfo = (curPrice != null && curPrice > 0)
                ? "Current price: " + String.format("%.2f", curPrice) + " " + fiat + "\n"
                : "";

        if (type.equals("PRICE")) {
            sendMessage(chatId, priceInfo + "Enter the target price for " + symbol + " in " + fiat + ":");
        } else {
            sendMessage(chatId, priceInfo + "Enter the percentage change (e.g. 5.5) for " + symbol + ":");
        }
    }

    private void handleSetAlertValue(String chatId, User user, String text, String symbol, String type) {
        double targetValue;
        try {
            targetValue = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Invalid number format. Please enter a valid number (or press Back to cancel).");
            return; 
        }

        if (targetValue <= 0) {
            sendMessage(chatId, "Value must be positive. Please try again or press Back.");
            return; 
        }

        pendingCommands.remove(chatId);

        sendMessage(chatId, "Creating " + type + " alert for " + symbol + " with target " 
                + targetValue + (type.equals("PERCENT") ? "%" : " " + getUserFiat(user)) 
                + "... Please wait.");

        String fiat = getUserFiat(user);
        try {
            Integer alertId = cryptoInformationModule.addUserAlert(user.getId(), symbol, type, targetValue, fiat);
            sendMainMenu(chatId, "Alert #" + alertId + " created successfully.");
        } catch (Exception e) {
            log.error("Failed to create alert for user {}", user.getId(), e);
            sendMainMenu(chatId, "Failed to create alert: " + e.getMessage());
        }
    }

    private void sendCurrentFiat(String chatId, User user) {
        sendMessage(chatId, "Current fiat currency: " + getUserFiat(user));
    }

    private String getUserFiat(User user) {
        return userRepository.getFiatSymbolByUserId(user.getId()).orElse("USD");
    }

    private boolean isAllowedFiat(String symbol) {
        return FIAT_SYMBOLS.contains(symbol.toUpperCase());
    }

    private boolean isAllowedCrypto(String symbol) {
        return CRYPTO_SYMBOLS.contains(symbol.toUpperCase());
    }

    private void sendHelp(String chatId) {
        sendMessage(chatId, "Available commands:\n" +
                "/start - Register\n" +
                "/set_fiat - Choose fiat currency\n" +
                "/current_fiat - Show current fiat\n" +
                "/add_tracked_crypto - Add tracked crypto\n" +
                "/remove_tracked_crypto - Remove tracked crypto\n" +
                "/tracked - View tracked cryptos\n" +
                "/price_crypto <symbol> - Get current price\n" +
                "/portfolio_add - Add asset to portfolio\n" +
                "/portfolio_remove - Remove asset from portfolio\n" +
                "/portfolio - View portfolio with prices\n" +
                "/portfolio_amount - View total portfolio value\n" +
                "/portfolio_history - Portfolio value change over period\n" +
                "/portfolio_crypto_history - Per-crypto change since purchase\n" +
                "/set_alert - Create a custom alert (Price or Percent)\n" +
                "/alerts_list - View your active custom alerts\n" +
                "/delete_alert - Delete a custom alert\n" +
                "/alerts - View recent alert history (last 7 days)\n" +
                "/compare <symbol1> <symbol2> - Compare two cryptos\n" +
                "/price_history <symbol> <days> - View price history\n" +
                "/llm_analyze <symbol> - LLM investment analysis\n" +
                "/llm_portfolio - LLM portfolio review\n" +
                "/llm_ask <question> - Ask the LLM about crypto");
    }

    private ReplyKeyboardMarkup createKeyboard(List<String> buttons, int rowSize) {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        for (int i = 0; i < buttons.size(); i++) {
            row.add(new KeyboardButton(buttons.get(i)));
            if ((i + 1) % rowSize == 0 && i + 1 < buttons.size()) {
                keyboard.add(row);
                row = new KeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            keyboard.add(row);
        }
        KeyboardRow backRow = new KeyboardRow();
        backRow.add(new KeyboardButton(BTN_BACK));
        keyboard.add(backRow);
        
        markup.setKeyboard(keyboard);
        markup.setResizeKeyboard(true);
        markup.setOneTimeKeyboard(true);
        return markup;
    }

    private ReplyKeyboardMarkup createPersistentMenu() {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add(new KeyboardButton(BTN_HELP));
        rows.add(row);
        markup.setKeyboard(rows);
        markup.setResizeKeyboard(true);
        markup.setOneTimeKeyboard(false); // постоянная кнопка
        return markup;
    }

    private void sendMainMenu(String chatId, String text) {
        sendMessage(chatId, text, createPersistentMenu());
    }

    private void sendMessage(String chatId, String text, ReplyKeyboardMarkup markup) {
        final int MAX_LENGTH = 4000;
        if (text.length() <= MAX_LENGTH) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId);
            message.setText(text);
            message.setReplyMarkup(markup);
            try {
                execute(message);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        } else {
            // Split into parts
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + MAX_LENGTH, text.length());
                String part = text.substring(start, end);
                SendMessage message = new SendMessage();
                message.setChatId(chatId);
                message.setText(part);
                if (start == 0) {
                    message.setReplyMarkup(markup);
                }
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
                start = end;
            }
        }
    }

    public void sendMessage(String chatId, String text) {
        sendMessage(chatId, text, null);
    }

    @Override
    public void afterPropertiesSet() {
        new Thread(() -> {
            try {
                TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try {
                        botsApi.registerBot(this);
                        log.info("Telegram bot registered successfully on attempt {}", attempt);
                        return;
                    } catch (TelegramApiException e) {
                        log.warn("Failed to register Telegram bot (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                        if (attempt == MAX_RETRIES) {
                            log.error("Final attempt to register bot failed", e);
                        }
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            } catch (TelegramApiException e) {
                log.error("Critical error during Telegram API initialization", e);
            }
        }).start();
    }

    private enum PendingAction {
        ADD_TRACKED_CHOOSE,
        ADD_TRACKED_PRICE,
        REMOVE_TRACKED,
        PORTFOLIO_ADD_CHOOSE_CRYPTO,
        PORTFOLIO_ADD_AMOUNT,
        PORTFOLIO_REMOVE_CHOOSE_CRYPTO,
        PORTFOLIO_REMOVE_AMOUNT,
        PORTFOLIO_HISTORY_PERIOD,
        SET_ALERT_CHOOSE,
        SET_ALERT_TYPE,
        SET_ALERT_VALUE,
        DELETE_ALERT_CHOOSE
    }

    private static class PendingCommand {
        private final PendingAction action;
        private final String symbol;
        public String alertType;

        public PendingCommand(PendingAction action, String symbol) {
            this.action = action;
            this.symbol = symbol;
        }
    }
}
