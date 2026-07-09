package com.example;

public class UserManagerTest {
    public void createsDisplayName() {
        UserManager manager = new UserManager();
        assert manager.displayName("ada").equals("User: ada");
    }
}
