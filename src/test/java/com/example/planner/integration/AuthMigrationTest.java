package com.example.planner.integration;

import com.example.planner.config.DatabaseTargetGuard;
import com.example.planner.model.Plan;
import com.example.planner.repository.PlanRepository;
import com.example.planner.repository.SubPlanRepository;
import com.example.planner.repository.SubTaskRepository;
import com.example.planner.repository.TaskRepository;
import com.example.planner.support.AuthTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AuthMigrationTest {
    @Test
    void upgradePreservesLegacyRowsAndCanRollBackToMvp() throws SQLException {
        assumeTrue(System.getenv("TEST_DB_URL") != null, "Requires a dedicated PostgreSQL test target");
        AuthTestDatabase.withAccountSchema(connection -> {
            assertEquals(new ArrayList<>(java.util.Collections.nCopies(4, null)), row(connection, """
                    SELECT to_regclass('public.plan'), to_regclass('public.sub_plan'),
                           to_regclass('public.task'), to_regclass('public.sub_task')
                    """), "Rehearsal requires a target without existing Planner tables");
            AuthTestDatabase.applyMvpSchema(connection);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO plan (id, title) VALUES (1, 'Legacy plan')");
                statement.executeUpdate("""
                        INSERT INTO sub_plan (id, plan_id, parent_sub_plan_id, title) VALUES
                        (1, 1, NULL, 'Phase'), (2, 1, 1, 'Nested phase')
                        """);
                statement.executeUpdate("""
                        INSERT INTO task (id, plan_id, sub_plan_id, title) VALUES
                        (1, 1, NULL, 'Direct'), (2, NULL, 2, 'Nested'), (3, NULL, NULL, 'Independent')
                        """);
                statement.executeUpdate("""
                        INSERT INTO sub_task (id, task_id, title, done, completed_at) VALUES
                        (1, 1, 'Done', true, TIMESTAMPTZ '2026-11-27 00:00:00+00'),
                        (2, 2, 'Open', false, NULL), (3, 3, 'Legacy', false, NULL)
                        """);
            }
            var baseline = snapshot(connection);
            assertThrows(SQLException.class, () -> DatabaseTargetGuard.verifyRuntimeCatalog(connection));
            verifyDdlFailureRollsBack(connection, baseline);
            var before = connection.setSavepoint();
            AuthTestDatabase.applyOwnershipMigration(connection);
            DatabaseTargetGuard.verifyRuntimeCatalog(connection);
            assertEquals(baseline, snapshot(connection));
            assertEquals(List.of("0", "0"), row(connection, """
                    SELECT (SELECT count(owner_user_id) FROM plan), (SELECT count(owner_user_id) FROM task)
                    """));

            assertRejected(connection, "INSERT INTO plan (title) VALUES ('Owner required')", "23514");
            assertRejected(connection, "INSERT INTO task (title) VALUES ('Owner required')", "23514");
            assertRejected(connection, "INSERT INTO plan (title, owner_user_id) VALUES ('Unknown owner', 99)", "23503");
            verifyOwnerlessRowsAreHidden(connection);
            connection.rollback(before);
            assertEquals(baseline, snapshot(connection));
            assertMissingOwnerColumns(connection);
            assertThrows(SQLException.class, () -> DatabaseTargetGuard.verifyRuntimeCatalog(connection));
        });
    }

    private static void verifyDdlFailureRollsBack(Connection connection, List<String> baseline) throws SQLException {
        var before = connection.setSavepoint();
        try (var statement = connection.createStatement()) {
            // The migration has added both owner columns by the time it reaches this index.
            statement.executeUpdate("CREATE INDEX plan_owner_idx ON plan (id)");
            var failure = assertThrows(ScriptStatementFailedException.class,
                    () -> AuthTestDatabase.applyOwnershipMigration(connection));
            assertEquals("42P07", assertInstanceOf(SQLException.class, failure.getCause()).getSQLState());
        } finally {
            connection.rollback(before);
            connection.releaseSavepoint(before);
        }
        assertEquals(baseline, snapshot(connection));
        assertMissingOwnerColumns(connection);
        assertEquals(java.util.Collections.singletonList(null), row(connection,
                "SELECT to_regclass('public.plan_owner_idx')"));
    }

    private static void verifyOwnerlessRowsAreHidden(Connection connection) {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        long userId = AuthTestDatabase.createFixtureAccount(jdbc);
        var plans = new PlanRepository(jdbc);
        var phases = new SubPlanRepository(jdbc);
        var tasks = new TaskRepository(jdbc);
        var leaves = new SubTaskRepository(jdbc);

        var ownPlan = new Plan("New owned plan");
        long ownPlanId = plans.insertForOwner(userId, ownPlan);
        assertEquals(List.of(ownPlanId), plans.findAllForOwner(userId).stream().map(Plan::getId).toList());
        assertTrue(plans.findByIdForOwner(userId, 1).isEmpty());
        assertTrue(phases.findByPlanIdsForOwner(userId, List.of(1L)).isEmpty());
        assertTrue(tasks.findByPlanIdsForOwner(userId, List.of(1L)).isEmpty());
        assertTrue(tasks.findIndependentForOwner(userId).isEmpty());
        assertTrue(leaves.findByTaskIdsForOwner(userId, List.of(1L, 2L, 3L)).isEmpty());
        assertFalse(plans.deleteByIdForOwner(userId, 1));
        assertFalse(tasks.deleteByIdForOwner(userId, 3));
        assertFalse(leaves.deleteByIdForOwner(userId, 1));
    }

    private static void assertMissingOwnerColumns(Connection connection) throws SQLException {
        assertEquals(List.of("0"), row(connection, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name IN ('plan', 'task') AND column_name = 'owner_user_id'
                """));
    }

    private static List<String> snapshot(Connection connection) throws SQLException {
        return row(connection, """
                SELECT
                  (SELECT jsonb_agg(to_jsonb(p) - 'owner_user_id' ORDER BY p.id) FROM plan p),
                  (SELECT jsonb_agg(s ORDER BY s.id) FROM sub_plan s),
                  (SELECT jsonb_agg(to_jsonb(t) - 'owner_user_id' ORDER BY t.id) FROM task t),
                  (SELECT jsonb_agg(l ORDER BY l.id) FROM sub_task l)
                """);
    }

    private static List<String> row(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            List<String> values = new ArrayList<>();
            for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                values.add(row.getString(column));
            }
            return values;
        }
    }

    private static void assertRejected(Connection connection, String sql, String state) throws SQLException {
        var before = connection.setSavepoint();
        try (var statement = connection.createStatement()) {
            assertEquals(state, assertThrows(SQLException.class, () -> statement.executeUpdate(sql)).getSQLState());
        } finally {
            connection.rollback(before);
            connection.releaseSavepoint(before);
        }
    }
}
