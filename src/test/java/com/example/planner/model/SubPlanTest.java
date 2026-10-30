package com.example.planner.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubPlanTest {

    @Test
    void emptySubPlanHasNoWork() {
        SubPlan stage = new SubPlan("Giai đoạn");
        assertEquals(0, stage.getTotalCount());
        assertEquals(0, stage.getCompletedCount());
        assertEquals(0.0, stage.getProgress(), 0.001);
        assertTrue(stage.getChildren().isEmpty());
    }

    @Test
    void preventsSelfAndAncestorCyclesIncludingUnsavedNodes() {
        SubPlan parent = new SubPlan("Cha");
        SubPlan child = new SubPlan("Con");
        SubPlan grandchild = new SubPlan("Cháu");
        parent.addSubPlan(child);
        child.addSubPlan(grandchild);
        assertThrows(IllegalArgumentException.class, () -> parent.addSubPlan(parent));
        assertThrows(IllegalArgumentException.class, () -> grandchild.addSubPlan(parent));
        assertThrows(IllegalArgumentException.class, () -> parent.addSubPlan(child));
        assertEquals(0, parent.getTotalCount());
    }

    @Test
    void restoresFieldsAndChecksDateTitleLocationAndOrder() {
        LocalDate date = LocalDate.of(2026, 10, 30);
        OffsetDateTime created = OffsetDateTime.now();
        SubPlan stage = new SubPlan(2L, 1L, null, "Giai đoạn", "Mô tả",
                date, date, 3, created);
        assertEquals(2L, stage.getId());
        assertEquals(1L, stage.getPlanId());
        assertNull(stage.getParentSubPlanId());
        assertEquals("Giai đoạn", stage.getName());
        assertEquals("Mô tả", stage.getDescription());
        assertEquals(date, stage.getStartDate());
        assertEquals(date, stage.getEndDate());
        assertEquals(3, stage.getOrderIndex());
        assertEquals(created, stage.getCreatedAt());
        stage.setTitle("Mới");
        assertEquals("Mới", stage.getTitle());
        stage.setDescription(null);
        assertNull(stage.getDescription());
        assertThrows(IllegalArgumentException.class, () -> stage.setLocation(1L, 2L));
        stage.setLocation(1L, 3L);
        assertThrows(IllegalArgumentException.class, () -> stage.setId(3L));
        assertEquals(2L, stage.getId());
        assertThrows(IllegalArgumentException.class, () -> stage.setLocation(0L, null));
        assertThrows(IllegalArgumentException.class, () -> stage.setOrderIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> stage.setTitle(null));
        assertThrows(IllegalArgumentException.class, () -> stage.setTitle("a".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> stage.setDates(date, date.minusDays(1)));
        stage.setDates(null, date);
        assertNull(stage.getStartDate());
        stage.setDates(null, null);
        assertNull(stage.getEndDate());
        assertThrows(IllegalArgumentException.class,
                () -> new SubPlan(null, null, null, "A", null, null, null, 0, null));
    }

    @Test
    void validatesSavedParentKeysAndDuplicates() {
        SubPlan parent = new SubPlan("Cha");
        parent.setId(2L);
        parent.setLocation(1L, null);
        SubPlan child = new SubPlan("Con");
        child.setId(3L);
        child.setLocation(1L, 2L);
        parent.addSubPlan(child);
        SubPlan copy = new SubPlan("Bản sao");
        copy.setId(3L);
        copy.setLocation(1L, 2L);
        assertThrows(IllegalArgumentException.class, () -> parent.addSubPlan(copy));
        SubPlan wrong = new SubPlan("Sai Plan");
        wrong.setLocation(4L, 2L);
        assertThrows(IllegalArgumentException.class, () -> parent.addSubPlan(wrong));
        wrong.setLocation(1L, 9L);
        assertThrows(IllegalArgumentException.class, () -> parent.addSubPlan(wrong));
        Task task = new Task("Học");
        task.setId(10L);
        task.setLocation(null, 2L);
        parent.addTask(task);
        assertThrows(IllegalArgumentException.class, () -> parent.addTask(task));
        Task badTask = new Task("Sai cha");
        badTask.setLocation(1L, null);
        assertThrows(IllegalArgumentException.class, () -> parent.addTask(badTask));
        badTask.setLocation(null, 9L);
        assertThrows(IllegalArgumentException.class, () -> parent.addTask(badTask));
        assertThrows(IllegalArgumentException.class, () -> parent.addTask(null));
        assertThrows(IllegalArgumentException.class, () -> parent.addSubPlan(null));
    }

    @Test
    void childrenOutsideParentDatesAreNotBlockedAndRemovalRecalculates() {
        SubPlan stage = new SubPlan("Cha");
        LocalDate date = LocalDate.of(2026, 10, 30);
        stage.setDates(date, date);
        SubPlan child = new SubPlan("Con ngoài khoảng ngày");
        child.setDates(date.plusDays(10), date.plusDays(20));
        stage.addSubPlan(child);
        Task task = new Task("Học");
        SubTask leaf = new SubTask("Từ");
        leaf.markDone();
        task.addSubTask(leaf);
        child.addTask(task);
        assertEquals(100.0, stage.getProgress(), 0.001);
        assertTrue(stage.removeSubPlan(child));
        assertFalse(stage.removeSubPlan(child));
        assertEquals(0.0, stage.getProgress(), 0.001);
        assertTrue(child.removeTask(task));
        assertEquals(0, child.getTotalCount());
    }

    @Test
    void childrenAreOrderedAndReadOnly() {
        SubPlan parent = new SubPlan("Cha");
        SubPlan a = new SubPlan("A");
        a.setOrderIndex(2);
        SubPlan b = new SubPlan("B");
        parent.addSubPlan(a);
        parent.addSubPlan(b);
        Task task = new Task("Việc");
        parent.addTask(task);
        assertEquals(List.of(b, a), parent.getSubPlans());
        assertEquals(List.of(task), parent.getTasks());
        assertEquals(List.of(b, a, task), parent.getChildren());
        assertThrows(UnsupportedOperationException.class, () -> parent.getSubPlans().clear());
        assertThrows(UnsupportedOperationException.class, () -> parent.getTasks().clear());
        assertThrows(UnsupportedOperationException.class, () -> parent.getChildren().clear());
    }
}
