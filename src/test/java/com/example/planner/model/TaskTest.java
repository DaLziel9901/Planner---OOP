package com.example.planner.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskTest {

    @Test
    @DisplayName("Tạo Task tự do rồi sửa thông tin mà không đổi thời điểm tạo")
    void newTaskCanBeEditedAndPreparedForPersistence() {
        Task task = new Task("Học từ");
        assertNull(task.getId());
        assertNull(task.getDescription());
        assertNull(task.getStartDate());
        assertNull(task.getEndDate());
        assertEquals(0, task.getOrderIndex());
        assertNotNull(task.getCreatedAt());
        assertTrue(task.getSubTasks().isEmpty());

        OffsetDateTime created = task.getCreatedAt();
        task.setId(2L);
        task.setTitle("Ôn từ");
        task.setDescription("Ôn kiến thức cũ");
        task.setOrderIndex(4);

        assertEquals(2L, task.getId());
        assertEquals("Ôn từ", task.getTitle());
        assertEquals(task.getTitle(), task.getName());
        assertEquals("Ôn kiến thức cũ", task.getDescription());
        assertEquals(4, task.getOrderIndex());
        assertEquals(created, task.getCreatedAt());
        task.setDescription(null);
        assertNull(task.getDescription());
    }

    @Test
    @DisplayName("Constructor đầy đủ giữ nguyên dữ liệu Task đã lưu")
    void fullConstructorRestoresAllFields() {
        OffsetDateTime created = OffsetDateTime.parse("2026-10-27T09:00:00+07:00");
        LocalDate start = LocalDate.of(2026, 10, 28);
        Task task = new Task(2L, 1L, null, "Học từ", "20 từ",
                start, start.plusDays(2), 3, created);

        assertEquals(2L, task.getId());
        assertEquals(1L, task.getPlanId());
        assertNull(task.getSubPlanId());
        assertEquals("Học từ", task.getTitle());
        assertEquals("20 từ", task.getDescription());
        assertEquals(start, task.getStartDate());
        assertEquals(start.plusDays(2), task.getEndDate());
        assertEquals(3, task.getOrderIndex());
        assertEquals(created, task.getCreatedAt());
        assertTrue(task.getChildren().isEmpty());
    }

    @Test
    @DisplayName("ID, tên và thời điểm tạo không hợp lệ bị từ chối")
    void invalidUpdatesPreserveValidTaskData() {
        Task task = new Task("Học từ");
        task.setId(2L);
        task.setLocation(1L, null);

        assertThrows(IllegalArgumentException.class, () -> task.setId(-1L));
        assertThrows(IllegalArgumentException.class, () -> task.setLocation(0L, null));
        assertThrows(IllegalArgumentException.class, () -> task.setLocation(null, -1L));
        assertThrows(IllegalArgumentException.class, () -> task.setTitle(null));
        assertThrows(IllegalArgumentException.class, () -> task.setTitle("a".repeat(256)));
        assertEquals(2L, task.getId());
        assertEquals(1L, task.getPlanId());
        assertNull(task.getSubPlanId());
        assertEquals("Học từ", task.getTitle());
        assertThrows(IllegalArgumentException.class,
                () -> new Task(null, null, null, "Học từ", null, null, null, 0, null));
        task.setTitle("a".repeat(255));
        assertEquals(255, task.getTitle().length());
    }

    @Test
    @DisplayName("Task chấp nhận cùng ngày hoặc bỏ cả hai mốc ngày")
    void sameDayAndUnspecifiedDatesAreAllowed() {
        Task task = new Task("Học từ");
        LocalDate date = LocalDate.of(2026, 10, 28);
        task.setDates(date, date);
        assertEquals(date, task.getStartDate());
        assertEquals(date, task.getEndDate());
        task.setDates(null, null);
        assertNull(task.getStartDate());
        assertNull(task.getEndDate());
    }

    @Test
    @DisplayName("Thêm, tick và xóa SubTask tính lại cả tử số và mẫu số")
    void removingChildrenRecalculatesProgressUntilEmpty() {
        Task task = new Task("Học từ");
        SubTask first = new SubTask("Học");
        SubTask second = new SubTask("Ôn");
        SubTask third = new SubTask("Viết câu");
        task.addSubTask(first);
        task.addSubTask(second);
        task.addSubTask(third);
        first.markDone();
        assertEquals(100.0 / 3, task.getProgress(), 0.001);

        assertFalse(task.removeSubTask(new SubTask("Không có trong Task")));
        assertEquals(3, task.getTotalCount());
        assertTrue(task.removeSubTask(third));
        assertEquals(50.0, task.getProgress(), 0.001);
        assertTrue(task.removeSubTask(second));
        assertEquals(100.0, task.getProgress(), 0.001);
        assertTrue(task.removeSubTask(first));
        assertEquals(0, task.getTotalCount());
        assertEquals(0, task.getCompletedCount());
        assertEquals(0.0, task.getProgress(), 0.001);
    }

    @Test
    @DisplayName("Đổi thứ tự được phản ánh ở lần đọc sau; danh sách trả về được bảo vệ")
    void orderingUpdatesAndUnsavedIdsAreHandled() {
        Task task = new Task("Học từ");
        SubTask unsaved = new SubTask("Chưa lưu");
        SubTask saved = new SubTask("Đã lưu");
        saved.setId(10L);
        task.addSubTask(unsaved);
        task.addSubTask(saved);
        assertEquals(List.of(saved, unsaved), task.getSubTasks());

        saved.setOrderIndex(1);
        assertEquals(List.of(unsaved, saved), task.getChildren());
        assertThrows(UnsupportedOperationException.class, () -> task.getChildren().clear());
        assertEquals(2, task.getTotalCount());
    }

    @Test
    void locationSupportsThreeCasesAndRejectsTwoParents() {
        Task task = new Task("Học từ");
        assertNull(task.getPlanId());
        assertNull(task.getSubPlanId());
        task.setLocation(1L, null);
        assertEquals(1L, task.getPlanId());
        task.setLocation(null, 2L);
        assertNull(task.getPlanId());
        assertEquals(2L, task.getSubPlanId());
        assertThrows(IllegalArgumentException.class, () -> task.setLocation(1L, 2L));
        assertNull(task.getPlanId());
        assertEquals(2L, task.getSubPlanId());
        task.setLocation(null, null);
        assertNull(task.getSubPlanId());
    }

    @Test
    void invalidDatesDoNotOverwriteValidDates() {
        Task task = new Task("Học từ");
        LocalDate start = LocalDate.of(2026, 10, 28);
        task.setDates(start, null);
        assertNull(task.getEndDate());
        task.setDates(null, start);
        assertNull(task.getStartDate());
        task.setDates(start, start.plusDays(1));
        assertThrows(IllegalArgumentException.class,
                () -> task.setDates(start, start.minusDays(1)));
        assertEquals(start, task.getStartDate());
        assertEquals(start.plusDays(1), task.getEndDate());
        assertThrows(IllegalArgumentException.class, () -> task.setTitle(" "));
        assertThrows(IllegalArgumentException.class, () -> task.setOrderIndex(-1));
    }

    @Test
    void duplicateAndMismatchedSubTasksAreRejected() {
        OffsetDateTime now = OffsetDateTime.now();
        Task task = new Task(1L, null, null, "Học từ", null, null, null, 0, now);
        SubTask first = new SubTask(10L, 1L, "Học", null, null, false, null, 0, now);
        task.addSubTask(first);

        assertThrows(IllegalArgumentException.class, () -> task.addSubTask(first));
        assertThrows(IllegalArgumentException.class,
                () -> task.addSubTask(new SubTask(10L, 1L, "Bản sao", null, null,
                        false, null, 0, now)));
        assertThrows(IllegalArgumentException.class,
                () -> task.addSubTask(new SubTask(11L, 2L, "Khác cha", null, null,
                        false, null, 0, now)));
        assertThrows(IllegalArgumentException.class, () -> task.addSubTask(null));
        assertEquals(1, task.getTotalCount());
    }

    @Test
    void childrenAreOrderedAndCannotBeModifiedThroughReturnedList() {
        Task task = new Task("Học từ");
        OffsetDateTime now = OffsetDateTime.now();
        SubTask later = new SubTask(20L, null, "Sau", null, null, false, null, 1, now);
        SubTask first = new SubTask(5L, null, "Đầu", null, null, false, null, 0, now);
        SubTask second = new SubTask(10L, null, "Giữa", null, null, false, null, 0, now);
        task.addSubTask(later);
        task.addSubTask(second);
        task.addSubTask(first);

        assertEquals(List.of(first, second, later), task.getSubTasks());
        assertEquals(List.of(first, second, later), task.getChildren());
        assertThrows(UnsupportedOperationException.class, () -> task.getSubTasks().clear());
        first.markDone();
        assertEquals(1, task.getCompletedCount());
        assertTrue(task.removeSubTask(first));
        assertEquals(2, task.getTotalCount());
        assertEquals(0.0, task.getProgress(), 0.001);
    }

    @Test
    void emptyTaskHasZeroProgress() {
        Task task = new Task("Học từ vựng");

        assertEquals(0, task.getTotalCount());
        assertEquals(0, task.getCompletedCount());
        assertEquals(0.0, task.getProgress(), 0.001);
    }

    @Test
    void oneOfTwoSubTasksDoneGivesFiftyPercent() {
        // Data.
        SubTask learn = new SubTask("Học 20 từ");
        SubTask review = new SubTask("Ôn lại");
        learn.markDone();

        Task task = new Task("Học từ vựng");
        task.addSubTask(learn);
        task.addSubTask(review);

        // Kiểm tra kết quả.
        assertEquals(2, task.getTotalCount());
        assertEquals(1, task.getCompletedCount());
        assertEquals(50.0, task.getProgress(), 0.001);
    }

    @Test
    void allUndoneGivesZeroPercent() {
        Task task = new Task("Học từ vựng");
        task.addSubTask(new SubTask("Học 20 từ"));
        task.addSubTask(new SubTask("Ôn lại"));

        assertEquals(2, task.getTotalCount());
        assertEquals(0, task.getCompletedCount());
        assertEquals(0.0, task.getProgress(), 0.001);
    }

    @Test
    void changingSubTaskStateUpdatesProgress() {
        SubTask learn = new SubTask("Học 20 từ");
        SubTask review = new SubTask("Ôn lại");

        Task task = new Task("Học từ vựng");
        task.addSubTask(learn);
        task.addSubTask(review);

        learn.markDone();
        assertEquals(50.0, task.getProgress(), 0.001);

        review.markDone();
        assertEquals(2, task.getCompletedCount());
        assertEquals(100.0, task.getProgress(), 0.001);

        review.markUndone();
        assertEquals(1, task.getCompletedCount());
        assertEquals(50.0, task.getProgress(), 0.001);
    }

    @Test
    void planNodeUsesBehaviorOfActualObject() {
        SubTask learn = new SubTask("Học 20 từ");
        learn.markDone();

        Task task = new Task("Học từ vựng");
        task.addSubTask(learn);
        task.addSubTask(new SubTask("Ôn lại"));

        PlanNode leafNode = learn;
        PlanNode compositeNode = task;

        assertEquals(1, leafNode.getTotalCount());
        assertEquals(100.0, leafNode.getProgress(), 0.001);

        assertEquals(2, compositeNode.getTotalCount());
        assertEquals(50.0, compositeNode.getProgress(), 0.001);
    }   
}
