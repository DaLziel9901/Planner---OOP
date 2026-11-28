package com.example.planner.repository;

import com.example.planner.service.RegistrationService;
import com.example.planner.support.AuthTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class UserRepositoryTest {
    @Test
    void concurrentNormalizedRegistrationsLeaveOneAccountWithVerifiableHash() throws Exception {
        var jdbc = new JdbcTemplate(AuthTestDatabase.dataSource());
        var users = new UserRepository(jdbc);
        var encoder = new BCryptPasswordEncoder();
        var registration = new RegistrationService(users, encoder);
        String name = "race_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String password = " " + UUID.randomUUID() + " ";
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return registration.register(name, password, password);
            });
            var second = pool.submit(() -> {
                start.await();
                return registration.register(" " + name.toUpperCase(Locale.ROOT) + " ", password, password);
            });
            start.countDown();
            var firstResult = first.get(15, TimeUnit.SECONDS);
            var secondResult = second.get(15, TimeUnit.SECONDS);
            assertTrue(firstResult.isEmpty() ^ secondResult.isEmpty());
            assertEquals(java.util.Set.of("username"), (firstResult.isEmpty() ? secondResult : firstResult).keySet());

            var saved = users.findByUsername(name).orElseThrow();
            assertTrue(saved.id() > 0);
            assertNotNull(saved.createdAt());
            assertTrue(encoder.matches(password, saved.passwordHash()));
            assertFalse(encoder.matches(password.trim(), saved.passwordHash()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public.app_user WHERE username = ?",
                    Integer.class, name));
        } finally {
            pool.shutdownNow();
            if (pool.awaitTermination(20, TimeUnit.SECONDS)) {
                users.findByUsername(name).ifPresent(account ->
                        jdbc.update("DELETE FROM public.app_user WHERE id = ?", account.id()));
            }
        }
    }
}
