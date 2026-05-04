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
import reactor.core.publisher.Mono;

import static org.jooq.impl.DSL.e;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

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
               
                if (parts.length == 4) {
                    
                    try {
                        String symbol = parts[1].toUpperCase();
                        double amount = Double.parseDouble(parts[2]);
                        double price = Double.parseDouble(parts[3]);
                        portfolioManagementModule.addAsset(user.getId(), symbol, amount, price);
                        sendMessage(chatId, "Добавлено " + amount + " " + symbol + " в портфель.");
                    } catch (Exception e) {
                        sendMessage(chatId, "Ошибка: " + e.getMessage());
                    }
                } else {
                    
                    sendPortfolioAddChooseCrypto(chatId);
                }
                break;
            case "/portfolio_remove":
                
                Map<String, Double> currentPortfolio = portfolioManagementModule.getPortfolio(user.getId());
                if (currentPortfolio.isEmpty()) {
                    sendMessage(chatId, "Ваш портфель пуст.");
                } else {
                    sendPortfolioRemoveChooseCrypto(chatId, currentPortfolio);
                }
                break;

            case "/portfolio":
               
                handlePortfolioView(chatId, user);
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
                    sendMessage(chatId, "Ваш портфель пуст.");
                } else {
                    String fiat = getUserFiat(user);
              
                    Flux.fromIterable(port.entrySet())
                        .flatMap(e -> cryptoInformationModule.getCurrentPrice(e.getKey(), fiat)
                                .map(price -> price * e.getValue())
                                .onErrorResume(err -> Mono.just(0.0))) 
                        .reduce(0.0, Double::sum)
                        .subscribe(total -> {
                            String formatted = String.format("%.2f", total);
                            sendMessage(chatId, "Общая стоимость портфеля: " + formatted + " " + fiat);
                        });
                }
                break;

            case "/portfolio_history":
              
                handlePortfolioHistoryStart(chatId, user);
                break;
            
            case "/portfolio_crypto_history":
                
                handlePortfolioCryptoHistory(chatId, user);
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
    
    private void sendPortfolioAddChooseCrypto(String chatId) {
        List<String> buttons = new ArrayList<>(CRYPTO_SYMBOLS);
        buttons.add("Назад");  
        ReplyKeyboardMarkup keyboard = createKeyboard(buttons, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_ADD_CHOOSE_CRYPTO, null));
        sendMessage(chatId, "Выберите криптовалюту для добавления в портфель:", keyboard);
    }
    
     private void handlePortfolioAddChoose(String chatId, User user, String text) {
        if (text.equalsIgnoreCase("Назад")) {
            pendingCommands.remove(chatId);
            sendMessage(chatId, "Добавление отменено.");
            return;
        }
        String symbol = text.toUpperCase();
        if (!isAllowedCrypto(symbol)) {
            sendMessage(chatId, "Пожалуйста, выберите криптовалюту из списка.");
            return;
        }
        
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_ADD_AMOUNT, symbol));
        sendMessage(chatId, "Введите количество монет " + symbol + ":");
    }
    
    private void handlePortfolioAddAmount(String chatId, User user, String text, String symbol) {
        double amount;
        try {
            amount = Double.parseDouble(text);
            if (amount <= 0) {
                sendMessage(chatId, "Количество должно быть положительным числом. Попробуйте снова.");
                return;
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Некорректный ввод. Введите положительное число, например: 0.5");
            return;
        }

        
        pendingCommands.remove(chatId);

      
        cryptoInformationModule.getCurrentPrice(symbol, "USD")
            .subscribe(priceUsd -> {
                if (priceUsd == null || priceUsd <= 0) {
                    sendMessage(chatId, "Не удалось получить текущую цену " + symbol + ". Попробуйте позже.");
                    return;
                }
                try {
                    portfolioManagementModule.addAsset(user.getId(), symbol, amount, priceUsd);
                    sendMessage(chatId, "Успешно добавлено: " + amount + " " + symbol +
                                         " по цене $" + String.format("%.2f", priceUsd));
                 
                    handlePortfolioView(chatId, user);
                } catch (Exception e) {
                    sendMessage(chatId, "Ошибка при добавлении: " + e.getMessage());
                }
            }, error -> {
                sendMessage(chatId, "Ошибка получения цены: " + error.getMessage());
            });
    }

    private void sendPortfolioRemoveChooseCrypto(String chatId, Map<String, Double> portfolio) {
        List<String> symbols = new ArrayList<>(portfolio.keySet());
        symbols.add("Назад");
        ReplyKeyboardMarkup keyboard = createKeyboard(symbols, 3);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_REMOVE_CHOOSE_CRYPTO, null));
        sendMessage(chatId, "Выберите криптовалюту для удаления из портфеля:", keyboard);
    }
    

    private void handlePortfolioRemoveChoose(String chatId, User user, String text) {
        if (text.equalsIgnoreCase("Назад")) {
            pendingCommands.remove(chatId);
            sendMessage(chatId, "Удаление отменено.");
            return;
        }
        String symbol = text.toUpperCase();
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (!portfolio.containsKey(symbol)) {
            sendMessage(chatId, "Этой криптовалюты нет в вашем портфеле. Выберите из списка.");
            return;
        }
       
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_REMOVE_AMOUNT, symbol));
        double currentAmount = portfolio.get(symbol);
        sendMessage(chatId, "У вас есть " + currentAmount + " " + symbol +
                           ". Сколько монет вы хотите удалить?");
    }
    

    private void handlePortfolioRemoveAmount(String chatId, User user, String text, String symbol) {
        double amountToRemove;
        try {
            amountToRemove = Double.parseDouble(text);
            if (amountToRemove <= 0) {
                sendMessage(chatId, "Количество должно быть положительным числом.");
                return;
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Некорректный ввод. Введите положительное число.");
            return;
        }

        
        Double currentAmount = portfolioManagementModule.getPortfolio(user.getId()).get(symbol);
        if (currentAmount == null || currentAmount < amountToRemove) {
            sendMessage(chatId, "Недостаточно монет " + symbol + ". Доступно: " +
                               (currentAmount != null ? currentAmount : 0));
            return;
        }

        pendingCommands.remove(chatId);

        
        cryptoInformationModule.getCurrentPrice(symbol, "USD")
            .subscribe(priceUsd -> {
                if (priceUsd == null || priceUsd <= 0) {
                    sendMessage(chatId, "Не удалось получить текущую цену. Удаление прервано.");
                    return;
                }
                try {
                    portfolioManagementModule.removeAsset(user.getId(), symbol, amountToRemove, priceUsd);
                    sendMessage(chatId, "Удалено " + amountToRemove + " " + symbol + " из портфеля.");
                   
                    handlePortfolioView(chatId, user);
                } catch (Exception e) {
                    sendMessage(chatId, "Ошибка при удалении: " + e.getMessage());
                }
            }, error -> {
                sendMessage(chatId, "Ошибка получения цены: " + error.getMessage());
            });
    }

     private void handlePortfolioView(String chatId, User user) {
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (portfolio.isEmpty()) {
            sendMessage(chatId, "Ваш портфель пуст.");
            return;
        }

        String fiat = getUserFiat(user);
      
        Flux.fromIterable(portfolio.entrySet())
            .flatMap(entry -> {
                String symbol = entry.getKey();
                double amount = entry.getValue();
                return cryptoInformationModule.getCurrentPrice(symbol, fiat)
                        .map(price -> {
                            if (price == null || price <= 0) {
                                return symbol + ": " + amount + " шт., цена недоступна";
                            }
                            double cost = price * amount;
                            return String.format("%s: %.4f шт. × %.2f %s = %.2f %s",
                                    symbol, amount, price, fiat, cost, fiat);
                        })
                        .onErrorReturn(symbol + ": ошибка получения цены");
            })
            .collectList()
            .subscribe(lines -> {
                if (lines.isEmpty()) {
                    sendMessage(chatId, "Не удалось загрузить данные портфеля.");
                } else {
                    sendMessage(chatId, "Ваш портфель:\n" + String.join("\n", lines));
                }
            });
    }
   

    private void handlePortfolioHistoryStart(String chatId, User user) {
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (portfolio.isEmpty()) {
            sendMessage(chatId, "Ваш портфель пуст.");
            return;
        }
        List<String> periods = Arrays.asList("1 день", "1 месяц", "1 год", "Назад");
        ReplyKeyboardMarkup keyboard = createKeyboard(periods, 2);
        pendingCommands.put(chatId, new PendingCommand(PendingAction.PORTFOLIO_HISTORY_PERIOD, null));
        sendMessage(chatId, "Выберите период для оценки изменения стоимости портфеля:", keyboard);
    }
    

    private void handlePortfolioHistoryPeriod(String chatId, User user, String text) {
        if (text.equalsIgnoreCase("Назад")) {
            pendingCommands.remove(chatId);
            sendMessage(chatId, "Операция отменена.");
            return;
        }


        long days;
        switch (text) {
            case "1 день":
                days = 1L;
                break;
            case "1 месяц":
                days = 30L;
                break;
            case "1 год":
                days = 365L;
                break;
            default:
                sendMessage(chatId, "Пожалуйста, выберите период из списка.");
                return;
        }
        pendingCommands.remove(chatId);

        String fiat = getUserFiat(user);
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());

       
        Mono<Double> currentTotalMono = Flux.fromIterable(portfolio.entrySet())
                .flatMap(e -> cryptoInformationModule.getCurrentPrice(e.getKey(), fiat)
                        .map(price -> price * e.getValue())
                        .onErrorReturn(0.0))
                .reduce(0.0, Double::sum);

        
        Instant historyPoint = Instant.now().minus(days, ChronoUnit.DAYS);

        Mono<Double> historyTotalMono = Flux.fromIterable(portfolio.entrySet())
                .flatMap(e -> bingXService.getHistory(e.getKey(), fiat, "1d", (int) days)
                        .collectList() 
                        .map(prices -> {
                            if (prices.isEmpty()) return 0.0;
                    
                            double histPrice = prices.get(0);
                            return histPrice * e.getValue();
                        })
                        .onErrorReturn(0.0))
                .reduce(0.0, Double::sum);

        // Комбинируем результаты
        currentTotalMono.zipWith(historyTotalMono)
                .subscribe(tuple -> {
                    double current = tuple.getT1();
                    double historical = tuple.getT2();
                    double diff = current - historical;
                    double percent = historical != 0 ? (diff / historical) * 100 : 0;
                    String formatted = String.format("Изменение за %s:\n" +
                                    "Текущая стоимость: %.2f %s\n" +
                                    "Стоимость %d дн. назад: %.2f %s\n" +
                                    "Разница: %.2f %s (%.2f%%)",
                            text, current, fiat, days, historical, fiat, diff, fiat, percent);
                    sendMessage(chatId, formatted);
                }, error -> sendMessage(chatId, "Ошибка при вычислении истории: " + error.getMessage()));
    }
    

    private void handlePortfolioCryptoHistory(String chatId, User user) {
        Map<String, Double> portfolio = portfolioManagementModule.getPortfolio(user.getId());
        if (portfolio.isEmpty()) {
            sendMessage(chatId, "Ваш портфель пуст.");
            return;
        }

        String fiat = getUserFiat(user);
    
        Map<String, Double> firstPricesUsd = portfolioManagementModule.getFirstPurchasePrices(user.getId());

     
        Flux.fromIterable(portfolio.keySet())
            .flatMap(symbol -> {
                Double firstPriceUsd = firstPricesUsd.get(symbol);
                if (firstPriceUsd == null) {
                    return Mono.just(symbol + ": нет данных о первой покупке.");
                }
                return cryptoInformationModule.getCurrentPrice(symbol, fiat)
                        .map(currentPriceFiat -> {
                        
                            return null;  // надо доработать, а то заглушка
                        });
            })
            .collectList()
            .subscribe(lines -> {
                sendMessage(chatId, "Изменение с момента первой покупки:\n" + String.join("\n", lines));
            });

  
        List<String> resultLines = new ArrayList<>();
        
        Map<String, Double> finalFirstPricesUsd = firstPricesUsd;
        Flux.fromIterable(portfolio.keySet())
            .flatMap(symbol -> {
                if (!finalFirstPricesUsd.containsKey(symbol)) {
                    return Mono.just(symbol + ": нет данных о первой покупке");
                }
                double firstPriceUsd = finalFirstPricesUsd.get(symbol);
                
                Mono<Double> currentPriceUsdMono = cryptoInformationModule.getCurrentPrice(symbol, "USD");
                Mono<Double> currentPriceFiatMono = cryptoInformationModule.getCurrentPrice(symbol, fiat);
                return Mono.zip(currentPriceUsdMono, currentPriceFiatMono)
                        .map(tuple -> {
                            double currentUsd = tuple.getT1();
                            double currentFiat = tuple.getT2();
                            if (currentUsd <= 0 || currentFiat <= 0) {
                                return symbol + ": цена недоступна";
                            }
                            double diffUsd = currentUsd - firstPriceUsd;
                            double percent = firstPriceUsd != 0 ? (diffUsd / firstPriceUsd) * 100 : 0;
                            return String.format("%s: цена покупки %.2f USD, тек. цена %.2f %s (%.2f USD), изменение %+.2f%%",
                                    symbol, firstPriceUsd, currentFiat, fiat, currentUsd, percent);
                        })
                        .onErrorReturn(symbol + ": ошибка получения цены");
            })
            .collectList()
            .subscribe(lines -> {
                sendMessage(chatId, "Изменение стоимости с момента первой покупки:\n" + String.join("\n", lines));
            }, error -> sendMessage(chatId, "Ошибка: " + error.getMessage()));
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
        REMOVE_TRACKED,
        PORTFOLIO_ADD_CHOOSE_CRYPTO,
        PORTFOLIO_ADD_AMOUNT,
        PORTFOLIO_REMOVE_CHOOSE_CRYPTO,
        PORTFOLIO_REMOVE_AMOUNT,
        PORTFOLIO_HISTORY_PERIOD       
    }

    private static class PendingCommand {
        private final PendingAction action;
        private final String symbol;
        private final Double extraData;

        public PendingCommand(PendingAction action, String symbol) {
            this.action = action;
            this.symbol = symbol;
        }

        public PendingCommand(PendingAction action, String symbol, Double extraData) {
            this.action = action;
            this.symbol = symbol;
            this.extraData = extraData;
        }
    }
}
