package com.example.planner.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public final class PlannerPrincipal extends User {
    private final long id;

    public PlannerPrincipal(long id, String username, String passwordHash) {
        super(username, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.id = id;
    }

    public long id() {
        return id;
    }
}
