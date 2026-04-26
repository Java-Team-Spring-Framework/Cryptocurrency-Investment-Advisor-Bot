package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Component;

@Component
public class AuthUserModule {

    public User registerUser(String telegramId, String username) {
        User user = new User(null, telegramId, username);
        // TODO: сохранить пользователя в PostgreSQL и вернуть объект
        return user;
    }
}
