package com.example.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class AccountEntity {
    @Id
    private Long id;

    public AccountEntity() {
    }
}
