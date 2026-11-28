package com.example.planner.security;

import java.util.Locale;
import java.util.regex.Pattern;

public final class UsernameNormalizer {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{3,32}");

    private UsernameNormalizer() {
    }

    public static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String username) {
        return USERNAME.matcher(username).matches();
    }
}
