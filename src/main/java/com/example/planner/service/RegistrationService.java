package com.example.planner.service;

import com.example.planner.repository.UserRepository;
import com.example.planner.security.PasswordConfig;
import com.example.planner.security.UsernameNormalizer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RegistrationService {
    private final UserRepository users;
    private final PasswordEncoder passwords;

    public RegistrationService(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public Map<String, String> register(String username, String password, String confirmPassword) {
        String normalized = UsernameNormalizer.normalize(username);
        Map<String, String> errors = new LinkedHashMap<>();
        if (!UsernameNormalizer.isValid(normalized)) {
            errors.put("username", "Tên đăng nhập phải có 3–32 ký tự a–z, 0–9 hoặc dấu gạch dưới.");
        }
        if (!PasswordConfig.isValid(password)) {
            errors.put("password", "Mật khẩu phải có 8–64 ký tự và tối đa 72 byte UTF-8.");
        }
        if (password == null || !password.equals(confirmPassword)) {
            errors.put("confirmPassword", "Mật khẩu xác nhận không khớp.");
        }
        if (errors.isEmpty() && !users.insert(normalized, passwords.encode(password))) {
            errors.put("username", "Tên đăng nhập đã được sử dụng.");
        }
        return Map.copyOf(errors);
    }
}
