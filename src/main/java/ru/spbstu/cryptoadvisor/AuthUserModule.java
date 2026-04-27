package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Component;

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
