package ru.spbstu.cryptoadvisor.auth;

import org.springframework.stereotype.Component;

import ru.spbstu.cryptoadvisor.users.User;
import ru.spbstu.cryptoadvisor.users.UserRepository;

@Component
public class AuthUserModule {

    private final UserRepository userRepository;

    public AuthUserModule(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User registerUser(String telegramId, String username) {
        return userRepository.findByChatId(telegramId)
                .orElseGet(() -> userRepository.save(new User(null, telegramId, username)));
    }
}
