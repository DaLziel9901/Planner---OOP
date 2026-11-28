package com.example.planner.support;

import com.example.planner.config.DatabaseTargetGuard;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

public final class AuthTestDatabase {
    private static final String SCHEMA = "public";

    private AuthTestDatabase() {
    }

    public static Connection openConnection() throws SQLException {
        return settings().open();
    }

    public static long createFixtureAccount(JdbcTemplate jdbc) {
        String username = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String hash = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
        return jdbc.queryForObject("""
                INSERT INTO public.app_user (username, password_hash) VALUES (?, ?) RETURNING id
                """, Long.class, username, hash);
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
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/fixtures/mvp-baseline.sql"));
    }

    public static void applyPlannerSchema(Connection connection) throws SQLException {
        applyPlannerSql(connection, "schema.sql");
    }

    public static void initializeEmptyPlannerSchema() throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement();
                     var tables = statement.executeQuery("""
                             SELECT 1 FROM pg_catalog.pg_class c
                             JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                             WHERE n.nspname = 'public'
                               AND c.relname IN ('app_user', 'plan', 'sub_plan', 'task', 'sub_task')
                             """)) {
                    if (tables.next()) {
                        throw new SQLException("Planner bootstrap requires public without existing Planner relations");
                    }
                }
                applyPlannerSql(connection, "db/bootstrap/app-user.sql");
                applyPlannerSchema(connection);
                connection.commit();
            } finally {
                connection.rollback();
            }
        }
    }

    public static void applyOwnershipMigration(Connection connection) throws SQLException {
        applyPlannerSql(connection, "db/migration/V003__user_ownership.sql");
    }

    private static void applyPlannerSql(Connection connection, String resource) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new SQLException("Planner schema scripts require a transaction");
        }
        DatabaseTargetGuard.verifyIdentity(connection, settings().role(), SCHEMA);
        verifyAccountBootstrapPrivileges(connection);
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(resource));
    }

    @FunctionalInterface
    public interface AccountFixture {
        void run(Connection connection) throws SQLException;
    }

    public static void withAccountSchema(AccountFixture fixture) throws SQLException {
        Settings target = settings();
        try (Connection connection = target.open()) {
            connection.setAutoCommit(false);
            try {
                DatabaseTargetGuard.verifyIdentity(connection, target.role(), SCHEMA);
                verifyAccountBootstrapPrivileges(connection);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/bootstrap/app-user.sql"));
                fixture.run(connection);
            } finally {
                // This transaction contains only this invocation's DDL and fixtures.
                connection.rollback();
            }
        }
    }

    private static void verifyAccountBootstrapPrivileges(Connection connection) throws SQLException {
        String sql = """
                SELECT SESSION_USER = CURRENT_USER
                    AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
                    AND pg_catalog.current_setting('transaction_read_only') = 'off'
                    AND pg_catalog.has_database_privilege(CURRENT_USER, pg_catalog.current_database(), 'CONNECT')
                    AND pg_catalog.has_schema_privilege(CURRENT_USER, 'public', 'USAGE')
                    AND pg_catalog.has_schema_privilege(CURRENT_USER, 'public', 'CREATE')
                    AND NOT EXISTS (
                        SELECT 1 FROM pg_catalog.pg_database db
                        WHERE db.datname = 'planner'
                          AND pg_catalog.has_database_privilege(CURRENT_USER, db.oid, 'CONNECT')
                    )
                FROM pg_catalog.pg_roles
                WHERE rolname = CURRENT_USER
                """;
        try (var statement = connection.createStatement();
             var row = statement.executeQuery(sql)) {
            if (!row.next() || !row.getBoolean(1)) {
                throw new SQLException("Account bootstrap requires a dedicated role with CONNECT on planner_auth_test, "
                        + "USAGE and CREATE on public, and no CONNECT on planner or elevated role attributes");
            }
        }
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
            Connection connection = DatabaseTargetGuard.openVerified(
                    url, role, System.getenv("TEST_DB_PASSWORD"), SCHEMA, DriverManager::getConnection);
            try {
                verifyAccountBootstrapPrivileges(connection);
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
    }
}
