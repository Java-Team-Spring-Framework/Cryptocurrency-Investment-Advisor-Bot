package ru.spbstu.cryptoadvisor.service;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import ru.spbstu.cryptoadvisor.dto.AlertCheckMessage;
import ru.spbstu.cryptoadvisor.dto.TrackedCryptoInfo;
import ru.spbstu.cryptoadvisor.dto.UserAlertInfo;
import ru.spbstu.cryptoadvisor.repository.CryptoCurrencyRepository;
import ru.spbstu.cryptoadvisor.repository.TrackedCurrencyRepository;
import ru.spbstu.cryptoadvisor.repository.UserAlertRepository;
import ru.spbstu.cryptoadvisor.repository.UserRepository;

import java.util.List;

@Component
public class CryptoInformationModule {

    private final BingXService bingXService;
    private final RabbitMQService rabbitMQService;
    private final FiatConversionService fiatConversionService;
    private final CryptoCurrencyRepository cryptoCurrencyRepository;
    private final TrackedCurrencyRepository trackedCurrencyRepository;
    private final UserAlertRepository userAlertRepository;
    private final UserRepository userRepository;

    public CryptoInformationModule(BingXService bingXService,
                                   RabbitMQService rabbitMQService,
                                   FiatConversionService fiatConversionService,
                                   CryptoCurrencyRepository cryptoCurrencyRepository,
                                   TrackedCurrencyRepository trackedCurrencyRepository,
                                   UserAlertRepository userAlertRepository,
                                   UserRepository userRepository) {
        this.bingXService = bingXService;
        this.rabbitMQService = rabbitMQService;
        this.fiatConversionService = fiatConversionService;
        this.cryptoCurrencyRepository = cryptoCurrencyRepository;
        this.trackedCurrencyRepository = trackedCurrencyRepository;
        this.userAlertRepository = userAlertRepository;
        this.userRepository = userRepository;
    }

    public Mono<Double> getCurrentPrice(String symbol, String fiat) {
        return bingXService.getPrice(symbol, fiat);
    }

    public boolean addTrackedCurrency(Long userId, String symbol, Double targetPrice, String fiatSymbol) {
        String normalizedSymbol = symbol.toUpperCase();
        Long cryptoId = cryptoCurrencyRepository.findIdBySymbol(normalizedSymbol);

        if (cryptoId == null) {
            return false;
        }

        Double storedTargetPriceUsd = null;
        if (targetPrice != null && targetPrice > 0) {
            storedTargetPriceUsd = convertAmountToUsd(targetPrice, fiatSymbol);
        }

        Long exists = trackedCurrencyRepository.findIdByUserAndCrypto(userId, cryptoId);

        int trackedCurrencyId;
        if (exists == null) {
            trackedCurrencyId = trackedCurrencyRepository.insert(userId, cryptoId, storedTargetPriceUsd);

            String chatId = userRepository.findChatIdByUserId(userId).orElse(null);

            Double currentPriceUsd = getCurrentPrice(normalizedSymbol, "USD").block();
            if (currentPriceUsd == null) currentPriceUsd = 0.0;

            // 1. 24h Percent Change Task always in USD
            rabbitMQService.sendAlertCheckDelayed24h(AlertCheckMessage.percentChange(userId, chatId, trackedCurrencyId, null, normalizedSymbol, "USD", 5.0, currentPriceUsd));
        } else {
            trackedCurrencyId = exists.intValue();
        }

        // Add to user_alert table if targetPrice > 0
        if (storedTargetPriceUsd != null && storedTargetPriceUsd > 0) {
            addUserAlert(userId, normalizedSymbol, "PRICE", storedTargetPriceUsd, "USD");
        }

        return true;
    }

