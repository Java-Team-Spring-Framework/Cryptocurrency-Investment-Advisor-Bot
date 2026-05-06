package ru.spbstu.cryptoadvisor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthAdminModule {

    public void validateBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Missing or invalid Authorization header");
        }

        String token = authorizationHeader.substring("Bearer ".length()).trim();
        String expectedToken = System.getenv("API_TOKEN");
        if (expectedToken == null || expectedToken.isBlank() || !expectedToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin token");
        }
    }
}
