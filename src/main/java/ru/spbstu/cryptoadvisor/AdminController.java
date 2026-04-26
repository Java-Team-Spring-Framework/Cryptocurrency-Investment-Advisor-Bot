package ru.spbstu.cryptoadvisor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final AuthAdminModule authAdminModule;

    public AdminController(UserRepository userRepository, AuthAdminModule authAdminModule) {
        this.userRepository = userRepository;
        this.authAdminModule = authAdminModule;
    }

    @GetMapping("/users")
    public Flux<User> getAllUsers(@RequestHeader("Authorization") String authorization) {
        authAdminModule.validateBearerToken(authorization);
        return Flux.fromIterable(userRepository.findAll());
    }
}
