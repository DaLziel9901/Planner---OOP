package com.example.planner.support;

import com.example.planner.config.DatabaseTargetGuard;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class AuthTestDatabase {
    private static final String SCHEMA = "public";

    private AuthTestDatabase() {
    }

    public static Connection openConnection() throws SQLException {
        return settings().open();
    }

    public static DataSource dataSource() {
        Settings target = settings();
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return target.open();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("Connection-specific credential overrides are disabled for Planner tests");
            }
        };
    }

    public static void applyMvpSchema(Connection connection) throws SQLException {
        Settings target = settings();
        DatabaseTargetGuard.verifyIdentity(connection, target.role(), SCHEMA);
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
    }

    public static void registerApplicationProperties(DynamicPropertyRegistry properties) {
        Settings target = settings();
        properties.add("spring.datasource.url", target::url);
        properties.add("spring.datasource.username", target::role);
        properties.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    private static Settings settings() {
        String url = System.getenv("TEST_DB_URL");
        String role = System.getenv("TEST_DB_USERNAME");
        DatabaseTargetGuard.verifySameTarget(System.getenv("DB_URL"), System.getenv("DB_USERNAME"), url, role);
        String appPassword = System.getenv("DB_PASSWORD");
        String testPassword = System.getenv("TEST_DB_PASSWORD");
        if (appPassword == null || appPassword.isEmpty() || !appPassword.equals(testPassword)) {
            throw new IllegalArgumentException("Set matching explicit DB_PASSWORD and TEST_DB_PASSWORD");
        }
        return new Settings(url, role);
    }

    private record Settings(String url, String role) {
        Connection open() throws SQLException {
            return DatabaseTargetGuard.openVerified(
                    url, role, System.getenv("TEST_DB_PASSWORD"), SCHEMA, DriverManager::getConnection);
        }
    }
}
