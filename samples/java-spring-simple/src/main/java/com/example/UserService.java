package com.example;

import org.springframework.stereotype.Service;

@Service
public class UserService {
    public String findName(String id) {
        return "spring-user-" + id;
    }
}
