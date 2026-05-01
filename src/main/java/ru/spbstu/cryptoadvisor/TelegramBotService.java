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
import reactor.core.publisher.Flux;

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

    private final String botToken;
    private final AuthUserModule authUserModule;
    private final CryptoInformationModule cryptoInformationModule;
    private final PortfolioManagementModule portfolioManagementModule;
    private final MessageHandlingModule messageHandlingModule;
    private final UserRepository userRepository;
    private final BingXService bingXService;
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    public TelegramBotService(AuthUserModule authUserModule,
                              CryptoInformationModule cryptoInformationModule,
                              PortfolioManagementModule portfolioManagementModule,
                              MessageHandlingModule messageHandlingModule,
                              UserRepository userRepository,
                              BingXService bingXService) {
        this.botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        this.authUserModule = authUserModule;
        this.cryptoInformationModule = cryptoInformationModule;
        this.portfolioManagementModule = portfolioManagementModule;
        this.messageHandlingModule = messageHandlingModule;
        this.userRepository = userRepository;
        this.bingXService = bingXService;
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
                sendMessage(chatId, "Welcome " + (username != null ? username : "") + "! Registration complete.");
            } else {
                sendMessage(chatId, "Please /start first.");
            }
            return;
        }

        User user = optionalUser.get();
        PendingCommand pending = pendingCommands.get(chatId);
        if (pending != null) {
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
                default:
                    break;
            }
        }

        switch (command) {
            case "/start":
                sendMessage(chatId, "Welcome back, " + (username != null ? username : "") + "!");
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
                    cryptoInformationModule.getCurrentPrice(parts[1], fiat)
                            .subscribe(price -> {
                                if (price == null || price <= 0) {
                                    sendMessage(chatId, "Could not fetch price for " + parts[1] + ". Please try again later.");
                                } else {
                                    String formattedPrice = String.format("%.2f", price);
                                    sendMessage(chatId, "Current price of " + parts[1].toUpperCase() + ": " + formattedPrice + " " + fiat);
                                }
                            }, error -> sendMessage(chatId, "Error fetching price: " + error.getMessage()));
                } else {
                    sendMessage(chatId, "Usage: /price_crypto <symbol>");
                }
                break;
            case "/portfolio_add":
                if (parts.length > 3) {
                    portfolioManagementModule.addAsset(user.getId(), parts[1], Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
                    sendMessage(chatId, "Added " + parts[2] + " " + parts[1] + " to your portfolio");
                } else {
                    sendMessage(chatId, "Usage: /portfolio_add <symbol> <amount> <price_at_purchase>");
                }
                break;
            case "/portfolio":
                Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
                if (portfolio.isEmpty()) {
                    sendMessage(chatId, "Your portfolio is empty.");
                } else {
                    StringBuilder sb = new StringBuilder("Your portfolio:\n");
                    portfolio.forEach((s, a) -> sb.append(s).append(": ").append(a).append("\n"));
                    sendMessage(chatId, sb.toString());
                }
                break;
            case "/llm_analyze":
                if (parts.length > 1) {
                    sendMessage(chatId, "Requesting investment analysis for " + parts[1] + "...");
                    messageHandlingModule.analyzeCryptoInvestment(parts[1])
                            .subscribe(resp -> sendMessage(chatId, resp));
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
                    messageHandlingModule.askOpenRouter("Review this cryptocurrency portfolio and suggest improvements: " + portfolioStr)
                            .subscribe(resp -> sendMessage(chatId, resp));
                }
                break;
            case "/llm_ask":
                if (parts.length > 1) {
                    String query = text.substring(command.length() + 1);
                    messageHandlingModule.askOpenRouter(query)
                            .subscribe(response -> sendMessage(chatId, response));
                } else {
                    sendMessage(chatId, "Usage: /llm_ask <your question>");
                }
                break;
            case "/portfolio_amount":
                Map<String, Double> port = portfolioManagementModule.getPortfolio(user.getId());
                if (port.isEmpty()) {
                    sendMessage(chatId, "Your portfolio is empty.");
                } else {
                    String fiat = getUserFiat(user);
                    Flux.fromIterable(port.entrySet())
                            .flatMap(e -> cryptoInformationModule.getCurrentPrice(e.getKey(), fiat)
                                    .map(price -> price * e.getValue()))
                            .reduce(0.0, Double::sum)
                            .subscribe(total -> sendMessage(chatId, "Total portfolio value: " + total + " " + fiat));
                }
                break;
            case "/compare":
                if (parts.length > 2) {
                    String s1 = parts[1];
                    String s2 = parts[2];
                    String fiat = getUserFiat(user);
                    cryptoInformationModule.getCurrentPrice(s1, fiat)
                            .zipWith(cryptoInformationModule.getCurrentPrice(s2, fiat))
                            .subscribe(tuple -> {
                                double p1 = tuple.getT1();
                                double p2 = tuple.getT2();
                                sendMessage(chatId, s1 + ": " + p1 + " " + fiat + "\n" + s2 + ": " + p2 + " " + fiat + "\nRatio: " + (p1 / p2));
                            });
                } else {
                    sendMessage(chatId, "Usage: /compare <symbol1> <symbol2>");
                }
                break;
            case "/price_history":
                if (parts.length > 2) {
                    String s = parts[1];
                    int days = Integer.parseInt(parts[2]);
                    String fiat = getUserFiat(user);
                    bingXService.getHistory(s, fiat, "1d", days)
                            .subscribe(prices -> sendMessage(chatId, "Price history for " + s + " (" + days + " days): " + prices));
                } else {
                    sendMessage(chatId, "Usage: /price_history <symbol> <days>");
                }
                break;
            case "/help":
                sendMessage(chatId, "Available commands:\n" +
                        "/start - Register\n" +
                        "/set_fiat - Choose fiat currency\n" +
                        "/current_fiat - Show current fiat\n" +
                        "/add_tracked_crypto - Add tracked crypto\n" +
                        "/remove_tracked_crypto - Remove tracked crypto\n" +
                        "/tracked - View tracked cryptos\n" +
                        "/price_crypto <symbol> - Get current price\n" +
                        "/portfolio_add <symbol> <amt> <price> - Add to portfolio\n" +
                        "/portfolio - View portfolio\n" +
                        "/portfolio_amount - View portfolio value\n" +
                        "/llm_analyze <symbol> - Get LLM investment analysis for a crypto\n" +
                        "/llm_portfolio - Get LLM review of your portfolio\n" +
                        "/llm_ask <question> - Ask the LLM about crypto markets");
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
            sendMessage(chatId, "Your fiat currency is already set to " + normalized + ".");
            return;
        }

        if (userRepository.updateFiat(user.getId(), normalized)) {
            sendMessage(chatId, "Fiat currency set to " + normalized + ".");
        } else {
            sendMessage(chatId, "Failed to set fiat currency. Please try again.");
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
            if (!cryptoInformationModule.addTrackedCurrency(user.getId(), normalized, targetPrice)) {
                sendMessage(chatId, "Currency " + normalized + " is already tracked or unsupported.");
            } else {
                sendMessage(chatId, "Added " + normalized + " to watchlist with target price " + targetPriceText + ".");
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
        sendMessage(chatId, "Enter the target price for " + normalized + " in " + getUserFiat(user) + ":");
    }

    private void handlePendingAddTrackedPrice(String chatId, User user, String text, String symbol) {
        try {
            double targetPrice = Double.parseDouble(text);
            if (targetPrice < 0) {
                sendMessage(chatId, "Price cannot be negative. Please enter a non-negative target price for " + symbol + ".");
                return;
            }
            if (cryptoInformationModule.addTrackedCurrency(user.getId(), symbol, targetPrice)) {
                sendMessage(chatId, "Added " + symbol + " to watchlist with target price " + targetPrice + ".");
            } else {
                sendMessage(chatId, "Could not add " + symbol + ". It may already be tracked.");
            }
            pendingCommands.remove(chatId);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Please enter a valid price for " + symbol + ".");
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
            sendMessage(chatId, "No tracked cryptocurrencies left. Default BTC has been added to your watchlist.");
        } else {
            sendMessage(chatId, "Tracked crypto removed. Current tracked: " + String.join(", ", trackedAfter));
        }
    }

    private void sendTrackedList(String chatId, User user) {
        List<CryptoInformationModule.TrackedCryptoInfo> tracked = cryptoInformationModule.getTrackedCurrencyInfo(user.getId());
        if (tracked.isEmpty()) {
            cryptoInformationModule.ensureDefaultTrackedCurrency(user.getId());
            tracked = cryptoInformationModule.getTrackedCurrencyInfo(user.getId());
        }
        String fiat = getUserFiat(user);
        if (tracked.isEmpty()) {
            sendMessage(chatId, "No tracked cryptocurrencies.");
            return;
        }
        Flux.fromIterable(tracked)
                .flatMap(info -> cryptoInformationModule.getCurrentPrice(info.getSymbol(), fiat)
                        .map(price -> {
                            if (price != null && price > 0) {
                                String formattedPrice = String.format("%.2f", price);
                                return info.getSymbol() + ": " + formattedPrice + " " + fiat + " (target: " + info.getTargetPrice() + " " + fiat + ")";
                            } else {
                                return info.getSymbol() + ": price unavailable (target: " + info.getTargetPrice() + " " + fiat + ")";
                            }
                        })
                        .onErrorReturn(info.getSymbol() + ": error fetching price (target: " + info.getTargetPrice() + " " + fiat + ")"))
                .collectList()
                .subscribe(lines -> {
                    if (lines.isEmpty()) {
                        sendMessage(chatId, "Could not fetch any tracked prices.");
                    } else {
                        sendMessage(chatId, "Tracked cryptocurrencies:\n" + String.join("\n", lines));
                    }
                });
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
        markup.setKeyboard(keyboard);
        markup.setResizeKeyboard(true);
        markup.setOneTimeKeyboard(true);
        return markup;
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
    public void afterPropertiesSet() throws Exception {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                botsApi.registerBot(this);
                log.info("Telegram bot registered successfully on attempt {}", attempt);
                return;
            } catch (TelegramApiException e) {
                log.warn("Failed to register Telegram bot (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    throw e;
                }
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }

    private enum PendingAction {
        ADD_TRACKED_CHOOSE,
        ADD_TRACKED_PRICE,
        REMOVE_TRACKED
    }

    private static class PendingCommand {
        private final PendingAction action;
        private final String symbol;

        public PendingCommand(PendingAction action, String symbol) {
            this.action = action;
            this.symbol = symbol;
        }
    }
}
