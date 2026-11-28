package com.example.planner.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.sql.SQLException;

public final class ReleaseDatabaseBootstrap {
    private ReleaseDatabaseBootstrap() {}

    public static void initialize(String url, String role, String password) throws SQLException {
        try (var connection = DatabaseTargetGuard.openRelease(url, role, password, DriverManager::getConnection)) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_catalog.pg_advisory_xact_lock(631492220)");
                }
                if (DatabaseTargetGuard.hasRuntimeCatalog(connection)) {
                    connection.commit();
                    System.out.println("Planner release schema already initialized.");
                    return;
                }
                try (var statement = connection.createStatement();
                     var objects = statement.executeQuery("""
                             SELECT EXISTS (
                                 SELECT 1 FROM pg_catalog.pg_class c
                                 JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                                 WHERE n.nspname = 'public'
                             )
                             """)) {
                    if (!objects.next() || objects.getBoolean(1)) {
                        throw new SQLException("Planner bootstrap requires an empty public schema. Existing data was left intact.");
                    }
                }
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/bootstrap/app-user.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
                DatabaseTargetGuard.verifyRuntimeCatalog(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
        System.out.println("Planner release schema initialized.");
    }
}
