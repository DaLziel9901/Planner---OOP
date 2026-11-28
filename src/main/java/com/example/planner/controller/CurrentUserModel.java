package com.example.planner.controller;

import com.example.planner.security.PlannerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
class CurrentUserModel {
    @ModelAttribute("currentUser")
    CurrentUser currentUser(@AuthenticationPrincipal PlannerPrincipal principal) {
        return principal == null ? null : new CurrentUser(principal.getUsername());
    }

    public record CurrentUser(String username) {}
}
