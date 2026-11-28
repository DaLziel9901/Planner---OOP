package com.example.planner.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DatabaseTargetGuardTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/planner_auth_test";
    private static final String RELEASE_URL = "jdbc:postgresql://localhost:5432/planner_release";
    private static final String ROLE = "planner_auth_test_user";
    private static final String SCHEMA = "public";

    @Test
    void releaseRuntimeChecksIdentityAndTestOrBootstrapEntryPointsStaySeparate() throws SQLException {
        var connector = mock(DatabaseTargetGuard.Connector.class);
        assertThrows(IllegalArgumentException.class, () -> open(RELEASE_URL, ROLE, SCHEMA, connector));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseTargetGuard.verifySameTarget(RELEASE_URL, ROLE, RELEASE_URL, ROLE));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseTargetGuard.openRelease(URL, ROLE, "fixture", connector));
        verifyNoInteractions(connector);

        var correct = connection("planner_release", SCHEMA, ROLE, true, false);
        var wrong = connection("planner_auth_test", SCHEMA, ROLE, true, false);
        when(connector.connect(RELEASE_URL, ROLE, "fixture")).thenReturn(correct, wrong);
        assertSame(correct, DatabaseTargetGuard.openRuntime(RELEASE_URL, ROLE, "fixture", connector));
        assertThrows(SQLException.class,
                () -> DatabaseTargetGuard.openRelease(RELEASE_URL, ROLE, "fixture", connector));
        verify(wrong).close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "jdbc:postgresql://localhost:5432/planner", "jdbc:h2:mem:planner_auth_test",
        "jdbc:postgresql:planner_auth_test", "jdbc:postgresql://a,b/planner_auth_test",
        "jdbc:postgresql://localhost:0/planner_auth_test",
        "jdbc:postgresql://localhost:65536/planner_auth_test",
        "jdbc:postgresql://localhost/planner_auth_test?PGDBNAME=planner",
        "jdbc:postgresql://localhost/planner_auth_test?currentSchema=other",
        "jdbc:postgresql://user@localhost/planner_auth_test",
        "jdbc:postgresql://localhost/planner_auth_test_extra"
    })
    void rejectsBadUrlBeforeCallingConnector(String url) throws SQLException {
        var connector = mock(DatabaseTargetGuard.Connector.class);
        assertThrows(IllegalArgumentException.class, () -> open(url, ROLE, SCHEMA, connector));
        verifyNoInteractions(connector);
    }

    @Test
    void allowsOnlyExplicitTargetAndVerifiedConnection() throws SQLException {
        var connector = mock(DatabaseTargetGuard.Connector.class);
        var connection = connection("planner_auth_test", SCHEMA, ROLE, true, false);
        when(connector.connect(URL, ROLE, "fixture")).thenReturn(connection);

        assertSame(connection, open(URL, ROLE, SCHEMA, connector));
        verify(connector).connect(URL, ROLE, "fixture");
        verify(connection).createStatement();
        verify(connection, never()).close();
    }

    @Test
    void rejectsEffectivePropertyOverrideAndCompetingConfiguration() {
        var environment = new MockEnvironment()
                .withProperty("DB_URL", URL)
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/planner")
                .withProperty("spring.datasource.username", ROLE)
                .withProperty("spring.sql.init.mode", "never");
        assertThrows(IllegalArgumentException.class, () -> DatabaseTargetGuard.verifyRuntimeSettings(environment));
        environment.setProperty("spring.datasource.url", URL);
        assertDoesNotThrow(() -> DatabaseTargetGuard.verifyRuntimeSettings(environment));
        environment.getPropertySources().addFirst(new MapPropertySource("fixtureOverride",
                Map.of("spring.datasource.url", "jdbc:postgresql://localhost/planner")));
        assertThrows(IllegalArgumentException.class, () -> DatabaseTargetGuard.verifyRuntimeSettings(environment));
        environment.getPropertySources().remove("fixtureOverride");
        environment.setProperty("spring.datasource.hikari.jdbc-url", "jdbc:postgresql://localhost/planner");
        assertThrows(IllegalArgumentException.class, () -> DatabaseTargetGuard.verifyRuntimeSettings(environment));
        var initOverride = new MockEnvironment()
                .withProperty("spring.datasource.url", URL)
                .withProperty("spring.datasource.username", ROLE)
                .withProperty("spring.sql.init.mode", "always");
        assertThrows(IllegalArgumentException.class, () -> DatabaseTargetGuard.verifyRuntimeSettings(initOverride));
        var namingOverride = new MockEnvironment()
                .withProperty("spring.datasource.url", URL)
                .withProperty("spring.datasource.username", ROLE)
                .withProperty("spring.sql.init.mode", "never")
                .withProperty("spring.datasource.jndi-name", "jdbc/fixture");
        assertThrows(IllegalArgumentException.class, () -> DatabaseTargetGuard.verifyRuntimeSettings(namingOverride));
    }

    @Test
    void rejectsDifferentTestTargetsBeforeOpeningConnection() {
        assertDoesNotThrow(() -> DatabaseTargetGuard.verifySameTarget(URL, ROLE, URL, ROLE));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseTargetGuard.verifySameTarget(URL, ROLE, URL.replace("5432", "5433"), ROLE));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseTargetGuard.verifySameTarget(URL, ROLE, URL, "other_user"));
    }

    @Test
    void rejectsUnexpectedIdentityOrSearchPathAndClosesConnection() throws SQLException {
        for (var connection : new Connection[] {
                connection("planner", SCHEMA, ROLE, true, false),
                connection("planner_auth_test", "different_schema", ROLE, true, false),
                connection("planner_auth_test", SCHEMA, "different_role", true, false),
                connection("planner_auth_test", SCHEMA, ROLE, false, false),
                connection("planner_auth_test", SCHEMA, ROLE, true, true),
                connection("planner_auth_test", SCHEMA, null, true, false)
        }) {
            assertThrows(SQLException.class, () -> open(URL, ROLE, SCHEMA, (url, user, password) -> connection));
            verify(connection).close();
        }
    }

    @Test
    void rejectsMissingIdentityRowAndQueryFailure() throws SQLException {
        var missing = mock(Connection.class, RETURNS_DEEP_STUBS);
        assertThrows(SQLException.class, () -> open(URL, ROLE, SCHEMA, (url, user, password) -> missing));
        verify(missing).close();

        var failed = mock(Connection.class);
        when(failed.createStatement()).thenThrow(new SQLException("fixture metadata failure"));
        assertThrows(SQLException.class, () -> open(URL, ROLE, SCHEMA, (url, user, password) -> failed));
        verify(failed).close();
    }

    @Test
    void rejectsMissingOrUnsafeIdentityConfigurationBeforeConnecting() throws SQLException {
        var connector = mock(DatabaseTargetGuard.Connector.class);
        assertThrows(IllegalArgumentException.class, () -> open(URL, null, SCHEMA, connector));
        assertThrows(IllegalArgumentException.class, () -> open(URL, "postgres", SCHEMA, connector));
        assertThrows(IllegalArgumentException.class, () -> open(URL, ROLE, "", connector));
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseTargetGuard.openVerified(URL, ROLE, null, SCHEMA, connector));
        verifyNoInteractions(connector);
    }

    @Test
    void runtimeCatalogMustReportComplete003Schema() throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var row = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("required_constraints"))).thenReturn(row);
        when(row.next()).thenReturn(true);
        when(row.getBoolean(1)).thenReturn(false, true);
        assertThrows(SQLException.class, () -> DatabaseTargetGuard.verifyRuntimeCatalog(connection));
        assertDoesNotThrow(() -> DatabaseTargetGuard.verifyRuntimeCatalog(connection));
    }

    private static Connection open(String url, String role, String schema, DatabaseTargetGuard.Connector connector)
            throws SQLException {
        return DatabaseTargetGuard.openVerified(url, role, "fixture", schema, connector);
    }

    private static Connection connection(String database, String schema, String role, boolean singleSchema,
                                         boolean superuser) throws SQLException {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString(1)).thenReturn(database);
        when(result.getString(2)).thenReturn(schema);
        when(result.getString(3)).thenReturn(role);
        when(result.getBoolean(4)).thenReturn(singleSchema);
        when(result.getBoolean(5)).thenReturn(superuser);
        return connection;
    }
}
