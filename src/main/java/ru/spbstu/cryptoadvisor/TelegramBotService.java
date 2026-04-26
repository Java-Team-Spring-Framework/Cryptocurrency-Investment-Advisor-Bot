package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Service;

@Service
public class TelegramBotService {

    private final String botToken;

    public TelegramBotService() {
        this.botToken = System.getenv("TELEGRAM_BOT_TOKEN");
    }

    public String getBotToken() {
        return botToken;
    }

    public void start() {
        // TODO: зарегистрировать слушателей Telegram и отправлять события в модули приложения
    }
}
