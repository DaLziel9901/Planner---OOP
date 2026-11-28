package com.example.planner.model;

import java.time.OffsetDateTime;

public record AppUser(long id, String username, String passwordHash, OffsetDateTime createdAt) {
    @Override
    public String toString() {
        return "AppUser[id=" + id + "]";
    }
}
