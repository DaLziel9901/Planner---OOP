package com.example.planner.config;

import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

public final class DatabaseTargetGuard {
    private static final String DATABASE = "planner_auth_test";
    static final String RELEASE_DATABASE = "planner_release";
    private static final Pattern TARGET = Pattern.compile(
            "jdbc:postgresql://(?:[A-Za-z0-9][A-Za-z0-9.-]*|\\[[0-9A-Fa-f:]+\\])(?::([0-9]{1,5}))?/"
                    + "(" + DATABASE + "|" + RELEASE_DATABASE + ")");
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String IDENTITY_SQL = """
            SELECT pg_catalog.current_database(), pg_catalog.current_schema(), CURRENT_USER,
                   pg_catalog.cardinality(pg_catalog.current_schemas(false)) = 1,
                   rolsuper
            FROM pg_catalog.pg_roles
            WHERE rolname = CURRENT_USER
            """;
    private static final String RUNTIME_CATALOG_SQL = """
            WITH required_columns(table_name, column_name, column_type) AS (
                VALUES ('app_user', 'id', 'bigint'),
                       ('app_user', 'username', 'character varying(32)'),
                       ('app_user', 'password_hash', 'character varying(60)'),
                       ('app_user', 'created_at', 'timestamp with time zone'),
                       ('plan', 'owner_user_id', 'bigint'), ('task', 'owner_user_id', 'bigint'),
                       ('sub_plan', 'plan_id', 'bigint'), ('sub_plan', 'parent_sub_plan_id', 'bigint'),
                       ('task', 'plan_id', 'bigint'), ('task', 'sub_plan_id', 'bigint'),
                       ('sub_task', 'task_id', 'bigint'), ('sub_task', 'done', 'boolean'),
                       ('sub_task', 'completed_at', 'timestamp with time zone')
            ), required_constraints(table_name, constraint_name, constraint_type) AS (
                VALUES ('app_user', 'app_user_pkey', 'p'),
                       ('app_user', 'app_user_username_key', 'u'),
                       ('app_user', 'app_user_username_format', 'c'),
                       ('plan', 'plan_owner_fk', 'f'), ('task', 'task_owner_fk', 'f'),
                       ('plan', 'plan_owner_required', 'c'), ('task', 'task_owner_scope', 'c')
            )
            SELECT NOT EXISTS (
                SELECT 1 FROM required_columns expected
                WHERE NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_attribute actual
                    JOIN pg_catalog.pg_class relation ON relation.oid = actual.attrelid
                    JOIN pg_catalog.pg_namespace ns ON ns.oid = relation.relnamespace
                    WHERE ns.nspname = 'public' AND relation.relkind = 'r'
                      AND relation.relname = expected.table_name AND actual.attname = expected.column_name
                      AND NOT actual.attisdropped
                      AND pg_catalog.format_type(actual.atttypid, actual.atttypmod) = expected.column_type
                )
            ) AND NOT EXISTS (
                SELECT 1 FROM required_constraints expected
                WHERE NOT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_constraint actual
                    JOIN pg_catalog.pg_class relation ON relation.oid = actual.conrelid
                    JOIN pg_catalog.pg_namespace ns ON ns.oid = relation.relnamespace
                    WHERE ns.nspname = 'public' AND relation.relname = expected.table_name
                      AND actual.conname = expected.constraint_name AND actual.contype::text = expected.constraint_type
                      AND (expected.constraint_type <> 'f' OR (
                          actual.convalidated AND actual.confdeltype = 'r'
                          AND actual.confrelid = pg_catalog.to_regclass('public.app_user')))
                )
            )
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
        verifyTarget(url, role, null);
        if (environment.containsProperty("spring.datasource.jndi-name")
                || environment.containsProperty("spring.datasource.hikari.jdbc-url")) {
            throw new IllegalArgumentException("Alternate DataSource targets are disabled for Planner 003");
        }
        if (!"never".equals(environment.getProperty("spring.sql.init.mode"))) {
            throw new IllegalArgumentException("Automatic SQL initialization must be disabled for Planner 003");
        }
    }

    public static void verifySameTarget(String appUrl, String appRole, String testUrl, String testRole) {
        verifyTarget(appUrl, appRole, DATABASE);
        verifyTarget(testUrl, testRole, DATABASE);
        if (!appUrl.equals(testUrl) || !appRole.equals(testRole)) {
            throw new IllegalArgumentException("App and tests must use the same explicit Planner 003 database and role");
        }
    }

    private static String verifyTarget(String url, String role, String requiredDatabase) {
        var match = TARGET.matcher(url == null ? "" : url);
        if (!match.matches() || (requiredDatabase != null && !requiredDatabase.equals(match.group(2)))) {
            throw new IllegalArgumentException("Use a single-host JDBC URL without options for "
                    + (requiredDatabase == null ? "planner_release or planner_auth_test" : requiredDatabase));
        }
        String port = match.group(1);
        if (port != null && (Integer.parseInt(port) == 0 || Integer.parseInt(port) > 65535)) {
            throw new IllegalArgumentException("Invalid PostgreSQL port");
        }
        if (role == null || !NAME.matcher(role).matches() || "postgres".equals(role) || role.startsWith("pg_")) {
            throw new IllegalArgumentException("Set an explicit dedicated non-superuser PostgreSQL role");
        }
        return match.group(2);
    }

    public static Connection openVerified(String url, String role, String password, String schema,
                                          Connector connector) throws SQLException {
        return openVerified(url, role, password, schema, DATABASE, connector);
    }

    public static Connection openRuntime(String url, String role, String password, Connector connector) throws SQLException {
        return openVerified(url, role, password, "public", null, connector);
    }

    static Connection openRelease(String url, String role, String password, Connector connector) throws SQLException {
        return openVerified(url, role, password, "public", RELEASE_DATABASE, connector);
    }

    private static Connection openVerified(String url, String role, String password, String schema,
                                           String requiredDatabase, Connector connector) throws SQLException {
        String database = verifyTarget(url, role, requiredDatabase);
        if (schema == null || !NAME.matcher(schema).matches() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Schema and PostgreSQL credentials must be explicit");
        }

        Connection connection = connector.connect(url, role, password);
        if (connection == null) {
            throw new SQLException("Could not verify the Planner 003 connection");
        }
        try {
            verifyIdentity(connection, role, schema, database);
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
        verifyIdentity(connection, role, schema, DATABASE);
    }

    private static void verifyIdentity(Connection connection, String role, String schema, String database) throws SQLException {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery(IDENTITY_SQL)) {
            if (!row.next() || !database.equals(row.getString(1)) || !schema.equals(row.getString(2))
                    || !role.equals(row.getString(3)) || !row.getBoolean(4) || row.wasNull()
                    || row.getBoolean(5) || row.wasNull() || row.next()) {
                throw new SQLException("PostgreSQL database, schema, search path or role does not match the configured identity");
            }
        }
    }

    public static void verifyRuntimeCatalog(Connection connection) throws SQLException {
        if (!hasRuntimeCatalog(connection)) {
            throw new SQLException("Planner requires the complete 003 catalog in public before startup");
        }
    }

    static boolean hasRuntimeCatalog(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery(RUNTIME_CATALOG_SQL)) {
            return row.next() && row.getBoolean(1);
        }
    }
}
