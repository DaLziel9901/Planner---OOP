package com.example.planner.integration;

import com.example.planner.controller.PlanController;
import com.example.planner.controller.SubTaskController;
import com.example.planner.controller.TaskController;
import com.example.planner.model.PlanNode;
import com.example.planner.model.SubPlan;
import com.example.planner.model.SubTask;
import com.example.planner.model.Task;
import com.example.planner.repository.SubTaskRepository;
import com.example.planner.repository.TaskRepository;
import com.example.planner.service.PlanService;
import com.example.planner.service.ProgressPresentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.ExtendedModelMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@SpringJUnitConfig({PostgresTreeTest.Config.class, PlanTreeIntegrationTest.ReadConfig.class})
class PlanTreeIntegrationTest extends PostgresTreeTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class ReadConfig {
        @Bean
        @Primary
        TaskRepository observedTasks(JdbcTemplate jdbc, ReadProbe probe) {
            return new TaskRepository(jdbc) {
                @Override
                public List<Task> findByPlanIdsForOwner(long ownerId, Collection<Long> ids) {
                    var rows = super.findByPlanIdsForOwner(ownerId, ids);
                    probe.afterTasks.run();
                    return rows;
                }
            };
        }

        @Bean
        @Primary
        SubTaskRepository observedLeaves(JdbcTemplate jdbc, ReadProbe probe) {
            return new SubTaskRepository(jdbc) {
                @Override
                public List<SubTask> findByTaskIdsForOwner(long ownerId, Collection<Long> ids) {
                    probe.leafReads.incrementAndGet();
                    return super.findByTaskIdsForOwner(ownerId, ids);
                }
            };
        }

