package com.example.planner.config;

import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

public final class DatabaseTargetGuard {
    private static final String DATABASE = "planner_auth_test";
    private static final Pattern TARGET = Pattern.compile(
            "jdbc:postgresql://(?:[A-Za-z0-9][A-Za-z0-9.-]*|\\[[0-9A-Fa-f:]+\\])(?::([0-9]{1,5}))?/"
                    + DATABASE);
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String IDENTITY_SQL = """
            SELECT pg_catalog.current_database(), pg_catalog.current_schema(), CURRENT_USER,
                   pg_catalog.cardinality(pg_catalog.current_schemas(false)) = 1,
                   rolsuper
            FROM pg_catalog.pg_roles
            WHERE rolname = CURRENT_USER
            """;

    private DatabaseTargetGuard() {
    }

    @FunctionalInterface
    public interface Connector {
        Connection connect(String url, String role, String password) throws SQLException;
    }

    public static void verifyRuntimeSettings(Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        String role = environment.getProperty("spring.datasource.username");
        verifyTarget(url, role);
        if (environment.containsProperty("spring.datasource.jndi-name")
                || environment.containsProperty("spring.datasource.hikari.jdbc-url")) {
            throw new IllegalArgumentException("Alternate DataSource targets are disabled for Planner 003");
        }
        if (!"never".equals(environment.getProperty("spring.sql.init.mode"))) {
            throw new IllegalArgumentException("Automatic SQL initialization must be disabled for Planner 003");
        }
    }

    public static void verifySameTarget(String appUrl, String appRole, String testUrl, String testRole) {
        verifyTarget(appUrl, appRole);
        verifyTarget(testUrl, testRole);
        if (!appUrl.equals(testUrl) || !appRole.equals(testRole)) {
            throw new IllegalArgumentException("App and tests must use the same explicit Planner 003 database and role");
        }
    }

    private static void verifyTarget(String url, String role) {
        var match = TARGET.matcher(url == null ? "" : url);
        if (!match.matches()) {
            throw new IllegalArgumentException("Use an explicit single-host JDBC URL for planner_auth_test without URL options");
        }
        String port = match.group(1);
        if (port != null && (Integer.parseInt(port) == 0 || Integer.parseInt(port) > 65535)) {
            throw new IllegalArgumentException("Invalid PostgreSQL port");
        }
        if (role == null || !NAME.matcher(role).matches() || "postgres".equals(role) || role.startsWith("pg_")) {
            throw new IllegalArgumentException("Set an explicit dedicated PostgreSQL test role");
        }
    }

    public static Connection openVerified(String url, String role, String password, String schema,
                                          Connector connector) throws SQLException {
        verifyTarget(url, role);
        if (schema == null || !NAME.matcher(schema).matches() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Schema and PostgreSQL credentials must be explicit");
        }

        Connection connection = connector.connect(url, role, password);
        if (connection == null) {
            throw new SQLException("Could not verify the Planner 003 connection");
        }
        try {
            verifyIdentity(connection, role, schema);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public static void verifyIdentity(Connection connection, String role, String schema) throws SQLException {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery(IDENTITY_SQL)) {
            if (!row.next() || !DATABASE.equals(row.getString(1)) || !schema.equals(row.getString(2))
                    || !role.equals(row.getString(3)) || !row.getBoolean(4) || row.wasNull()
                    || row.getBoolean(5) || row.wasNull() || row.next()) {
                throw new SQLException("PostgreSQL database, schema, search path or role is not the configured test identity");
            }
        }
    }
}
