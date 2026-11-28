package com.example.planner.repository;

import com.example.planner.model.Plan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.planner.support.AuthTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PlanRepositoryTest {
    private long ownerId;
    private Connection connection;
    private JdbcTemplate jdbc;
    private PlanRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connection = AuthTestDatabase.openConnection();
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        ownerId = AuthTestDatabase.createFixtureAccount(jdbc);
        repository = new PlanRepository(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void insertReadUpdateDeleteAndPreserveCreationTime() {
        Plan input = new Plan("JUnit Plan");
        long id = repository.insertForOwner(ownerId, input);
        assertTrue(id > 0);
        Plan saved = repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertEquals("JUnit Plan", saved.getTitle());
        assertNull(saved.getGoal());
        assertNull(saved.getStartDate());
        assertNull(saved.getEndDate());
        assertNotNull(saved.getCreatedAt());

        var created = saved.getCreatedAt();
        LocalDate start = LocalDate.of(2026, 11, 1);
        saved.setTitle("JUnit Updated");
        saved.setGoal("730+");
        saved.setDates(start, start.plusDays(1));
        assertTrue(repository.updateForOwner(ownerId, saved));
        Plan updated = repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertEquals("JUnit Updated", updated.getTitle());
        assertEquals("730+", updated.getGoal());
        assertEquals(start, updated.getStartDate());
        assertEquals(start.plusDays(1), updated.getEndDate());
        assertEquals(created.toInstant(), updated.getCreatedAt().toInstant());
        updated.setGoal(null);
        updated.setDates(null, null);
        assertTrue(repository.updateForOwner(ownerId, updated));
        assertNull(repository.findByIdForOwner(ownerId, id).orElseThrow().getStartDate());
        assertNull(repository.findByIdForOwner(ownerId, id).orElseThrow().getGoal());
        assertTrue(repository.deleteByIdForOwner(ownerId, id));
        assertTrue(repository.findByIdForOwner(ownerId, id).isEmpty());
        assertFalse(repository.updateForOwner(ownerId, updated));
        assertFalse(repository.deleteByIdForOwner(ownerId, id));
    }

    @Test
    void duplicateTitlesAndSqlLikeTextAreStoredAsData() {
        String title = "JUnit '); DROP TABLE plan; --";
        long first = repository.insertForOwner(ownerId, new Plan(title));
        long second = repository.insertForOwner(ownerId, new Plan(title));
        assertNotEquals(first, second);
        assertEquals(title, repository.findByIdForOwner(ownerId, first).orElseThrow().getTitle());
        List<Long> ids = repository.findAllForOwner(ownerId).stream().map(Plan::getId).toList();
        // CURRENT_TIMESTAMP giống nhau trong cùng giao dịch, nên ID lớn đứng trước.
        assertTrue(ids.indexOf(second) < ids.indexOf(first));
    }

    @Test
    void deleteCascadesThroughNestedSubPlansAndBothTaskLocations() {
        long planId = repository.insertForOwner(ownerId, new Plan("JUnit cascade"));
        Long root = jdbc.queryForObject("INSERT INTO sub_plan(plan_id,title) VALUES (?,?) RETURNING id",
                Long.class, planId, "Tầng 1");
        Long child = jdbc.queryForObject("INSERT INTO sub_plan(plan_id,parent_sub_plan_id,title) VALUES (?,?,?) RETURNING id",
                Long.class, planId, root, "Tầng 2");
        Long nestedTask = jdbc.queryForObject("INSERT INTO task(sub_plan_id,title) VALUES (?,?) RETURNING id",
                Long.class, child, "Task trong nhánh");
        Long directTask = jdbc.queryForObject("INSERT INTO task(plan_id,title) VALUES (?,?) RETURNING id",
                Long.class, planId, "Task trực tiếp");
        Long freeTask = jdbc.queryForObject("INSERT INTO task(owner_user_id,title) VALUES (?,?) RETURNING id",
                Long.class, ownerId, "Task tự do cần giữ");
        Long nestedLeaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id",
                Long.class, nestedTask, "Lá 1");
        Long directLeaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id",
                Long.class, directTask, "Lá 2");
        assertTrue(repository.deleteByIdForOwner(ownerId, planId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_plan WHERE id IN (?,?)", Integer.class, root, child));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task WHERE id IN (?,?)", Integer.class, nestedTask, directTask));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id IN (?,?)", Integer.class, nestedLeaf, directLeaf));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM task WHERE id = ?", Integer.class, freeTask));
    }

    @Test
    void bootstrapRefusesInitializedSchemaWithoutLosingData() throws Exception {
        long id = repository.insertForOwner(ownerId, new Plan("JUnit giữ dữ liệu"));
        var rejected = assertThrows(java.sql.SQLException.class, AuthTestDatabase::initializeEmptyPlannerSchema);
        assertTrue(rejected.getMessage().contains("existing Planner relations"));
        assertEquals("JUnit giữ dữ liệu", repository.findByIdForOwner(ownerId, id).orElseThrow().getTitle());
    }
}
