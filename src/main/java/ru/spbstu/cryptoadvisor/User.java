package ru.spbstu.cryptoadvisor;

public class User {
    private Long id;
    private String telegramId;
    private String username;

    public User() {
    }

    public User(Long id, String telegramId, String username) {
        this.id = id;
        this.telegramId = telegramId;
        this.username = username;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTelegramId() {
        return telegramId;
    }

    public void setTelegramId(String telegramId) {
        this.telegramId = telegramId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
