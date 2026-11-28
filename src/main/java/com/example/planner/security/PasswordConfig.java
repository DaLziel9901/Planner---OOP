package com.example.planner.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
public class PasswordConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        var bcrypt = new BCryptPasswordEncoder();
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return bcrypt.encode(rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return rawPassword != null && isValid(rawPassword.toString())
                        && bcrypt.matches(rawPassword, encodedPassword);
            }
        };
    }

    public static boolean isValid(String password) {
        if (password == null || password.length() > 128) {
            return false;
        }
        int length = password.codePointCount(0, password.length());
        return length >= 8 && length <= 64 && StandardCharsets.UTF_8.newEncoder().canEncode(password)
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
