package com.example.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class UserEntity {
    @Id
    private Long id;

    public UserEntity() {
    }
}
