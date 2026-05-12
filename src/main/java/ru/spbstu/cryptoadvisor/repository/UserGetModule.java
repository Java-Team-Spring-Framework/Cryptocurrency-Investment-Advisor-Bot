package ru.spbstu.cryptoadvisor.repository;

import org.springframework.stereotype.Component;

import ru.spbstu.cryptoadvisor.model.User;

import java.util.Collections;
import java.util.List;

@Component
public class UserGetModule {

    public List<User> listUsers() {
        return Collections.emptyList();
    }
}
