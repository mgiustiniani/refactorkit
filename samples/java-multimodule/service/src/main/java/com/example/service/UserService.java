package com.example.service;

import com.example.api.UserApi;

public class UserService implements UserApi {
    @Override
    public String findName(String id) {
        return "multi-" + id;
    }
}
