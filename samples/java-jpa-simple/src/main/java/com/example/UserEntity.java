package com.example;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    private Long id;

    @Column(name = "username")
    private String username;

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }
}
