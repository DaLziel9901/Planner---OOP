package com.example.planner.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanTest {

    @Test
    void emptyNodesDoNotIncreaseDenominator() {
        Plan plan = new Plan("TOEIC");
        plan.addTask(new Task("Rỗng"));
        plan.addSubPlan(new SubPlan("Rỗng"));
        assertEquals(0, plan.getTotalCount());
        assertEquals(0, plan.getCompletedCount());
        assertEquals(0.0, plan.getProgress(), 0.001);
    }

    @Test
    void progressUsesLeafCountsInsteadOfAveragePercentages() {
        Plan plan = new Plan("TOEIC");
        Task finished = new Task("1/1");
        SubTask done = new SubTask("Xong");
        done.markDone();
        finished.addSubTask(done);
        plan.addTask(finished);

        SubPlan stage = new SubPlan("Giai đoạn");
        Task pending = new Task("0/9");
        for (int i = 0; i < 9; i++) {
            pending.addSubTask(new SubTask("Việc " + i));
        }
        stage.addTask(pending);
        plan.addSubPlan(stage);

        Task freeTask = new Task("Tự do, không gắn vào Plan");
        freeTask.addSubTask(new SubTask("Không tính vào Plan"));
        assertEquals(10, plan.getTotalCount());
        assertEquals(1, plan.getCompletedCount());
        assertEquals(10.0, plan.getProgress(), 0.001);
        done.markUndone();
        assertEquals(0.0, plan.getProgress(), 0.001);
    }

    @Test
    void fiveNestedSubPlansPropagateChangesUpToRoot() {
        Plan plan = new Plan("TOEIC");
        SubPlan root = new SubPlan("Tầng 1");
        plan.addSubPlan(root);
        SubPlan deepest = root;
        for (int i = 2; i <= 5; i++) {
            SubPlan child = new SubPlan("Tầng " + i);
            deepest.addSubPlan(child);
            deepest = child;
        }
        Task task = new Task("Học từ");
        SubTask leaf = new SubTask("20 từ");
        task.addSubTask(leaf);
        deepest.addTask(task);

        PlanNode node = plan;
        assertEquals(1, node.getTotalCount());
        leaf.markDone();
        assertEquals(1, root.getCompletedCount());
        assertEquals(100.0, node.getProgress(), 0.001);
        deepest.removeTask(task);
        assertEquals(0, node.getTotalCount());
        assertEquals(0.0, node.getProgress(), 0.001);
    }

    @Test
    void savedFieldsAndValidationMatchPlanSchema() {
        LocalDate date = LocalDate.of(2026, 10, 30);
        OffsetDateTime created = OffsetDateTime.now();
        Plan plan = new Plan(1L, "TOEIC", "730+", date, date, created);
        assertEquals(1L, plan.getId());
        assertEquals("TOEIC", plan.getName());
        assertEquals("730+", plan.getGoal());
        assertEquals(date, plan.getStartDate());
        assertEquals(date, plan.getEndDate());
        assertEquals(created, plan.getCreatedAt());
        plan.setTitle("Kế hoạch mới");
        assertEquals("Kế hoạch mới", plan.getTitle());
        plan.setGoal(null);
        assertNull(plan.getGoal());
        assertThrows(IllegalArgumentException.class, () -> plan.setTitle(" "));
        assertThrows(IllegalArgumentException.class, () -> plan.setTitle("a".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> plan.setId(0L));
        assertThrows(IllegalArgumentException.class, () -> plan.setDates(date, date.minusDays(1)));
        assertEquals(date, plan.getEndDate());
        plan.setDates(null, date);
        assertNull(plan.getStartDate());
        plan.setDates(null, null);
        assertNull(plan.getEndDate());
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(null, "TOEIC", null, null, null, null));
    }

    @Test
    void rejectsDuplicateAndIncorrectChildren() {
        Plan plan = new Plan("TOEIC");
        plan.setId(1L);
        Task task = new Task("Học");
        task.setId(2L);
        task.setLocation(1L, null);
        plan.addTask(task);
        assertThrows(IllegalArgumentException.class, () -> plan.addTask(task));
        Task copy = new Task("Bản sao");
        copy.setId(2L);
        assertThrows(IllegalArgumentException.class, () -> plan.addTask(copy));
        Task wrong = new Task("Khác cha");
        wrong.setLocation(null, 3L);
        assertThrows(IllegalArgumentException.class, () -> plan.addTask(wrong));
        wrong.setLocation(4L, null);
        assertThrows(IllegalArgumentException.class, () -> plan.addTask(wrong));

        SubPlan stage = new SubPlan("Tầng 1");
        stage.setId(3L);
        stage.setLocation(1L, null);
        plan.addSubPlan(stage);
        assertThrows(IllegalArgumentException.class, () -> plan.addSubPlan(stage));
        SubPlan wrongStage = new SubPlan("Khác Plan");
        wrongStage.setLocation(2L, null);
        assertThrows(IllegalArgumentException.class, () -> plan.addSubPlan(wrongStage));
        wrongStage.setLocation(1L, 3L);
        assertThrows(IllegalArgumentException.class, () -> plan.addSubPlan(wrongStage));
        assertThrows(IllegalArgumentException.class, () -> plan.addTask(null));
        assertThrows(IllegalArgumentException.class, () -> plan.addSubPlan(null));
        assertTrue(plan.removeTask(task));
        assertTrue(plan.removeSubPlan(stage));
        assertFalse(plan.removeSubPlan(stage));
    }

    @Test
    void listsAreSortedWithinEachTypeAndProtected() {
        Plan plan = new Plan("TOEIC");
        Task later = new Task("Sau");
        later.setOrderIndex(1);
        Task first = new Task("Đầu");
        plan.addTask(later);
        plan.addTask(first);
        SubPlan a = new SubPlan("A");
        a.setId(10L);
        SubPlan b = new SubPlan("B");
        b.setId(5L);
        plan.addSubPlan(a);
        plan.addSubPlan(b);
        assertEquals(List.of(first, later), plan.getTasks());
        assertEquals(List.of(b, a), plan.getSubPlans());
        assertEquals(List.of(b, a, first, later), plan.getChildren());
        assertThrows(UnsupportedOperationException.class, () -> plan.getTasks().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.getSubPlans().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.getChildren().clear());
    }
}
