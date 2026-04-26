package ru.spbstu.cryptoadvisor;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class UserRepository {

    public List<User> findAll() {
        return new ArrayList<>();
    }
}
