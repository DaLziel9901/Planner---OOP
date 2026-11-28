package com.example.planner.integration;

import com.example.planner.support.AuthTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthDatabaseFoundationTest {
    @Test
    void guardedTransactionBootstrapsAccountTableAndEnforcesUsernameConstraints() throws SQLException {
        AuthTestDatabase.withAccountSchema(connection -> {
            assertFalse(connection.getAutoCommit());
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("""
                         SELECT pg_catalog.current_database(), pg_catalog.current_schema(), CURRENT_USER
                         """)) {
                assertTrue(row.next());
                assertEquals("planner_auth_test", row.getString(1));
                assertEquals("public", row.getString(2));
                assertTrue(System.getenv("TEST_DB_USERNAME").equals(row.getString(3)), "Unexpected PostgreSQL role");
            }

            String hash = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
            // PostgreSQL keeps CREATE TABLE uncommitted, so this insert also checks connection reuse.
            long firstId = insertAccount(connection, "a_0", hash);
            long secondId = insertAccount(connection, "a".repeat(32), hash);
            assertTrue(firstId > 0 && secondId > 0 && firstId != secondId);

            assertRejected(connection, "a_0", hash, "23505");
            assertRejected(connection, "ab", hash, "23514");
            assertRejected(connection, "aB0", hash, "23514");
            assertRejected(connection, "éab", hash, "23514");
            assertRejected(connection, "abc\n", hash, "23514");
            assertRejected(connection, "a".repeat(33), hash, "22001");
        });
    }

    private static long insertAccount(Connection connection, String username, String hash) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO public.app_user (username, password_hash)
                VALUES (?, ?)
                RETURNING id, created_at = CURRENT_TIMESTAMP
                """)) {
            statement.setString(1, username);
            statement.setString(2, hash);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertTrue(row.getBoolean(2), "created_at must default to the database transaction timestamp");
                return row.getLong(1);
            }
        }
    }

    private static void assertRejected(Connection connection, String username, String hash, String sqlState)
            throws SQLException {
        var savepoint = connection.setSavepoint();
        try {
            var failure = assertThrows(SQLException.class, () -> insertAccount(connection, username, hash));
            assertEquals(sqlState, failure.getSQLState());
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }
}
