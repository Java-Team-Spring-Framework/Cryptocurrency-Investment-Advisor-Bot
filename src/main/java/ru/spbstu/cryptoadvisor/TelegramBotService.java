package ru.spbstu.cryptoadvisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TelegramBotService extends TelegramLongPollingBot implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);
    private static final int MAX_RETRIES = 10;
    private static final long RETRY_DELAY_MS = 5000;

    private final String botToken;
    private final AuthUserModule authUserModule;
    private final CryptoInformationModule cryptoInformationModule;
    private final PortfolioManagementModule portfolioManagementModule;
    private final MessageHandlingModule messageHandlingModule;
    private final UserRepository userRepository;
    private final BingXService bingXService;

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
            String messageText = update.getMessage().getText();
            String chatId = update.getMessage().getChatId().toString();
            String username = update.getMessage().getFrom().getUserName();

            handleCommand(chatId, messageText, username);
        }
    }

    private void handleCommand(String chatId, String text, String username) {
        String[] parts = text.split(" ");
        String command = parts[0];

        userRepository.findByChatId(chatId).ifPresentOrElse(user -> {
            switch (command) {
                case "/start":
                    sendMessage(chatId, "Welcome back, " + (username != null ? username : "") + "!");
                    break;
                case "/set_fiat":
                    if (parts.length > 1) {
                        userRepository.updateFiat(user.getId(), parts[1]);
                        sendMessage(chatId, "Fiat currency set to " + parts[1]);
                    } else {
                        sendMessage(chatId, "Usage: /set_fiat <symbol>");
                    }
                    break;
                case "/add_tracked_crypto":
                    if (parts.length > 2) {
                        cryptoInformationModule.addTrackedCurrency(user.getId(), parts[1], Double.parseDouble(parts[2]));
                        sendMessage(chatId, "Added " + parts[1] + " to watchlist with target price " + parts[2]);
                    } else {
                        sendMessage(chatId, "Usage: /add_tracked_crypto <symbol> <target_price>");
                    }
                    break;
                case "/remove_tracked_crypto":
                    if (parts.length > 1) {
                        cryptoInformationModule.removeTrackedCurrency(user.getId(), parts[1]);
                        sendMessage(chatId, "Removed " + parts[1] + " from watchlist");
                    } else {
                        sendMessage(chatId, "Usage: /remove_tracked_crypto <symbol>");
                    }
                    break;
                case "/price_crypto":
                    if (parts.length > 1) {
                        // In a real app we'd fetch the user's preferred fiat
                        cryptoInformationModule.getCurrentPrice(parts[1], "USD")
                                .subscribe(price -> sendMessage(chatId, "Current price of " + parts[1] + ": " + price + " USD"));
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
                        sendMessage(chatId, "Analyzing " + parts[1] + "...");
                        messageHandlingModule.processFreeTextRequest("Analyze cryptocurrency " + parts[1] + " and provide investment advice.")
                                .subscribe(resp -> sendMessage(chatId, resp));
                    } else {
                        sendMessage(chatId, "Usage: /llm_analyze <symbol>");
                    }
                    break;
                case "/llm_portfolio":
                    Map<String, Double> p = portfolioManagementModule.getPortfolio(user.getId());
                    String portfolioStr = p.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(", "));
                    sendMessage(chatId, "Reviewing your portfolio...");
                    messageHandlingModule.processFreeTextRequest("Review this cryptocurrency portfolio and suggest improvements: " + portfolioStr)
                            .subscribe(resp -> sendMessage(chatId, resp));
                    break;
                case "/llm_ask":
                    if (parts.length > 1) {
                        String query = text.substring(command.length() + 1);
                        messageHandlingModule.processFreeTextRequest(query)
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
                        // We need to fetch all prices and sum them up
                        Flux.fromIterable(port.entrySet())
                                .flatMap(e -> cryptoInformationModule.getCurrentPrice(e.getKey(), "USD")
                                        .map(price -> price * e.getValue()))
                                .reduce(0.0, Double::sum)
                                .subscribe(total -> sendMessage(chatId, "Total portfolio value: " + total + " USD"));
                    }
                    break;
                case "/compare":
                    if (parts.length > 2) {
                        String s1 = parts[1];
                        String s2 = parts[2];
                        cryptoInformationModule.getCurrentPrice(s1, "USD")
                                .zipWith(cryptoInformationModule.getCurrentPrice(s2, "USD"))
                                .subscribe(tuple -> {
                                    double p1 = tuple.getT1();
                                    double p2 = tuple.getT2();
                                    sendMessage(chatId, s1 + ": " + p1 + " USD\n" + s2 + ": " + p2 + " USD\nRatio: " + (p1 / p2));
                                });
                    } else {
                        sendMessage(chatId, "Usage: /compare <symbol1> <symbol2>");
                    }
                    break;
                case "/price_history":
                    if (parts.length > 2) {
                        String s = parts[1];
                        int days = Integer.parseInt(parts[2]);
                        bingXService.getHistory(s, "USD", "1d", days)
                                .subscribe(prices -> sendMessage(chatId, "Price history for " + s + " (" + days + " days): " + prices));
                    } else {
                        sendMessage(chatId, "Usage: /price_history <symbol> <days>");
                    }
                    break;
                case "/help":
                    sendMessage(chatId, "Available commands:\n" +
                            "/start - Register\n" +
                            "/set_fiat <symbol> - Set fiat currency\n" +
                            "/add_tracked_crypto <symbol> <target> - Watch crypto\n" +
                            "/remove_tracked_crypto <symbol> - Unwatch crypto\n" +
                            "/price_crypto <symbol> - Get current price\n" +
                            "/portfolio_add <symbol> <amt> <price> - Add to portfolio\n" +
                            "/portfolio - View portfolio\n" +
                            "/llm_analyze <symbol> - Get AI analysis\n" +
                            "/llm_portfolio - Get AI portfolio review\n" +
                            "/llm_ask <question> - Ask AI anything");
                    break;
                default:
                    sendMessage(chatId, "Unknown command. Type /help for list of commands.");
            }
        }, () -> {
            if (command.equals("/start")) {
                authUserModule.registerUser(chatId, username);
                sendMessage(chatId, "Welcome " + (username != null ? username : "") + "! Registration complete.");
            } else {
                sendMessage(chatId, "Please /start first.");
            }
        });
    }

    public void sendMessage(String chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
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
}
