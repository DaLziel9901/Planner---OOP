package com.example.planner.controller;

import com.example.planner.security.PlannerPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

final class RequestOwner {
    private RequestOwner() {}

    static long require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof PlannerPrincipal principal) || principal.id() <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Yêu cầu tài khoản xác thực");
        }
        return principal.id();
    }
}
