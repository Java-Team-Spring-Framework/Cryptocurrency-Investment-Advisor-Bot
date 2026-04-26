package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class UserGetModule {

    public List<User> listUsers() {
        return Collections.emptyList();
    }
}
