package com.example.planner.integration;

import com.example.planner.service.PlanService;
import com.example.planner.service.SubPlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(PostgresTreeTest.Config.class)
class TreeConcurrencyTest extends PostgresTreeTest {
    @Autowired PlanService planService;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void reciprocalMovesCannotCommitACycle() throws Exception {
        long planId = plan();
        long first = subPlan(planId, null);
        long second = subPlan(planId, null);

        var rejected = assertInstanceOf(SubPlanService.InputException.class,
                compete(() -> move(first, second), () -> move(second, first)));

        assertEquals("parentSubPlanId", rejected.getField());
        assertEquals(second, subPlans.getById(first).getParentSubPlanId());
        assertNull(subPlans.getById(second).getParentSubPlanId());
        assertEquals(2, trees.loadPlan(planId).subPlans().size());
    }

    @Test
    void concurrentMovesCannotPushADescendantFromLevelFiveToSix() throws Exception {
        long planId = plan();
        long root = subPlan(planId, null);
        long second = subPlan(planId, root);
        long third = subPlan(planId, second);
        long movable = subPlan(planId, null);
        long incoming = subPlan(planId, null);
        long descendant = subPlan(planId, incoming);

        // Each move fits alone. Together, the incoming descendant would reach level six.
        var rejected = assertInstanceOf(SubPlanService.InputException.class,
                compete(() -> move(movable, third), () -> move(incoming, movable)));

        assertEquals("parentSubPlanId", rejected.getField());
        assertEquals(third, subPlans.getById(movable).getParentSubPlanId());
        assertNull(subPlans.getById(incoming).getParentSubPlanId());
        assertEquals(incoming, subPlans.getById(descendant).getParentSubPlanId());
        assertEquals(6, trees.loadPlan(planId).subPlans().size());
        move(incoming, third);
        assertEquals(6, trees.loadPlan(planId).subPlans().size());
    }

    @ParameterizedTest
    @CsvSource({"PLAN, true", "PLAN, false", "SUBPLAN, true", "SUBPLAN, false", "TASK, true", "TASK, false"})
    void cascadeRejectsCompetingLeafWriteAndPreservesUnrelatedWork(DeleteScope scope, boolean create)
            throws Exception {
        long planId = plan();
        long branch = subPlan(planId, null);
        long taskId = scope == DeleteScope.TASK ? task(null, null) : task(null, branch);
        long leafId = leaf(taskId);
        long survivor = leaf(task(null, null));

        Runnable delete = switch (scope) {
            case PLAN -> () -> planService.delete(planId);
            case SUBPLAN -> () -> subPlans.delete(branch);
            case TASK -> () -> taskService.delete(taskId);
        };
        Runnable write = create ? () -> leaf(taskId)
                : () -> leafService.update(leafId, "Competing update", null, null, 1);

        var rejected = assertInstanceOf(ResponseStatusException.class, compete(delete, write));

        assertEquals(HttpStatus.NOT_FOUND, rejected.getStatusCode());
        assertEquals(scope != DeleteScope.PLAN, plans.findById(planId).isPresent());
        assertTrue(tasks.findById(taskId).isEmpty());
        assertTrue(leaves.findByTaskId(taskId).isEmpty());
        assertEquals("JUnit leaf", leafService.getById(survivor).getTitle());
    }

    @Test
    void overlappingDoneRequestsAndMetadataEditPreserveCompletion() throws Exception {
        long taskId = task(null, null);
        long leafId = leaf(taskId);
        var firstCompletedAt = new AtomicReference<OffsetDateTime>();
        Runnable finish = () -> {
            leafService.setCompletion(leafId, true);
            firstCompletedAt.set(leafService.getById(leafId).getCompletedAt());
            assertNotNull(firstCompletedAt.get());
        };

        assertNull(compete(finish, () -> leafService.setCompletion(leafId, true)));
        assertCompletion(leafId, firstCompletedAt.get());

        leafService.setCompletion(leafId, false);
        assertNull(leafService.getById(leafId).getCompletedAt());
        assertNull(compete(finish, () -> leafService.update(leafId, "Edited after lock", null, null, 2)));
        assertCompletion(leafId, firstCompletedAt.get());
        assertEquals("Edited after lock", leafService.getById(leafId).getTitle());
        assertEquals(2, leafService.getById(leafId).getOrderIndex());
        assertEquals(1, trees.loadTask(taskId).task().getCompletedCount());
    }

    private enum DeleteScope { PLAN, SUBPLAN, TASK }

    private void move(long id, long parent) {
        subPlans.update(id, parent, "Moved branch", null, null, null, 0);
    }

    private void assertCompletion(long leafId, OffsetDateTime expected) {
        var saved = leafService.getById(leafId);
        assertTrue(saved.isDone());
        assertEquals(expected, saved.getCompletedAt());
    }

    private Throwable compete(Runnable first, Runnable second) throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        var ready = new CompletableFuture<Integer>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = transaction.execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout = '15s'");
                int holder = backendPid();
                first.run();
                var worker = executor.submit(() -> transaction.executeWithoutResult(workerStatus -> {
                    jdbc.execute("SET LOCAL statement_timeout = '15s'");
                    ready.complete(backendPid());
                    second.run();
                }));

                int waiter;
                try {
                    waiter = ready.get(5, SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                } catch (ExecutionException | TimeoutException exception) {
                    throw new AssertionError("Second transaction did not start", exception);
                }
                assertNotEquals(holder, waiter);
                awaitBlockedBy(waiter, holder);
                return worker;
            });
            try {
                result.get(20, SECONDS);
                return null;
            } catch (ExecutionException exception) {
                return exception.getCause();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, SECONDS), "Competing transaction did not exit");
        }
    }

    private void awaitBlockedBy(int waiter, int holder) {
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holder, waiter))) {
                return;
            }
        } while (System.nanoTime() < deadline);
        fail("PostgreSQL did not report the competing transaction waiting for the first");
    }
}
