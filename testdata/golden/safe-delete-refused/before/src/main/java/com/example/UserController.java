package com.example;

public class UserController {
    private UserManager userManager = new UserManager();

    public String handle() {
        return userManager.getUser();
    }
}