        @Bean
        ReadProbe readProbe() {
            return new ReadProbe();
        }
    }

    static class ReadProbe {
        volatile Runnable afterTasks = () -> {};
        final AtomicInteger leafReads = new AtomicInteger();
    }

    @Autowired ReadProbe probe;
    @Autowired PlanService planService;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void resetProbe() {
        probe.afterTasks = () -> {};
        probe.leafReads.set(0);
    }

    @Test
    void persistedLeavesDetermineProgressAcrossFiveLevels() {
        long planId = plan();
        long direct = task(planId, null);
        assertEmpty(trees.loadPlan(ownerId, planId).plan());
        assertEmpty(trees.loadTask(ownerId, direct).task());
        long first = leaf(direct);
        long second = leaf(direct);
        assertProgress(trees.loadTask(ownerId, direct).task(), 0, 2, "0%");
        leafService.setCompletion(ownerId, first, true);
        assertProgress(trees.loadTask(ownerId, direct).task(), 1, 2, "50%");
        leafService.setCompletion(ownerId, second, true);
        assertProgress(trees.loadTask(ownerId, direct).task(), 2, 2, "100%");
        leafService.setCompletion(ownerId, second, false);
        assertNull(leafService.getById(ownerId, second).getCompletedAt());
        assertProgress(trees.loadTask(ownerId, direct).task(), 1, 2, "50%");
        leafService.delete(ownerId, second);

        Long parent = null;
        var branchIds = new ArrayList<Long>();
        for (int depth = 1; depth <= 5; depth++) {
            parent = subPlan(planId, parent);
            branchIds.add(parent);
        }
        long nested = task(null, parent);
        long nestedFirst = leaf(nested);
        long nestedSecond = leaf(nested);
        task(planId, null);
        long independent = task(null, null);
        leafService.setCompletion(ownerId, leaf(independent), true);

        assertProgress(trees.loadPlan(ownerId, planId).plan(), 1, 3, "33,33%");
        long nestedThird = leaf(nested);
        var snapshot = trees.loadPlan(ownerId, planId);
        assertProgress(snapshot.plan(), 1, 4, "25%");
        assertEquals(branchIds, snapshot.subPlans().stream().map(SubPlan::getId).toList());
        assertEquals(3, snapshot.plan().getChildren().size());
        assertProgress(trees.loadTask(ownerId, independent).task(), 1, 1, "100%");

        leafService.setCompletion(ownerId, nestedFirst, true);
        assertProgress(trees.loadTask(ownerId, nested).task(), 1, 3, "33,33%");
        leafService.setCompletion(ownerId, nestedSecond, true);
        assertProgress(trees.loadTask(ownerId, nested).task(), 2, 3, "66,67%");
        var refreshed = trees.loadPlan(ownerId, planId);
        assertProgress(refreshed.plan(), 3, 4, "75%");
        for (SubPlan node : refreshed.subPlans()) assertProgress(node, 2, 3, "66,67%");
        assertProgress(snapshot.plan(), 1, 4, "25%");

        leafService.delete(ownerId, first);
        leafService.delete(ownerId, nestedFirst);
        leafService.delete(ownerId, nestedSecond);
        leafService.delete(ownerId, nestedThird);
        assertEmpty(trees.loadTask(ownerId, direct).task());
        assertEmpty(trees.loadSubPlan(ownerId, parent).subPlan());
        assertEmpty(trees.loadPlan(ownerId, planId).plan());
    }

    @Test
    void completionRedirectReadsCommittedStateOnSourceAndAncestorPages() throws Exception {
        long planId = plan();
        long taskId = task(planId, null);
        long leafId = leaf(taskId);
        leaf(taskId);
        var mvc = MockMvcBuilders.standaloneSetup(new SubTaskController(leafService, taskService, trees),
                new TaskController(taskService, planService, subPlans, trees), new PlanController(planService, trees))
                .build();
        String source = "/tasks/" + taskId;
        String completion = "/subtasks/" + leafId + "/completion";

        mvc.perform(post(completion).param("done", "true").param("source", "task")
                        .param("sourceId", Long.toString(taskId)))
                .andExpect(redirectedUrl(source)).andExpect(flash().attributeExists("message"));
        var updated = mvc.perform(get(source)).andExpect(status().isOk()).andReturn().getModelAndView();
        var displayed = (Task) updated.getModel().get("task");
        assertProgress(displayed, 1, 2, "50%");
        assertTrue(displayed.getSubTasks().stream().anyMatch(leaf -> leaf.getId() == leafId && leaf.isDone()));
        mvc.perform(get("/plans/{id}", planId)).andExpect(status().isOk())
                .andExpect(model().attribute("progress", ProgressPresentation.from(displayed)));

        mvc.perform(post(completion).param("done", "false").param("source", "task")
                        .param("sourceId", Long.toString(taskId)))
                .andExpect(redirectedUrl(source)).andExpect(flash().attributeExists("message"));
        var undone = (Task) mvc.perform(get(source)).andExpect(status().isOk()).andReturn()
                .getModelAndView().getModel().get("task");
        assertProgress(undone, 0, 2, "0%");
        assertTrue(undone.getSubTasks().stream().noneMatch(SubTask::isDone));
    }

    @Test
    void readerKeepsOriginalMetadataAndLeavesAcrossWriterCommit() throws Exception {
        long planId = plan();
        long taskId = task(planId, null);
        long leafId = leaf(taskId);
        var taskRowsRead = new CountDownLatch(1);
        var writerCommitted = new CountDownLatch(1);
        var readerPid = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        probe.afterTasks = () -> {
            assertEquals("repeatable read", jdbc.queryForObject("SHOW transaction_isolation", String.class));
            assertEquals("on", jdbc.queryForObject("SHOW transaction_read_only", String.class));
            readerPid.set(backendPid());
            taskRowsRead.countDown();
            await(writerCommitted);
        };

        try {
            var result = executor.submit(() -> trees.loadPlan(ownerId, planId));
            await(taskRowsRead);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL statement_timeout = '5s'");
                assertNotEquals(readerPid.get(), backendPid());
                taskService.update(ownerId, taskId, "Committed title", null, null, null, 0);
                leafService.setCompletion(ownerId, leafId, true);
            });
            writerCommitted.countDown();

            var original = result.get(10, SECONDS).plan();
            assertProgress(original, 0, 1, "0%");
            assertEquals("JUnit task", original.getChildren().get(0).getName());
            probe.afterTasks = () -> {};
            var current = trees.loadPlan(ownerId, planId).plan();
            assertProgress(current, 1, 1, "100%");
            assertEquals("Committed title", current.getChildren().get(0).getName());
        } finally {
            writerCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, SECONDS));
        }
    }

    @Test
    void postgresReadErrorRollsBackBeforeControllerShowsUnavailable() {
        long planId = plan();
        leaf(task(planId, null));
        var completion = new AtomicInteger(-1);
        var failedReads = new AtomicInteger();
        probe.afterTasks = () -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completion.set(status);
                }
            });
            failedReads.incrementAndGet();
            jdbc.queryForObject("SELECT 1 / 0", Integer.class);
        };

        // Verify the SQL error exits the proxy, then exercise the actual controller fallback.
        assertThrows(DataAccessException.class, () -> trees.loadPlan(ownerId, planId));
        assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK, completion.getAndSet(-1));
        var model = new ExtendedModelMap();
        assertEquals("plans/detail", new PlanController(planService, trees).detail(planId, model));
        assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK, completion.get());
        assertEquals(2, failedReads.get());
        assertEquals(0, probe.leafReads.get());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(ProgressPresentation.unavailable(), model.getAttribute("progress"));
        assertEquals(Boolean.FALSE, model.getAttribute("progressAvailable"));
        assertTrue(((PlanNode) model.getAttribute("plan")).getChildren().isEmpty());

        probe.afterTasks = () -> {};
        assertProgress(trees.loadPlan(ownerId, planId).plan(), 0, 1, "0%");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, SECONDS), "Timed out waiting for the other connection");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void assertProgress(PlanNode node, int done, int total, String percentage) {
        var progress = ProgressPresentation.from(node);
        assertEquals(done, progress.completedCount());
        assertEquals(total, progress.totalCount());
        assertEquals(percentage, progress.percentage());
    }

    private static void assertEmpty(PlanNode node) {
        var progress = ProgressPresentation.from(node);
        assertEquals(ProgressPresentation.State.EMPTY, progress.state());
        assertEquals("Chưa có công việc", progress.label());
        assertNull(progress.percentage());
    }
}
