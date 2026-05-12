package ru.spbstu.cryptoadvisor.service;

import org.springframework.stereotype.Component;

import ru.spbstu.cryptoadvisor.model.User;
import ru.spbstu.cryptoadvisor.repository.UserRepository;

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
