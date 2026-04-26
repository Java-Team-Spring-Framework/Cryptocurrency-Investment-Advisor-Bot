package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Component;

@Component
public class AuthAdminModule {

    public void validateBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Invalid admin authorization header");
        }

        String token = authorizationHeader.substring("Bearer ".length());
        String expectedToken = System.getenv("API_TOKEN");
        if (expectedToken == null || !expectedToken.equals(token)) {
            throw new IllegalArgumentException("Invalid admin token");
        }
    }
}
