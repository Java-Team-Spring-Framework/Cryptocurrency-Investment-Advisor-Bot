package ru.spbstu.cryptoadvisor.controller;

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

import ru.spbstu.cryptoadvisor.service.AlertsHandlingModule;
import ru.spbstu.cryptoadvisor.service.BingXService;
import ru.spbstu.cryptoadvisor.service.RabbitMQService;
import ru.spbstu.cryptoadvisor.service.AuthUserModule;
import ru.spbstu.cryptoadvisor.service.CryptoInformationModule;
import ru.spbstu.cryptoadvisor.service.FiatConversionService;
import ru.spbstu.cryptoadvisor.service.LLMPromptBuilder;
import ru.spbstu.cryptoadvisor.service.MessageHandlingModule;
import ru.spbstu.cryptoadvisor.service.PortfolioManagementModule;
import ru.spbstu.cryptoadvisor.model.User;
import ru.spbstu.cryptoadvisor.dto.UserAlertInfo;
import ru.spbstu.cryptoadvisor.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDateTime;
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

    private static final String BTN_SET_FIAT = "Choose fiat currency";
    private static final String BTN_CURRENT_FIAT = "Show current fiat";
    private static final String BTN_ADD_CRYPTO = "Add tracked crypto";
    private static final String BTN_REMOVE_CRYPTO = "Remove tracked crypto";
    private static final String BTN_TRACKED = "Current tracked crypto";
    private static final String BTN_BACK = "Back";

    private final String botToken;
    private final AuthUserModule authUserModule;
    private final CryptoInformationModule cryptoInformationModule;
    private final PortfolioManagementModule portfolioManagementModule;
    private final MessageHandlingModule messageHandlingModule;
    private final LLMPromptBuilder llmPromptBuilder;
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
                              LLMPromptBuilder llmPromptBuilder,
                              UserRepository userRepository,
                              BingXService bingXService,
                              FiatConversionService fiatConversionService,
                              RabbitMQService rabbitMQService) {
        this.botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        this.authUserModule = authUserModule;
        this.cryptoInformationModule = cryptoInformationModule;
        this.portfolioManagementModule = portfolioManagementModule;
        this.messageHandlingModule = messageHandlingModule;
        this.llmPromptBuilder = llmPromptBuilder;
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
                sendMessage(chatId, "🚀 Welcome to CryptoBot!\n" +
                        "🤖 I am your personal assistant in the world of cryptocurrencies. I help you track the crypto market, analyze assets, and make investment decisions.\n" +
                        "💡 With my help, you can:\n" +
                        "🔹 Get current prices for BTC, ETH, SOL, XRP, ADA, DOGE, AVAX, NEAR, LTC in various currencies.\n" +
                        "🔹 Track cryptocurrency prices and receive notifications about changes.\n" +
                        "🔹 Compare exchange rates of different assets.\n" +
                        "🔹 Manage your portfolio: add and remove cryptocurrencies, as well as track changes in the value of your portfolio and assets.\n" +
                        "📌 To see the list of available features, use the /help command.");
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
                sendHelp(chatId);
                return;
            }
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
                case PRICE_HISTORY_CHOOSE_CRYPTO:
                    handlePriceHistoryChooseCrypto(chatId, user, text);
                    return;
                case PRICE_HISTORY_PERIOD:
                    handlePriceHistoryPeriod(chatId, user, text, pending.symbol);
                    return;
                case PRICE_CRYPTO_CHOOSE:
                    handlePriceCryptoChoose(chatId, user, text);
                    return;
                case COMPARE_CHOOSE_FIRST:
                    handleCompareChooseFirst(chatId, user, text);
                    return;
                case COMPARE_CHOOSE_SECOND:
                    handleCompareChooseSecond(chatId, user, text, pending.symbol);
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

        switch (text) {
            case BTN_SET_FIAT:
                sendFiatSelection(chatId);
                return;
            case BTN_CURRENT_FIAT:
                sendCurrentFiat(chatId, user);
                return;
            case BTN_ADD_CRYPTO:
                sendAddTrackedSelection(chatId);
                return;
            case BTN_REMOVE_CRYPTO:
                sendRemoveTrackedSelection(chatId, user);
                return;
            case BTN_TRACKED:
                sendTrackedList(chatId, user);
                return;
            case BTN_BACK:
                sendHelp(chatId);
                return;
        }

        switch (command) {
            case "/start":
                sendMessage(chatId, "🚀 Welcome to CryptoBot!\n" +
                        "🤖 I am your personal assistant in the world of cryptocurrencies. I help you track the crypto market, analyze assets, and make investment decisions.\n" +
                        "💡 With my help, you can:\n" +
                        "🔹 Get current prices for BTC, ETH, SOL, XRP, ADA, DOGE, AVAX, NEAR, LTC in various currencies.\n" +
                        "🔹 Track cryptocurrency prices and receive notifications about changes.\n" +
                        "🔹 Compare exchange rates of different assets.\n" +
                        "🔹 Manage your portfolio: add and remove cryptocurrencies, as well as track changes in the value of your portfolio and assets.\n" +
                        "📌 To see the list of available features, use the /help command.");
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
                        sendMessage(chatId, "Could not fetch price for " + parts[1] + ".");
                    } else {
                        sendMessage(chatId,
                            "📊 View current price:\n" +
                            "🎯 " + parts[1].toUpperCase() + ": "
                            + String.format("%.2f", price) + " " + fiat);
                    }
                } else {
                    sendPriceCryptoChooseCrypto(chatId, user);
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
            // LLM commands
            case "/llm_analyze":
                if (parts.length > 1) {
                    String symbol = parts[1].toUpperCase();
                    String fiat = getUserFiat(user);
                    sendMessage(chatId, "Requesting investment analysis for " + symbol + "...");
                    Double currentPrice = getCurrentPriceForAnalysis(symbol, fiat);
                    String priceHistorySummary = buildSymbolPriceHistorySummary(symbol, fiat, currentPrice);
                    String prompt = llmPromptBuilder.buildAnalyzeCryptoInvestmentPrompt(symbol, fiat, currentPrice, priceHistorySummary);
                    String analysis = messageHandlingModule.sendPrompt(prompt).block();
                    sendMessage(chatId, analysis != null ? analysis : "No response received.");
                } else {
                    sendMessage(chatId, "Usage: /llm_analyze <symbol>");
                }
                break;
            case "/llm_portfolio":
                {
                    String fiat = getUserFiat(user);
                    Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
                    if (portfolio.isEmpty()) {
                        sendMessage(chatId, "Your portfolio is empty.");
                        break;
                    }
                    Map<String, Double> firstPrices = portfolioManagementModule.getFirstPurchasePrices(user.getId());
                    Map<String, LocalDateTime> firstDates = portfolioManagementModule.getFirstPurchaseDates(user.getId());
                    String portfolioSummary = buildPortfolioSummary(fiat, portfolio);
                    String portfolioPerformanceSummary = buildPortfolioPerformanceSummary(fiat, portfolio, firstPrices, firstDates);
                    String priceHistorySummary = buildPortfolioPriceHistorySummary(fiat, portfolio);
                    String prompt = llmPromptBuilder.buildPortfolioReviewPrompt(portfolioSummary, portfolioPerformanceSummary, priceHistorySummary);
                    sendMessage(chatId, "Reviewing your portfolio...");
                    String portfolioReview = messageHandlingModule.sendPrompt(prompt).block();
                    sendMessage(chatId, portfolioReview != null ? portfolioReview : "No response received.");
                }
                break;
            case "/llm_ask":
                if (parts.length > 1) {
                    String query = text.substring(command.length() + 1);
                    String fiat = getUserFiat(user);
                    Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
                    String portfolioSummary = portfolio.isEmpty() ? "Portfolio is empty." : buildPortfolioSummary(fiat, portfolio);
                    String priceHistorySummary = portfolio.isEmpty() ? "No recent portfolio price history available." : buildPortfolioPriceHistorySummary(fiat, portfolio);
                    sendMessage(chatId, "Your question has been received and is being processed...");
                    String prompt = llmPromptBuilder.buildAskPrompt(query, portfolioSummary, priceHistorySummary);
                    String answer = messageHandlingModule.sendPrompt(prompt).block();
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
                                "📈 " + s1 + " vs " + s2 + "\n\n" +
                                s1 + ": " + String.format("%.2f", p1) + " " + fiat + "\n" +
                                s2 + ": " + String.format("%.2f", p2) + " " + fiat + "\n" +
                                "Ratio = " + String.format("%.2f", p1 / p2));
                    }
                } else if (parts.length == 2) {
                    String s1 = parts[1].toUpperCase();
                    if (!isAllowedCrypto(s1)) {
                        sendMessage(chatId, "Unsupported crypto. Supported: " + String.join(", ", CRYPTO_SYMBOLS));
                        break;
                    }
                    List<String> options = new ArrayList<>(CRYPTO_SYMBOLS);
                    options.remove(s1);
                    ReplyKeyboardMarkup keyboard = createKeyboard(options, 3);
                    pendingCommands.put(chatId, new PendingCommand(PendingAction.COMPARE_CHOOSE_SECOND, s1));
                    sendMessage(chatId, "Choose second crypto to compare with " + s1 + ":", keyboard);
                } else {
                    sendCompareChooseFirstCrypto(chatId, user);
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
                    sendPriceHistory(chatId, user, s, days);
                } else {
                    sendPriceHistoryChooseCrypto(chatId, user);
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
            sendMessage(chatId, "ℹ️ Your fiat currency is already set to " + normalized + ".\n\n" +
                    "You will continue to receive prices in the same currency.");
            return;
        }

        if (userRepository.updateFiat(user.getId(), normalized)) {
            sendMessage(chatId, "✅ Fiat currency updated\n\n" +
                    "💵 Your fiat currency has been set to " + normalized + ".");
        } else {
            sendMessage(chatId, "Failed to set fiat currency. Please try again.");
        }
    }

    private void sendFiatSelection(String chatId) {
        ReplyKeyboardMarkup keyboard = createKeyboard(FIAT_SYMBOLS, 3);
        sendMessage(chatId, "Please select your *fiat currency* from the buttons below.\n\n" +
                "📊 It will be used to display prices and for portfolio calculations.", keyboard);
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
                sendMessage(chatId, "❌ Addition Error\n\n" +
                        "The currency " + normalized + " is already in your tracked list.\n\n" +
                        "👉Please choose a different currency for addition .\n\n" +
                        "📋 You can view your current tracked list using the /tracked command.");
            } else {
                sendMessage(chatId, "✅ *Successfully added!*\n\n" +
                        normalized + " has been added to your watchlist.\n\n" +
                        "🔔 You will receive notifications every 24 hours if the price changes by 5%.\n\n" +
                        "📋 You can view your current tracked list using the /tracked command.");
                if (targetPrice > 0) {
                    sendMessage(chatId, "Custom price alert for " + normalized + " created with target " + String.format("%.2f", targetPrice) + " " + getUserFiat(user) + ".");
                }
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Please enter a valid numeric price. Usage: /add_tracked_crypto <symbol> <target_price>");
        }
    }

    private void sendAddTrackedSelection(String chatId) {
        ReplyKeyboardMarkup keyboard = createKeyboard(CRYPTO_SYMBOLS, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.ADD_TRACKED_CHOOSE, null));
        sendMessage(chatId, "📌 Add a cryptocurrency to your watchlist:\n\n" +
        "🎯 *Select a cryptocurrency* from the buttons below to add it to your tracked list.\n" +
        "🔔 Once added, I will notify you about price changes of the selected asset.\n" +
        "📊 *Price change alert:* 5% or more within 24 hours.\n" +
        "👉 Please choose a cryptocurrency from the buttons below.", keyboard);
    }

    private void handlePendingAddTrackedChoose(String chatId, User user, String text) {
        String normalized = text.toUpperCase();
        if (!isAllowedCrypto(normalized)) {
            sendMessage(chatId, "Please choose a valid cryptocurrency from the list: " + String.join(", ", CRYPTO_SYMBOLS));
            return;
        }
        List<String> tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        if (tracked.contains(normalized)) {
            sendMessage(chatId, "❌ Addition Error\n\n" +
                    "The currency " + normalized + " is already in your tracked list.\n\n" +
                    "👉Please choose a different currency for addition .\n\n" +
                    "📋 You can view your current tracked list using the /tracked command.");
            pendingCommands.remove(chatId);
            return;
        }
        pendingCommands.put(chatId, new PendingCommand(PendingAction.ADD_TRACKED_PRICE, normalized));
        String fiatTrk = getUserFiat(user);
        Double curPrice = cryptoInformationModule.getCurrentPrice(normalized, fiatTrk).block();
        String priceInfo = (curPrice != null && curPrice > 0)
                ? "📊 Current price of " + normalized + ": " + String.format("%.2f", curPrice) + " " + fiatTrk + "\n\n"
                : "";
        sendMessage(chatId, priceInfo + "🎯 Set a custom price alert\n" +
                "Please enter your target price for " + normalized + " in " + fiatTrk + ".\n\n" +
                "👉 Type 0 if you do not wish to skip custom price alert");
    }

    private void handlePendingAddTrackedPrice(String chatId, User user, String text, String symbol) {
        try {
            double targetPrice = Double.parseDouble(text);
            if (targetPrice < 0) {
                sendMessage(chatId, "Price cannot be negative.");
                return;
            }
            if (cryptoInformationModule.addTrackedCurrency(user.getId(), symbol, targetPrice, getUserFiat(user))) {
                sendMessage(chatId, "✅ *Successfully added!*\n\n" +
                        symbol + " has been added to your watchlist.\n\n" +
                        "🔔 You will receive notifications every 24 hours if the price changes by 5%.\n\n" +
                        "📋 You can view your current tracked list using the /tracked command.");
                if (targetPrice > 0) {
                    sendMessage(chatId, "Custom price alert for " + symbol + " created with target " + String.format("%.2f", targetPrice) + " " + getUserFiat(user) + ".");
                }
            } else {
                sendMessage(chatId, "❌ Addition Error\n\n" +
                        "The currency " + symbol + " is already in your tracked list.\n\n" +
                        "👉Please choose a different currency for addition .\n\n" +
                        "📋 You can view your current tracked list using the /tracked command.");
            }
            pendingCommands.remove(chatId);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Please enter a valid numeric price. Usage: /add_tracked_crypto <symbol> <target_price>");
        }
    }

    private void handleRemoveTrackedTyped(String chatId, User user, String symbol) {
        String normalized = symbol.toUpperCase();
        List<String> tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        if (!tracked.contains(normalized)) {
            sendMessage(chatId, "❌ Removal Error\n\n" +
                    "The currency " + normalized + " is not currently tracked.\n\n" +
                    "👉 Please choose a different currency or view your list with /tracked.");
            return;
        }
        if (cryptoInformationModule.removeTrackedCurrency(user.getId(), normalized)) {
            sendMessage(chatId, "✅ Currency removed\n\n" +
                    normalized + " has been successfully removed from your tracked list.\n\n" +
                    "🔔 To also delete price alerts for this currency, use:\n/delete_alert\n\n" +
                    "📋 You can view your current tracked list using the /tracked command.");
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
        sendMessage(chatId, "🗑️ Remove a cryptocurrency from your watchlist:\n\n" +
                "👉 Please select the cryptocurrency you want to remove from the buttons below.", keyboard);
    }

    private void handlePendingRemoveTracked(String chatId, User user, String text) {
        String normalized = text.toUpperCase();
        List<String> tracked = cryptoInformationModule.getTrackedCurrencies(user.getId());
        if (!tracked.contains(normalized)) {
            sendMessage(chatId, "❌ Removal Error\n\n" +
                    "Please choose a cryptocurrency from the buttons below.");
            return;
        }
        if (cryptoInformationModule.removeTrackedCurrency(user.getId(), normalized)) {
            sendMessage(chatId, "✅ Currency removed\n\n" +
                    normalized + " has been successfully removed from your tracked list.\n\n" +
                    "🔔 To also delete price alerts for this currency, use:\n/delete_alert\n\n" +
                    "📋 You can view your current tracked list using the /tracked command.");
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
            sendMessage(chatId, "⚠️ No tracked cryptocurrencies left.\n" +
                    "🔄 Default BTC has been automatically added back to your tracked list.");
        } else {
            sendMessage(chatId, "Tracked crypto removed. Current tracked: " + String.join(", ", trackedAfter));
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
                lines.add("🎯 " + symbol + ": " + String.format("%.2f", price) + " " + fiat);
            } else {
                lines.add("🎯 " + symbol + ": price unavailable");
            }
        }

        sendMessage(chatId, "📋 Your tracked cryptocurrencies:\n\n" + String.join("\n", lines));
    }

    // ===================== PORTFOLIO: Interactive Add =====================

    private void sendPortfolioAddChooseCrypto(String chatId) {
        ReplyKeyboardMarkup keyboard = createKeyboard(CRYPTO_SYMBOLS, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_ADD_CHOOSE_CRYPTO, null));
        sendMessage(chatId, "📊 Add to portfolio\n\n" +
                "👉 Please choose a cryptocurrency from the buttons below to add it to your portfolio.", keyboard);
    }

    private void handlePortfolioAddChoose(String chatId, User user, String text) {
        String symbol = text.toUpperCase();
        if (!isAllowedCrypto(symbol)) {
            sendMessage(chatId, "Please choose a valid cryptocurrency from the list: " + String.join(", ", CRYPTO_SYMBOLS));
            return;
        }
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_ADD_AMOUNT, symbol));
        sendMessage(chatId, "👉 Please enter the number of " + symbol + " coins you would like to add:\n" +
                "ℹ️ Positive number only (e.g., 1, 25.5, 100)\n\n" +
                "ℹ️ The number must be greater than zero.");
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
            sendMessage(chatId, "✅ Added successfully!\n\n" +
                    "+ " + amount + " " + symbol + " at $" + String.format("%.2f", priceUsd) + " per coin\n\n" +
                    "📊 Your current portfolio:");
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
        sendMessage(chatId, "🗑️ Remove from portfolio\n\n" +
                "👉 Please choose a cryptocurrency from the buttons below to remove it from your portfolio.", keyboard);
    }

    private void handlePortfolioRemoveChoose(String chatId, User user, String text) {
        String symbol = text.toUpperCase();
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (!portfolio.containsKey(symbol)) {
            sendMessage(chatId, "👉 Please choose a cryptocurrency from the buttons below.");
            return;
        }
        double currentAmount = portfolio.get(symbol);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_REMOVE_AMOUNT, symbol));
        sendMessage(chatId, "You currently own: " + currentAmount + " " + symbol + "\n" +
                "✏️ Please enter the number of coins you wish to remove:\n\n" +
                "ℹ️ Positive number only (e.g., 1, 1.5, 3)\n\n" +
                "ℹ️ Cannot exceed your current balance this cryptocurrency .");
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
            sendMessage(chatId, "✅ Removed " + amountToRemove + " " + symbol + "\nfrom your portfolio\n\n" +
                    "📊 Updated portfolio");
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
                lines.add(String.format("🎯 %s: %.4f × %.2f %s = %.2f %s", symbol, amount, price, fiat, cost, fiat));
            } else {
                lines.add("🎯 " + symbol + ": " + amount + " coins, price unavailable");
            }
        }
        sendMessage(chatId, "📊 Your current portfolio:\n\n" + String.join("\n", lines));
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
        sendMessage(chatId, "💰 Total portfolio value: " + String.format("%.2f", total) + " " + fiat);
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
        sendMessage(chatId, "📈 Portfolio value change\n\n" +
                "👉 Please choose a time period using the buttons below:\n\n" +
                "📅 1 day\n" +
                "📅 1 month\n" +
                "🗓️ 1 year", keyboard);
    }

    private void handlePortfolioHistoryPeriod(String chatId, User user, String text) {
        int days;
        String periodLabel;
        switch (text) {
            case "1 day": days = 1; periodLabel = "1 day"; break;
            case "1 month": days = 30; periodLabel = "1 month"; break;
            case "1 year": days = 365; periodLabel = "1 year"; break;
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
        String comparisonLabel = switch (days) {
            case 1 -> "1 day ago";
            case 30 -> "30 day ago";
            default -> "365 day ago";
        };
        sendMessage(chatId, "📈 Portfolio change (" + periodLabel + ")\n\n" +
                "Current: " + String.format("%.2f", currentTotal) + " " + fiat + "\n" +
                comparisonLabel + ": " + String.format("%.2f", historicalTotal) + " " + fiat + "\n\n" +
                "🟢 Difference: " + String.format("%.2f", diff) + " " + fiat + " (" + String.format("%.2f", percent) + "%)");
    }

    private void sendPriceHistoryChooseCrypto(String chatId, User user) {
        ReplyKeyboardMarkup keyboard = createKeyboard(CRYPTO_SYMBOLS, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PRICE_HISTORY_CHOOSE_CRYPTO, null));
        sendMessage(chatId, "📊 Price history\n\n" +
                "👉 Please choose a cryptocurrency from the buttons below.", keyboard);
    }

    private void handlePriceHistoryChooseCrypto(String chatId, User user, String text) {
        String symbol = text.toUpperCase();
        if (!isAllowedCrypto(symbol)) {
            sendMessage(chatId, "Please choose a valid crypto from the list: " + String.join(", ", CRYPTO_SYMBOLS));
            return;
        }
        List<String> periods = Arrays.asList("1 day", "7 days", "30 days");
        ReplyKeyboardMarkup keyboard = createKeyboard(periods, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PRICE_HISTORY_PERIOD, symbol));
        sendMessage(chatId, "📊 Price history\n\n" +
                "👉 Please choose the period using the buttons:\n\n" +
                "📅 1 day\n" +
                "📅 7 days\n" +
                "📅 30 days", keyboard);
    }

    private void handlePriceHistoryPeriod(String chatId, User user, String text, String symbol) {
        int days;
        switch (text) {
            case "1 day": days = 1; break;
            case "7 days": days = 7; break;
            case "30 days": days = 30; break;
            default:
                sendMessage(chatId, "Please choose a period from the buttons.");
                return;
        }
        pendingCommands.remove(chatId);
        sendPriceHistory(chatId, user, symbol, days);
    }

    private void sendPriceHistory(String chatId, User user, String symbol, int days) {
        String supported = String.join(", ", CRYPTO_SYMBOLS);
        if (!isAllowedCrypto(symbol)) {
            sendMessage(chatId, "Unsupported crypto. Supported: " + supported);
            return;
        }
        if (days < 1) {
            sendMessage(chatId, "Days must be at least 1. Supported: " + supported);
            return;
        }

        String fiat = getUserFiat(user);
        Double rate = fiatConversionService.getFiatRate("USD", fiat).block();
        if (rate == null) rate = 1.0;

        if (days == 1) {
            List<Double> prices = bingXService.getHistory(symbol, "1d", 1).block();
            Double midnightPrice = prices != null && !prices.isEmpty() ? prices.get(0) : null;
            Double currentPrice = cryptoInformationModule.getCurrentPrice(symbol, fiat).block();
            if (midnightPrice == null || midnightPrice <= 0 || currentPrice == null || currentPrice <= 0) {
                sendMessage(chatId, "Price history unavailable for " + symbol + ". Supported: " + supported);
                return;
            }
            StringBuilder sb = new StringBuilder("Price history for " + symbol + " (1 day):\n");
            sb.append("00:00: ").append(String.format("%.2f", midnightPrice * rate)).append(" ").append(fiat).append("\n");
            sb.append("Now:   ").append(String.format("%.2f", currentPrice)).append(" ").append(fiat).append("\n");
            double diff = currentPrice - midnightPrice * rate;
            String arrow = diff > 0 ? " ↑" : diff < 0 ? " ↓" : "";
            sb.append("Change: ").append(String.format("%.2f", diff)).append(" ").append(fiat).append(arrow);
            sendMessage(chatId, sb.toString());
            return;
        }

        List<Double> prices = bingXService.getHistory(symbol, "1d", days).block();
        if (prices == null || prices.isEmpty()) {
            sendMessage(chatId, "Price history unavailable for " + symbol + ". Supported: " + supported);
            return;
        }

        StringBuilder sb = new StringBuilder("Price history for " + symbol + " (" + days + " days):\n");
        for (int i = 0; i < prices.size(); i++) {
            double converted = prices.get(i) * rate;
            String arrow = "";
            if (i > 0) {
                double prev = prices.get(i - 1) * rate;
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

    private void sendPriceCryptoChooseCrypto(String chatId, User user) {
        ReplyKeyboardMarkup keyboard = createKeyboard(CRYPTO_SYMBOLS, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PRICE_CRYPTO_CHOOSE, null));
        sendMessage(chatId, "📊 View current price\n\n" +
                "👉Please choose a cryptocurrency from the buttons below to see its latest price.", keyboard);
    }

    private void handlePriceCryptoChoose(String chatId, User user, String text) {
        String symbol = text.toUpperCase();
        if (!isAllowedCrypto(symbol)) {
            sendMessage(chatId, "Please choose a valid crypto from the list: " + String.join(", ", CRYPTO_SYMBOLS));
            return;
        }
        pendingCommands.remove(chatId);
        String fiat = getUserFiat(user);
        Double price = cryptoInformationModule.getCurrentPrice(symbol, fiat).block();
        if (price == null || price <= 0) {
            sendMessage(chatId, "Could not fetch price for " + symbol + ".");
        } else {
            sendMessage(chatId, "📊 View current price:\n" +
                    "🎯 " + symbol + ": " + String.format("%.2f", price) + " " + fiat);
        }
    }

    private void sendCompareChooseFirstCrypto(String chatId, User user) {
        ReplyKeyboardMarkup keyboard = createKeyboard(CRYPTO_SYMBOLS, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.COMPARE_CHOOSE_FIRST, null));
        sendMessage(chatId, "🔄 Compare cryptocurrencies\n\n" +
                "👉 Please choose the first cryptocurrency from the buttons below.", keyboard);
    }

    private void handleCompareChooseFirst(String chatId, User user, String text) {
        String symbol = text.toUpperCase();
        if (!isAllowedCrypto(symbol)) {
            sendMessage(chatId, "Please choose a valid crypto from the list: " + String.join(", ", CRYPTO_SYMBOLS));
            return;
        }
        List<String> options = new ArrayList<>(CRYPTO_SYMBOLS);
        options.remove(symbol);
        ReplyKeyboardMarkup keyboard = createKeyboard(options, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.COMPARE_CHOOSE_SECOND, symbol));
        sendMessage(chatId, "🔄 Compare cryptocurrencies\n\n" +
                "👉 Please choose the second cryptocurrency from the buttons below.", keyboard);
    }

    private void handleCompareChooseSecond(String chatId, User user, String text, String firstSymbol) {
        String secondSymbol = text.toUpperCase();
        if (!isAllowedCrypto(secondSymbol)) {
            sendMessage(chatId, "Please choose a valid crypto from the list.");
            return;
        }
        if (secondSymbol.equalsIgnoreCase(firstSymbol)) {
            sendMessage(chatId, "Please choose a different crypto than " + firstSymbol + ".");
            return;
        }
        pendingCommands.remove(chatId);
        performCompare(chatId, user, firstSymbol, secondSymbol);
    }

    private void performCompare(String chatId, User user, String s1, String s2) {
        String fiat = getUserFiat(user);
        Double p1 = cryptoInformationModule.getCurrentPrice(s1, fiat).block();
        Double p2 = cryptoInformationModule.getCurrentPrice(s2, fiat).block();
        if (p1 == null || p1 <= 0 || p2 == null || p2 <= 0) {
            sendMessage(chatId, "Could not fetch prices for " + s1 + " and/or " + s2 + ".");
            return;
        }
        sendMessage(chatId,
                "📈 " + s1 + " vs " + s2 + "\n\n" +
                s1 + ": " + String.format("%.2f", p1) + " " + fiat + "\n" +
                s2 + ": " + String.format("%.2f", p2) + " " + fiat + "\n" +
                "Ratio = " + String.format("%.2f", p1 / p2));
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
        Double fiatRate = fiatConversionService.getFiatRate("USD", fiat).block();
        if (fiatRate == null) fiatRate = 1.0;
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
            double firstPriceFiat = firstPriceUsd * fiatRate;
            double diffUsd = currentPriceUsd - firstPriceUsd;
            double percent = firstPriceUsd != 0 ? (diffUsd / firstPriceUsd) * 100 : 0;
            lines.add(String.format(java.util.Locale.US,
                    "🔹 %s\n   Bought: %.2f %s (%.2f USD)\n   Now:    %.2f %s (%.2f USD)\n   Change: %.2f%%",
                    symbol, firstPriceFiat, fiat, firstPriceUsd, currentPriceFiat, fiat, currentPriceUsd, percent));
        }
        sendMessage(chatId, "📊 Per-crypto change (since first purchase):\n\n" + String.join("\n", lines));
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
        List<UserAlertInfo> alerts = cryptoInformationModule.getUserAlerts(user.getId());
        if (alerts.isEmpty()) {
            sendMessage(chatId, "You have no active custom alerts.");
            return;
        }

        String userFiat = getUserFiat(user);
        StringBuilder sb = new StringBuilder("🔔 *Your active alerts*\n\n");
        for (UserAlertInfo alert : alerts) {
            String formattedTarget = formatAlertTargetValue(alert, userFiat);
            sb.append(String.format(java.util.Locale.US, "ID: %d | %s | %s | Target: %s\n",
                alert.getId(), alert.getSymbol(), alert.getType(), formattedTarget));
        }
        sendMessage(chatId, sb.toString().trim());
    }

    private String formatAlertTargetValue(UserAlertInfo alert, String userFiat) {
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
        List<UserAlertInfo> alerts = cryptoInformationModule.getUserAlerts(user.getId());
        if (alerts.isEmpty()) {
            sendMessage(chatId, "You have no active custom alerts to delete.");
            return;
        }
        StringBuilder sb = new StringBuilder("🔔 Your active alerts:\n\n");
        for (UserAlertInfo alert : alerts) {
            String formattedTarget = formatAlertTargetValue(alert, getUserFiat(user));
            sb.append(String.format(java.util.Locale.US, "ID: %d | %s | %s | Target: %s\n",
                    alert.getId(), alert.getSymbol(), alert.getType(), formattedTarget));
        }
        sendMessage(chatId, sb.toString().trim());
        List<String> buttons = alerts.stream().map(a -> String.valueOf(a.getId())).collect(Collectors.toList());
        ReplyKeyboardMarkup keyboard = createKeyboard(buttons, 4);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.DELETE_ALERT_CHOOSE, null));
        sendMessage(chatId, "👉 Please choose the ID of the alert to delete:", keyboard);
    }

    private void handleDeleteAlertChoose(String chatId, User user, String text) {
        try {
            Integer alertId = Integer.parseInt(text);
            if (cryptoInformationModule.removeUserAlert(user.getId(), alertId)) {
                sendMessage(chatId, "Alert ID " + alertId + " deleted successfully.");
                handleAlertsList(chatId, user);
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
        sendMessage(chatId, "🔔 Create price alert\n\n" +
                "👉 Please choose a cryptocurrency from the buttons below to set up a price alert.", keyboard);
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
        
        sendMessage(chatId, "🔔 Choose alert type\n👉 Please choose one of the options below:\n" +
                "📊 PRICE – Trigger when price crosses your target value.\n" +
                "📈 PERCENT – Trigger when price changes by a certain % from current price.", keyboard);
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
            sendMessage(chatId, "📊 Set price alert for " + symbol + "\n" +
                    priceInfo + "\n" +
                    "🎯 Enter your target price in " + fiat + ":");
        } else {
            sendMessage(chatId, "📊 Set percent change alert for " + symbol + "\n" +
                    priceInfo + "\n" +
                    "🎯 Enter the percentage change in %:");
        }
    }

    private void handleSetAlertValue(String chatId, User user, String text, String symbol, String type) {
        try {
            double targetValue = Double.parseDouble(text);
            if (targetValue <= 0) {
                sendMessage(chatId, "Value must be positive.");
                return;
            }
            String fiat = getUserFiat(user);
            cryptoInformationModule.addUserAlert(user.getId(), symbol, type, targetValue, fiat);
            
            sendMessage(chatId, "Successfully created " + type + " alert for " + symbol + " with target " + targetValue + (type.equals("PERCENT") ? "%" : " " + fiat) + ".");
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Invalid number format. Please enter a valid number.");
        }
        pendingCommands.remove(chatId);
    }

    private void sendCurrentFiat(String chatId, User user) {
        sendMessage(chatId, "💰 Current fiat currency: " + getUserFiat(user) + "\n\n" +
                "ℹ️ To change it, use the /set_fiat command.");
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

    private void sendHelp(String chatId) {
        sendMessage(chatId, "🤖 CryptoBot – Help\n\n" +
                "📌 General\n" +
                "• /start — start the bot and see main recommendations\n" +
                "• /set_fiat — choose your base fiat currency\n" +
                "• /current_fiat — show the current fiat currency\n\n" +
                "📊 Tracked Cryptocurrencies\n" +
                "• /add_tracked_crypto — add a crypto to your watchlist\n" +
                "• /remove_tracked_crypto — remove a crypto from your watchlist\n" +
                "• /tracked — list tracked cryptocurrencies\n" +
                "• /price_crypto — view current crypto price\n\n" +
                "💼 Portfolio Management\n" +
                "• /portfolio_add — add coins to your portfolio\n" +
                "• /portfolio_remove — remove coins from your portfolio\n" +
                "• /portfolio — view portfolio value with current prices\n" +
                "• /portfolio_amount — show total portfolio value\n" +
                "• /portfolio_history — view portfolio value change over a period\n" +
                "• /portfolio_crypto_history — show per-crypto change since purchase\n\n" +
                "🔔 Alerts\n" +
                "• /set_alert — create a custom price alert\n" +
                "• /alerts_list — list your active alerts\n" +
                "• /alerts — show triggered alerts history for the last 7 days\n" +
                "• /delete_alert — delete an alert\n\n" +
                "🔄 Comparison and History\n" +
                "• /compare — compare two cryptocurrencies\n" +
                "• /price_history — view crypto price history \n\n" +
                "🧠 AI Analysis (LLM)\n" +
                "• /llm_analyze <symbol> — get investment analysis\n" +
                "• /llm_portfolio — review your portfolio with AI\n" +
                "• /llm_ask <question> — ask the AI about crypto");
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

    private String buildPortfolioSummary(String fiat, Map<String, Double> portfolio) {
        return portfolio.entrySet().stream()
                .map(entry -> {
                    String symbol = entry.getKey();
                    double amount = entry.getValue();
                    Double price = cryptoInformationModule.getCurrentPrice(symbol, fiat).block();
                    if (price == null || price <= 0) {
                        return String.format("%s: %.4f coins (current price unavailable)", symbol, amount);
                    }
                    return String.format("%s: %.4f coins @ %.2f %s = %.2f %s", symbol, amount, price, fiat, price * amount, fiat);
                })
                .collect(Collectors.joining("\n"));
    }

    private String buildPortfolioPerformanceSummary(String fiat, Map<String, Double> portfolio, Map<String, Double> firstPrices, Map<String, LocalDateTime> firstDates) {
        double currentTotal = portfolio.entrySet().stream()
                .mapToDouble(entry -> {
                    Double price = cryptoInformationModule.getCurrentPrice(entry.getKey(), fiat).block();
                    return price != null && price > 0 ? price * entry.getValue() : 0.0;
                })
                .sum();

        double total30DaysAgo = computeHistoricalPortfolioTotal(fiat, portfolio, firstPrices, firstDates, 30);
        double total365DaysAgo = computeHistoricalPortfolioTotal(fiat, portfolio, firstPrices, firstDates, 365);

        return String.format(
                "Current value: %.2f %s\n" +
                "Approx. 30 days ago: %.2f %s (%s)\n" +
                "Approx. 365 days ago: %.2f %s (%s)",
                currentTotal,
                fiat,
                total30DaysAgo,
                fiat,
                formatPercentChange(total30DaysAgo, currentTotal),
                total365DaysAgo,
                fiat,
                formatPercentChange(total365DaysAgo, currentTotal)
        );
    }

    private String buildPortfolioPriceHistorySummary(String fiat, Map<String, Double> portfolio) {
        return portfolio.keySet().stream()
                .map(symbol -> buildSymbolPriceTrendSummary(symbol, fiat))
                .collect(Collectors.joining("\n"));
    }


    private String buildSymbolPriceHistorySummary(String symbol, String fiat, Double currentPrice) {
        if (isFiatSymbol(symbol)) {
            if (symbol.equalsIgnoreCase(fiat)) {
                return String.format("1 %s equals 1 %s.", symbol, fiat);
            }
            return String.format("Current exchange rate: 1 %s = %.6f %s.", symbol, currentPrice != null ? currentPrice : 0.0, fiat);
        }

        List<Double> history = bingXService.getHistory(symbol, "1d", 30).block();
        if (history == null || history.isEmpty()) {
            return String.format("%s: recent price history unavailable.", symbol);
        }
        List<Double> historyInFiat = convertHistoryToFiat(history, fiat);
        double firstPrice = historyInFiat.get(0);
        double lastPrice = currentPrice != null && currentPrice > 0 ? currentPrice : historyInFiat.get(historyInFiat.size() - 1);
        return String.format(
                "%s 30-day trend: %.2f %s -> %.2f %s (%s).",
                symbol,
                firstPrice,
                fiat,
                lastPrice,
                fiat,
                formatPercentChange(firstPrice, lastPrice)
        );
    }

    private String buildSymbolPriceTrendSummary(String symbol, String fiat) {
        List<Double> history = bingXService.getHistory(symbol, "1d", 30).block();
        if (history == null || history.isEmpty()) {
            return String.format("%s: price history unavailable.", symbol);
        }
        List<Double> historyInFiat = convertHistoryToFiat(history, fiat);
        int size = historyInFiat.size();
        double latest = historyInFiat.get(size - 1);
        double start30 = historyInFiat.get(0);
        double start7 = size >= 7 ? historyInFiat.get(size - 7) : historyInFiat.get(0);
        return String.format(
                "%s: current %.2f %s, 7d %s, 30d %s",
                symbol,
                latest,
                fiat,
                formatPercentChange(start7, latest),
                formatPercentChange(start30, latest)
        );
    }

    private List<Double> convertHistoryToFiat(List<Double> history, String fiat) {
        if ("USD".equalsIgnoreCase(fiat)) {
            return history;
        }
        Double rate = Optional.ofNullable(fiatConversionService.getFiatRate("USD", fiat).block()).orElse(1.0);
        return history.stream().map(price -> price * rate).collect(Collectors.toList());
    }

    private Double getCurrentPriceForAnalysis(String symbol, String fiat) {
        if (isFiatSymbol(symbol)) {
            if (symbol.equalsIgnoreCase(fiat)) {
                return 1.0;
            }
            return fiatConversionService.getFiatRate(symbol, fiat).block();
        }
        return cryptoInformationModule.getCurrentPrice(symbol, fiat).block();
    }

    private boolean isFiatSymbol(String symbol) {
        return FIAT_SYMBOLS.contains(symbol.toUpperCase());
    }

    private double computeHistoricalPortfolioTotal(String fiat, Map<String, Double> portfolio, Map<String, Double> firstPrices, Map<String, LocalDateTime> firstDates, int days) {
        double fiatRate = Optional.ofNullable(fiatConversionService.getFiatRate("USD", fiat).block()).orElse(1.0);
        LocalDateTime now = LocalDateTime.now();
        double total = 0.0;

        for (Map.Entry<String, Double> entry : portfolio.entrySet()) {
            String symbol = entry.getKey();
            double amount = entry.getValue();
            Double firstPriceUsd = firstPrices.get(symbol);
            LocalDateTime firstDate = firstDates.get(symbol);
            boolean useFirstPrice = firstPriceUsd != null && firstDate != null && Duration.between(firstDate, now).toDays() < days;

            if (useFirstPrice) {
                total += firstPriceUsd * amount * fiatRate;
                continue;
            }

            List<Double> history = bingXService.getHistory(symbol, "1d", days).block();
            if (history != null && !history.isEmpty()) {
                total += history.get(0) * amount * fiatRate;
            } else if (firstPriceUsd != null) {
                total += firstPriceUsd * amount * fiatRate;
            } else {
                Double currentPrice = cryptoInformationModule.getCurrentPrice(symbol, fiat).block();
                if (currentPrice != null) {
                    total += currentPrice * amount;
                }
            }
        }

        return total;
    }

    private String formatPercentChange(double base, double current) {
        if (base == 0) {
            return "N/A";
        }
        double change = ((current - base) / Math.abs(base)) * 100.0;
        return String.format("%+.2f%%", change);
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
        PRICE_HISTORY_CHOOSE_CRYPTO,
        PRICE_HISTORY_PERIOD,
        PRICE_CRYPTO_CHOOSE,
        COMPARE_CHOOSE_FIRST,
        COMPARE_CHOOSE_SECOND,
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