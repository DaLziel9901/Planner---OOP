package com.example.planner.repository;

import com.example.planner.model.Plan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PlanRepositoryTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private PlanRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TEST_DB_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Chỉ cho phép database riêng có tên kết thúc bằng _test");
        }
        String username = System.getenv().getOrDefault("TEST_DB_USERNAME", "postgres");
        String password = System.getenv("TEST_DB_PASSWORD");
        if (password == null) {
            throw new IllegalArgumentException("Cần đặt TEST_DB_PASSWORD cho database thử nghiệm");
        }
        connection = DriverManager.getConnection(url, username, password);
        connection.setAutoCommit(false);
        // Schema và dữ liệu đều nằm trong giao dịch này và được rollback sau test.
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
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
        long id = repository.insert(input);
        assertTrue(id > 0);
        Plan saved = repository.findById(id).orElseThrow();
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
        assertTrue(repository.update(saved));
        Plan updated = repository.findById(id).orElseThrow();
        assertEquals("JUnit Updated", updated.getTitle());
        assertEquals("730+", updated.getGoal());
        assertEquals(start, updated.getStartDate());
        assertEquals(start.plusDays(1), updated.getEndDate());
        assertEquals(created.toInstant(), updated.getCreatedAt().toInstant());
        updated.setGoal(null);
        updated.setDates(null, null);
        assertTrue(repository.update(updated));
        assertNull(repository.findById(id).orElseThrow().getStartDate());
        assertNull(repository.findById(id).orElseThrow().getGoal());
        assertTrue(repository.deleteById(id));
        assertTrue(repository.findById(id).isEmpty());
        assertFalse(repository.update(updated));
        assertFalse(repository.deleteById(id));
    }

    @Test
    void duplicateTitlesAndSqlLikeTextAreStoredAsData() {
        String title = "JUnit '); DROP TABLE plan; --";
        long first = repository.insert(new Plan(title));
        long second = repository.insert(new Plan(title));
        assertNotEquals(first, second);
        assertEquals(title, repository.findById(first).orElseThrow().getTitle());
        List<Long> ids = repository.findAll().stream().map(Plan::getId).toList();
        // CURRENT_TIMESTAMP giống nhau trong cùng giao dịch, nên ID lớn đứng trước.
        assertTrue(ids.indexOf(second) < ids.indexOf(first));
    }

    @Test
    void deleteCascadesThroughNestedSubPlansAndBothTaskLocations() {
        long planId = repository.insert(new Plan("JUnit cascade"));
        Long root = jdbc.queryForObject("INSERT INTO sub_plan(plan_id,title) VALUES (?,?) RETURNING id",
                Long.class, planId, "Tầng 1");
        Long child = jdbc.queryForObject("INSERT INTO sub_plan(plan_id,parent_sub_plan_id,title) VALUES (?,?,?) RETURNING id",
                Long.class, planId, root, "Tầng 2");
        Long nestedTask = jdbc.queryForObject("INSERT INTO task(sub_plan_id,title) VALUES (?,?) RETURNING id",
                Long.class, child, "Task trong nhánh");
        Long directTask = jdbc.queryForObject("INSERT INTO task(plan_id,title) VALUES (?,?) RETURNING id",
                Long.class, planId, "Task trực tiếp");
        Long freeTask = jdbc.queryForObject("INSERT INTO task(title) VALUES (?) RETURNING id",
                Long.class, "Task tự do cần giữ");
        Long nestedLeaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id",
                Long.class, nestedTask, "Lá 1");
        Long directLeaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id",
                Long.class, directTask, "Lá 2");
        assertTrue(repository.deleteById(planId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_plan WHERE id IN (?,?)", Integer.class, root, child));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task WHERE id IN (?,?)", Integer.class, nestedTask, directTask));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id IN (?,?)", Integer.class, nestedLeaf, directLeaf));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM task WHERE id = ?", Integer.class, freeTask));
    }

    @Test
    void schemaCanRunTwiceWithoutLosingData() throws Exception {
        long id = repository.insert(new Plan("JUnit giữ dữ liệu"));
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        assertEquals("JUnit giữ dữ liệu", repository.findById(id).orElseThrow().getTitle());
    }
}
