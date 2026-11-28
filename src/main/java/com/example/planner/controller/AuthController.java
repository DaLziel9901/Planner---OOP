package com.example.planner.controller;

import com.example.planner.service.RegistrationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@Controller
public class AuthController {
    private final RegistrationService registration;

    public AuthController(RegistrationService registration) {
        this.registration = registration;
    }

    @GetMapping("/register")
    String registerForm(Model model) {
        return registrationForm("", Map.of(), model);
    }

    @PostMapping("/register")
    String register(@RequestParam(defaultValue = "") String username,
                    @RequestParam(defaultValue = "") String password,
                    @RequestParam(defaultValue = "") String confirmPassword, Model model) {
        Map<String, String> errors = registration.register(username, password, confirmPassword);
        return errors.isEmpty() ? "redirect:/login" : registrationForm(username, errors, model);
    }

    @GetMapping("/login")
    String login() {
        return "auth/login";
    }

    @GetMapping("/")
    String home() {
        return "redirect:/plans";
    }

    private String registrationForm(String username, Map<String, String> errors, Model model) {
        model.addAttribute("form", Map.of("username", username));
        model.addAttribute("errors", errors);
        return "auth/register";
    }
}