    public Integer addUserAlert(Long userId, String symbol, String type, Double targetValue, String fiatSymbol) {
        String normalizedSymbol = symbol.toUpperCase();
        String normalizedFiatSymbol = fiatSymbol != null ? fiatSymbol.toUpperCase() : "USD";
        Double basePrice = null;

        if ("PRICE".equalsIgnoreCase(type) && targetValue != null && targetValue > 0) {
            if (!"USD".equalsIgnoreCase(normalizedFiatSymbol)) {
                targetValue = convertAmountToUsd(targetValue, normalizedFiatSymbol);
                normalizedFiatSymbol = "USD";
            }
        }

        if ("PERCENT".equalsIgnoreCase(type)) {
            try {
                basePrice = getCurrentPrice(normalizedSymbol, normalizedFiatSymbol).block();
            } catch (Exception e) {
                // Log and ignore, will be updated by consumer later
            }
            if (basePrice == null) basePrice = 0.0;
        }

        Integer alertId = userAlertRepository.insert(userId, normalizedSymbol, type, targetValue, basePrice, normalizedFiatSymbol);

        String chatId = userRepository.findChatIdByUserId(userId).orElse(null);

        if ("PRICE".equalsIgnoreCase(type)) {
            Double currentPriceFiat = 0.0;
            try {
                Double current = getCurrentPrice(normalizedSymbol, fiatSymbol).block();
                if (current != null) currentPriceFiat = current;
            } catch (Exception e) {
                // Log and ignore
            }
            AlertCheckMessage.Direction direction = (currentPriceFiat < targetValue) ? AlertCheckMessage.Direction.UP : AlertCheckMessage.Direction.DOWN;
            rabbitMQService.sendAlertCheck(AlertCheckMessage.threshold(userId, chatId, null, alertId, normalizedSymbol, fiatSymbol, targetValue, direction));
        } else if ("PERCENT".equalsIgnoreCase(type)) {
            rabbitMQService.sendAlertCheck(AlertCheckMessage.percentChange(userId, chatId, null, alertId, normalizedSymbol, fiatSymbol, targetValue, basePrice));
        }

        return alertId;
    }

    public boolean removeUserAlert(Long userId, Integer alertId) {
        return userAlertRepository.deleteByUserAndId(userId, alertId) > 0;
    }

    public List<UserAlertInfo> getUserAlerts(Long userId) {
        return userAlertRepository.findByUserId(userId);
    }

    public boolean removeTrackedCurrency(Long userId, String symbol) {
        String normalizedSymbol = symbol.toUpperCase();
        Long cryptoId = cryptoCurrencyRepository.findIdBySymbol(normalizedSymbol);

        if (cryptoId == null) {
            return false;
        }

        return trackedCurrencyRepository.deleteByUserAndCrypto(userId, cryptoId) > 0;
    }

    public boolean updateTargetPrice(Long userId, String symbol, Double targetPrice, String fiatSymbol) {
        if (targetPrice == null || targetPrice < 0) {
            return false;
        }

        String normalizedSymbol = symbol.toUpperCase();
        Long cryptoId = cryptoCurrencyRepository.findIdBySymbol(normalizedSymbol);

        if (cryptoId == null) {
            return false;
        }

        Double storedTargetPriceUsd = targetPrice;
        if (!"USD".equalsIgnoreCase(fiatSymbol)) {
            storedTargetPriceUsd = convertAmountToUsd(targetPrice, fiatSymbol);
        }

        Integer trackedCurrencyId = trackedCurrencyRepository.updateTargetPrice(userId, cryptoId, storedTargetPriceUsd);

        if (trackedCurrencyId != null) {
            addUserAlert(userId, normalizedSymbol, "PRICE", targetPrice, fiatSymbol);
            return true;
        }

        return false;
    }

    public List<String> getTrackedCurrencies(Long userId) {
        return trackedCurrencyRepository.findSymbolsByUser(userId);
    }

    public List<TrackedCryptoInfo> getTrackedCurrencyInfo(Long userId) {
        return trackedCurrencyRepository.findInfoByUser(userId);
    }

    private Double convertAmountToUsd(Double amount, String fiatSymbol) {
        if (amount == null) {
            return null;
        }
        if (fiatSymbol == null || fiatSymbol.equalsIgnoreCase("USD")) {
            return amount;
        }
        Double rate = fiatConversionService.getFiatRate(fiatSymbol, "USD").block();
        if (rate == null || rate <= 0) {
            return amount;
        }
        return amount * rate;
    }

    public void ensureDefaultTrackedCurrency(Long userId) {
        List<String> tracked = getTrackedCurrencies(userId);
        if (tracked.isEmpty()) {
            addTrackedCurrency(userId, "BTC", 0.0, "USD");
        }
    }
}
