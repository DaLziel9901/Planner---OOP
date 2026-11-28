package com.example.planner.controller;

import com.example.planner.security.PlannerPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

abstract class AuthenticatedControllerTest {
    static final long OWNER_ID = 42L;

    @BeforeEach
    void installPrincipal() {
        var principal = new PlannerPrincipal(OWNER_ID, "alice", "");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }
}
