package com.example;

public class UserService {
    private final UserRepository repository = new UserRepository();

    public String findName(String id) {
        return repository.findName(id);
    }
}
