package ru.spbstu.cryptoadvisor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.spbstu.cryptoadvisor.repository.CryptoCurrencyRepository;
import ru.spbstu.cryptoadvisor.repository.PortfolioRepository;
import ru.spbstu.cryptoadvisor.repository.TransactionRepository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Модуль управления портфелем криптовалют.
 * Реализует добавление/удаление активов, просмотр портфеля,
 * получение цен первой покупки для расчёта изменения стоимости.
 */
@Component
public class PortfolioManagementModule {

    private static final Logger log = LoggerFactory.getLogger(PortfolioManagementModule.class);

    private final CryptoCurrencyRepository cryptoCurrencyRepository;
    private final PortfolioRepository portfolioRepository;
    private final TransactionRepository transactionRepository;

    public PortfolioManagementModule(CryptoCurrencyRepository cryptoCurrencyRepository,
                                     PortfolioRepository portfolioRepository,
                                     TransactionRepository transactionRepository) {
        this.cryptoCurrencyRepository = cryptoCurrencyRepository;
        this.portfolioRepository = portfolioRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Добавление актива в портфель.
     * Если криптовалюта уже есть в портфеле — увеличивается количество.
     * Записывается транзакция покупки с текущей ценой.
     */
    public void addAsset(Long userId, String symbol, double amount, double price) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        Long cryptoId = cryptoCurrencyRepository.findIdBySymbol(symbol);
        if (cryptoId == null) {
            throw new IllegalArgumentException("Cryptocurrency " + symbol + " not found");
        }

        portfolioRepository.upsertAdd(userId, cryptoId, amount);

        transactionRepository.insert(userId, cryptoId, amount, price,
                Timestamp.valueOf(LocalDateTime.now()), "BUY");

        log.info("Added {} {} to portfolio for user {}, price={}", amount, symbol, userId, price);
    }

    /**
     * Удаление (продажа) актива из портфеля.
     * Уменьшает количество монет; если количество становится <= 0, запись удаляется.
     * Записывается транзакция продажи с текущей ценой.
     */
    public void removeAsset(Long userId, String symbol, double amountToRemove, double currentPrice) {
        if (amountToRemove <= 0) {
            throw new IllegalArgumentException("Amount to remove must be positive");
        }

        Long cryptoId = cryptoCurrencyRepository.findIdBySymbol(symbol);
        if (cryptoId == null) {
            throw new IllegalArgumentException("Cryptocurrency " + symbol + " not found");
        }

        Double currentAmount = portfolioRepository.findAmount(userId, cryptoId);

        if (currentAmount == null || currentAmount < amountToRemove) {
            throw new IllegalArgumentException("Not enough " + symbol + " in portfolio. Available: " +
                                               (currentAmount != null ? currentAmount : 0));
        }

        double newAmount = currentAmount - amountToRemove;

        if (newAmount <= 0.0) {
            portfolioRepository.delete(userId, cryptoId);
        } else {
            portfolioRepository.updateAmount(userId, cryptoId, newAmount);
        }

        transactionRepository.insert(userId, cryptoId, amountToRemove, currentPrice,
                Timestamp.valueOf(LocalDateTime.now()), "SELL");

        log.info("Removed {} {} from portfolio for user {}, price={}", amountToRemove, symbol, userId, currentPrice);
    }

    /**
     * Получение текущего состава портфеля.
     */
    public Map<String, Double> getPortfolio(Long userId) {
        return portfolioRepository.findPortfolioByUser(userId);
    }

    /**
     * Получение цены первой покупки (в USD) для каждой криптовалюты в портфеле.
     */
    public Map<String, Double> getFirstPurchasePrices(Long userId) {
        return transactionRepository.findFirstPurchasePrices(userId);
    }

    /**
     * Получение даты первой покупки для каждой криптовалюты в портфеле.
     */
    public Map<String, java.time.LocalDateTime> getFirstPurchaseDates(Long userId) {
        return transactionRepository.findFirstPurchaseDates(userId);
    }
}
