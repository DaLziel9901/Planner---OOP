package com.example.planner.repository;

import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import com.example.planner.service.SubPlanService;
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
class SubPlanRepositoryTest {
    @Test void batchSubPlansIncludesEveryLevelAndOnlySelectedPlans() {
        long root=service.create(ownerId, planId,null,"Root",null,null,null,0);
        long child=service.create(ownerId, planId,root,"Child",null,null,null,0);
        long other=plans.insertForOwner(ownerId, new Plan("Other")); long third=service.create(ownerId, other,null,"Other sub",null,null,null,0);
        assertEquals(List.of(root,child),repository.findByPlanIdsForOwner(ownerId, List.of(planId,planId)).stream().map(SubPlan::getId).toList());
        assertEquals(List.of(root,child,third),repository.findByPlanIdsForOwner(ownerId, List.of(planId,other)).stream().map(SubPlan::getId).toList());
        assertTrue(repository.findByPlanIdsForOwner(ownerId, List.of()).isEmpty());
    }
    private long ownerId;
    private Connection connection;
    private JdbcTemplate jdbc;
    private PlanRepository plans;
    private SubPlanRepository repository;
    private SubPlanService service;
    private long planId;

    @BeforeEach
    void setUp() throws Exception {
        connection = AuthTestDatabase.openConnection();
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        ownerId = AuthTestDatabase.createFixtureAccount(jdbc);
        plans = new PlanRepository(jdbc);
        repository = new SubPlanRepository(jdbc);
        service = new SubPlanService(repository, plans);
        planId = plans.insertForOwner(ownerId, new Plan("JUnit SubPlan"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            try { connection.rollback(); } finally { connection.close(); }
        }
    }

    @Test
    void crudMapsNullableFieldsAndKeepsCreationTime() {
        long id = service.create(ownerId, planId, null, "Giai đoạn", null, null, null, 0);
        SubPlan saved = repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertEquals(planId, saved.getPlanId());
        assertNull(saved.getParentSubPlanId());
        assertNull(saved.getDescription());
        assertNull(saved.getStartDate());
        assertNull(saved.getEndDate());
        assertEquals(0, saved.getOrderIndex());
        var created = saved.getCreatedAt().toInstant();
        LocalDate date = LocalDate.of(2026, 11, 2);
        service.update(ownerId, id, null, "Mới", "Mô tả", date, date.plusDays(1), 3);
        SubPlan updated = repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertEquals("Mới", updated.getTitle());
        assertEquals("Mô tả", updated.getDescription());
        assertEquals(date, updated.getStartDate());
        assertEquals(date.plusDays(1), updated.getEndDate());
        assertEquals(3, updated.getOrderIndex());
        assertEquals(created, updated.getCreatedAt().toInstant());
        assertTrue(repository.lockPlanForOwner(ownerId, planId));
        service.delete(ownerId, id);
        assertTrue(repository.findByIdForOwner(ownerId, id).isEmpty());
        assertFalse(repository.updateForOwner(ownerId, updated));
        assertFalse(repository.deleteByIdForOwner(ownerId, id));
    }

    @Test
    void readsAllLevelsOrdersByIndexThenIdAndAllowsSameTitle() {
        long root = service.create(ownerId, planId, null, "Tên trùng", null, null, null, 0);
        long child = service.create(ownerId, planId, root, "Tên trùng", null, null, null, 0);
        long later = service.create(ownerId, planId, null, "Tên trùng", null, null, null, 2);
        assertEquals(List.of(root, child, later), repository.findByPlanIdForOwner(ownerId, planId).stream().map(SubPlan::getId).toList());
        assertEquals(root, repository.findByIdForOwner(ownerId, child).orElseThrow().getParentSubPlanId());
        long otherPlan = plans.insertForOwner(ownerId, new Plan("Plan khác"));
        assertThrows(SubPlanService.InputException.class,
                () -> service.create(ownerId, otherPlan, root, "Sai Plan", null, null, null, 0));
    }

    @Test
    void wholeBranchMovesAndDepthChecksWorkWithRealRows() {
        long root = service.create(ownerId, planId, null, "Tầng 1", null, null, null, 0);
        long second = service.create(ownerId, planId, root, "Tầng 2", null, null, null, 0);
        long third = service.create(ownerId, planId, second, "Tầng 3", null, null, null, 0);
        long fourth = service.create(ownerId, planId, third, "Tầng 4", null, null, null, 0);
        long fifth = service.create(ownerId, planId, fourth, "Tầng 5", null, null, null, 0);
        int before = repository.findByPlanIdForOwner(ownerId, planId).size();
        assertThrows(SubPlanService.InputException.class,
                () -> service.create(ownerId, planId, fifth, "Tầng 6", null, null, null, 0));
        assertEquals(before, repository.findByPlanIdForOwner(ownerId, planId).size());
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(ownerId, root, third, "Vòng lặp", null, null, null, 0));
        long moved = service.create(ownerId, planId, null, "Nhánh chuyển", null, null, null, 0);
        long child = service.create(ownerId, planId, moved, "Con đi cùng", null, null, null, 0);
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(ownerId, moved, fourth, "Quá sâu", null, null, null, 0));
        assertNull(repository.findByIdForOwner(ownerId, moved).orElseThrow().getParentSubPlanId());
        assertEquals("Nhánh chuyển", repository.findByIdForOwner(ownerId, moved).orElseThrow().getTitle());
        service.update(ownerId, moved, third, "Đúng độ sâu", null, null, null, 0);
        assertEquals(third, repository.findByIdForOwner(ownerId, moved).orElseThrow().getParentSubPlanId());
        assertEquals(moved, repository.findByIdForOwner(ownerId, child).orElseThrow().getParentSubPlanId());
    }

    @Test
    void deleteCascadesButKeepsSiblingBranch() {
        long root = service.create(ownerId, planId, null, "Xóa", null, null, null, 0);
        long child = service.create(ownerId, planId, root, "Con", null, null, null, 0);
        long sibling = service.create(ownerId, planId, null, "Giữ", null, null, null, 0);
        Long task = jdbc.queryForObject("INSERT INTO task(sub_plan_id,title) VALUES (?,?) RETURNING id",
                Long.class, child, "Task");
        Long leaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id",
                Long.class, task, "SubTask");
        assertEquals(planId, service.delete(ownerId, root));
        assertTrue(repository.findByIdForOwner(ownerId, root).isEmpty());
        assertTrue(plans.findByIdForOwner(ownerId, planId).isPresent());
        assertTrue(repository.findByIdForOwner(ownerId, child).isEmpty());
        assertTrue(repository.findByIdForOwner(ownerId, sibling).isPresent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task WHERE id = ?", Integer.class, task));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id = ?", Integer.class, leaf));
    }
}
