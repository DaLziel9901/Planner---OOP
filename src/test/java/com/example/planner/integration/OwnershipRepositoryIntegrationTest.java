package com.example.planner.integration;

import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import com.example.planner.model.SubTask;
import com.example.planner.model.Task;
import com.example.planner.repository.PlanRepository;
import com.example.planner.repository.SubPlanRepository;
import com.example.planner.repository.SubTaskRepository;
import com.example.planner.repository.TaskRepository;
import com.example.planner.service.PlanTreeService;
import com.example.planner.support.AuthTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OwnershipRepositoryIntegrationTest {
    @Test
    void wrongOwnerCannotReadLockUpdateCompleteOrDeleteAnyRowInTree() throws Exception {
        assumeTrue(System.getenv("TEST_DB_URL") != null, "Requires the dedicated PostgreSQL test target");
        AuthTestDatabase.withAccountSchema(connection -> {
            AuthTestDatabase.applyPlannerSchema(connection);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var plans = new PlanRepository(jdbc);
            var phases = new SubPlanRepository(jdbc);
            var tasks = new TaskRepository(jdbc);
            var leaves = new SubTaskRepository(jdbc);
            String hash = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
            long alice = createUser(jdbc, "alice", hash);
            long bob = createUser(jdbc, "bob", hash);
            var plan = new Plan("Alice plan");
            long planId = plans.insertForOwner(alice, plan);
            plan.setId(planId);
            var subPlan = new SubPlan("Alice phase");
            subPlan.setLocation(planId, null);
            long subPlanId = phases.insertForOwner(alice, subPlan).orElseThrow();
            subPlan.setId(subPlanId);
            long bobPlanId = plans.insertForOwner(bob, new Plan("Bob plan"));
            var crossPlan = new SubPlan("Invalid parent");
            crossPlan.setLocation(bobPlanId, subPlanId);
            assertTrue(phases.insertForOwner(bob, crossPlan).isEmpty());
            assertFalse(phases.lockPlanForOwner(bob, planId));
            assertFalse(plans.updateForOwner(bob, plan));
            assertFalse(plans.deleteByIdForOwner(bob, planId));
            assertTrue(plans.findByIdForOwner(bob, planId).isEmpty());
            assertEquals(List.of(bobPlanId), plans.findAllForOwner(bob).stream().map(Plan::getId).toList());
            assertFalse(phases.updateForOwner(bob, subPlan));
            assertFalse(phases.deleteByIdForOwner(bob, subPlanId));
            assertTrue(phases.findByIdForOwner(bob, subPlanId).isEmpty());
            assertTrue(phases.findByPlanIdForOwner(bob, planId).isEmpty());
            assertTrue(phases.findByPlanIdsForOwner(bob, List.of(planId)).isEmpty());

            verifyTask(tasks, leaves, alice, bob, planId, null);
            verifyTask(tasks, leaves, alice, bob, null, subPlanId);
            verifyTask(tasks, leaves, alice, bob, null, null);
            assertTrue(tasks.findByPlanIdsForOwner(bob, List.of(planId)).isEmpty());
            assertEquals(2, tasks.findByPlanIdsForOwner(alice, List.of(planId)).size());
            assertTrue(tasks.findByPlanIdForOwner(bob, planId).isEmpty());
            assertTrue(tasks.findBySubPlanIdForOwner(bob, subPlanId).isEmpty());
            assertTrue(tasks.findIndependentForOwner(bob).isEmpty());
            assertEquals(1, tasks.findIndependentForOwner(alice).size());
            var trees = new PlanTreeService(plans, phases, tasks, leaves);
            assertEquals(2, trees.loadPlan(alice, planId).plan().getTotalCount());
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> trees.loadPlan(bob, planId)).getStatusCode().value());
            assertEquals(List.of(bobPlanId), trees.loadPlans(bob).stream().map(Plan::getId).toList());
        });
    }

    private static long createUser(JdbcTemplate jdbc, String username, String hash) {
        return jdbc.queryForObject("""
                INSERT INTO public.app_user (username, password_hash) VALUES (?, ?) RETURNING id
                """, Long.class, username, hash);
    }

    private static void verifyTask(TaskRepository tasks, SubTaskRepository leaves, long alice, long bob,
                                   Long planId, Long subPlanId) {
        var task = new Task("Alice task");
        task.setLocation(planId, subPlanId);
        if (planId != null || subPlanId != null) {
            assertTrue(tasks.insertForOwner(bob, task).isEmpty());
        }
        long taskId = tasks.insertForOwner(alice, task).orElseThrow();
        task.setId(taskId);
        var leaf = new SubTask("Alice leaf");
        leaf.setTaskId(taskId);
        assertTrue(leaves.insertForOwner(bob, leaf).isEmpty());
        long leafId = leaves.insertForOwner(alice, leaf).orElseThrow();
        leaf.setId(leafId);

        assertTrue(tasks.findByIdForOwner(bob, taskId).isEmpty());
        assertFalse(tasks.lockTaskForOwner(bob, taskId));
        assertFalse(tasks.updateForOwner(bob, task));
        assertFalse(tasks.deleteByIdForOwner(bob, taskId));
        assertTrue(leaves.findByIdForOwner(bob, leafId).isEmpty());
        assertTrue(leaves.findByTaskIdForOwner(bob, taskId).isEmpty());
        assertTrue(leaves.findByTaskIdsForOwner(bob, List.of(taskId)).isEmpty());
        assertFalse(leaves.updateForOwner(bob, leaf));
        assertFalse(leaves.updateCompletionForOwner(bob, leaf));
        assertFalse(leaves.deleteByIdForOwner(bob, leafId));
        assertEquals("Alice task", tasks.findByIdForOwner(alice, taskId).orElseThrow().getTitle());
        assertEquals(List.of(leafId), leaves.findByTaskIdsForOwner(alice, List.of(taskId)).stream()
                .map(SubTask::getId).toList());
    }
}
