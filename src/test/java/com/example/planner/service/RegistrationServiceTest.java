package com.example.planner.service;

import com.example.planner.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final RegistrationService registration = new RegistrationService(users, encoder);

    @Test
    void storesNormalizedUsernameAndBcryptOfUnmodifiedPassword() {
        var hash = ArgumentCaptor.forClass(String.class);
        when(users.insert(eq("alice_7"), anyString())).thenReturn(true);

        assertTrue(registration.register("  ALICE_7 ", " valid password ", " valid password ").isEmpty());
        verify(users).insert(eq("alice_7"), hash.capture());
        assertTrue(encoder.matches(" valid password ", hash.getValue()));
        assertFalse(encoder.matches("valid password", hash.getValue()));
    }

    @Test
    void reportsConflictOnUsernameWithoutReturningCredentials() {
        var result = registration.register("Alice", "example password", "example password");
        assertEquals(1, result.size());
        assertTrue(result.containsKey("username"));
        assertFalse(result.toString().contains("example password"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "abcdefghijklmnopqrstuvwxyz1234567", "a-b", "téa"})
    void rejectsInvalidUsernameBeforeRepository(String username) {
        assertTrue(registration.register(username, "password", "password").containsKey("username"));
        verifyNoInteractions(users);
    }

    @Test
    void validatesCharacterAndUtf8LimitsBeforeHashing() {
        String seventyTwoBytes = "é".repeat(36);
        String seventyThreeBytes = seventyTwoBytes + "a";
        when(users.insert(anyString(), anyString())).thenReturn(true);

        assertTrue(registration.register("abc", "x".repeat(7), "x".repeat(7)).containsKey("password"));
        assertTrue(registration.register("abc", "x".repeat(65), "x".repeat(65)).containsKey("password"));
        assertTrue(registration.register("abc", seventyThreeBytes, seventyThreeBytes).containsKey("password"));
        assertTrue(registration.register("abc", "password ", "password").containsKey("confirmPassword"));
        verifyNoInteractions(users);
        assertTrue(registration.register("abc", "x".repeat(8), "x".repeat(8)).isEmpty());
        assertTrue(registration.register("a".repeat(32), "x".repeat(64), "x".repeat(64)).isEmpty());
        assertTrue(registration.register("abc", seventyTwoBytes, seventyTwoBytes).isEmpty());
    }
}
