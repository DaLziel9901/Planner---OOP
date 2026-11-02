package com.example.planner.repository;

import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import com.example.planner.service.SubPlanService;
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
class SubPlanRepositoryTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private PlanRepository plans;
    private SubPlanRepository repository;
    private SubPlanService service;
    private long planId;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TEST_DB_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Chỉ dùng database riêng kết thúc bằng _test");
        }
        String password = System.getenv("TEST_DB_PASSWORD");
        if (password == null) throw new IllegalArgumentException("Cần đặt TEST_DB_PASSWORD");
        connection = DriverManager.getConnection(url,
                System.getenv().getOrDefault("TEST_DB_USERNAME", "postgres"), password);
        connection.setAutoCommit(false);
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        plans = new PlanRepository(jdbc);
        repository = new SubPlanRepository(jdbc);
        service = new SubPlanService(repository, plans);
        planId = plans.insert(new Plan("JUnit SubPlan"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            try { connection.rollback(); } finally { connection.close(); }
        }
    }

    @Test
    void crudMapsNullableFieldsAndKeepsCreationTime() {
        long id = service.create(planId, null, "Giai đoạn", null, null, null, 0);
        SubPlan saved = repository.findById(id).orElseThrow();
        assertEquals(planId, saved.getPlanId());
        assertNull(saved.getParentSubPlanId());
        assertNull(saved.getDescription());
        assertNull(saved.getStartDate());
        assertNull(saved.getEndDate());
        assertEquals(0, saved.getOrderIndex());
        var created = saved.getCreatedAt().toInstant();
        LocalDate date = LocalDate.of(2026, 11, 2);
        service.update(id, null, "Mới", "Mô tả", date, date.plusDays(1), 3);
        SubPlan updated = repository.findById(id).orElseThrow();
        assertEquals("Mới", updated.getTitle());
        assertEquals("Mô tả", updated.getDescription());
        assertEquals(date, updated.getStartDate());
        assertEquals(date.plusDays(1), updated.getEndDate());
        assertEquals(3, updated.getOrderIndex());
        assertEquals(created, updated.getCreatedAt().toInstant());
        assertTrue(repository.lockPlan(planId));
        service.delete(id);
        assertTrue(repository.findById(id).isEmpty());
        assertFalse(repository.update(updated));
        assertFalse(repository.deleteById(id));
    }

    @Test
    void readsAllLevelsOrdersByIndexThenIdAndAllowsSameTitle() {
        long root = service.create(planId, null, "Tên trùng", null, null, null, 0);
        long child = service.create(planId, root, "Tên trùng", null, null, null, 0);
        long later = service.create(planId, null, "Tên trùng", null, null, null, 2);
        assertEquals(List.of(root, child, later), repository.findByPlanId(planId).stream().map(SubPlan::getId).toList());
        assertEquals(root, repository.findById(child).orElseThrow().getParentSubPlanId());
        long otherPlan = plans.insert(new Plan("Plan khác"));
        assertThrows(SubPlanService.InputException.class,
                () -> service.create(otherPlan, root, "Sai Plan", null, null, null, 0));
    }

    @Test
    void wholeBranchMovesAndDepthChecksWorkWithRealRows() {
        long root = service.create(planId, null, "Tầng 1", null, null, null, 0);
        long second = service.create(planId, root, "Tầng 2", null, null, null, 0);
        long third = service.create(planId, second, "Tầng 3", null, null, null, 0);
        long fourth = service.create(planId, third, "Tầng 4", null, null, null, 0);
        service.create(planId, fourth, "Tầng 5", null, null, null, 0);
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(root, third, "Vòng lặp", null, null, null, 0));
        long moved = service.create(planId, null, "Nhánh chuyển", null, null, null, 0);
        long child = service.create(planId, moved, "Con đi cùng", null, null, null, 0);
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(moved, fourth, "Quá sâu", null, null, null, 0));
        service.update(moved, third, "Đúng độ sâu", null, null, null, 0);
        assertEquals(third, repository.findById(moved).orElseThrow().getParentSubPlanId());
        assertEquals(moved, repository.findById(child).orElseThrow().getParentSubPlanId());
    }

    @Test
    void deleteCascadesButKeepsSiblingBranch() {
        long root = service.create(planId, null, "Xóa", null, null, null, 0);
        long child = service.create(planId, root, "Con", null, null, null, 0);
        long sibling = service.create(planId, null, "Giữ", null, null, null, 0);
        Long task = jdbc.queryForObject("INSERT INTO task(sub_plan_id,title) VALUES (?,?) RETURNING id",
                Long.class, child, "Task");
        Long leaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id",
                Long.class, task, "SubTask");
        assertEquals(planId, service.delete(root));
        assertTrue(repository.findById(child).isEmpty());
        assertTrue(repository.findById(sibling).isPresent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task WHERE id = ?", Integer.class, task));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id = ?", Integer.class, leaf));
    }
}
