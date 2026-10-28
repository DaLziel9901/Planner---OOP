package com.example.planner.model;

import java.time.OffsetDateTime;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubTaskTest {

    @Test
    @DisplayName("Tạo mới rồi bổ sung thông tin để chuẩn bị lưu SubTask")
    void newSubTaskCanBePreparedForPersistence() {
        SubTask subTask = new SubTask("Học từ");
        assertNull(subTask.getId());
        assertNull(subTask.getTaskId());
        assertNull(subTask.getDescription());
        assertNull(subTask.getScheduledDate());
        assertEquals(0, subTask.getOrderIndex());
        assertNotNull(subTask.getCreatedAt());

        OffsetDateTime createdAt = subTask.getCreatedAt();
        LocalDate date = LocalDate.of(2026, 10, 28);
        subTask.setId(10L);
        subTask.setTaskId(2L);
        subTask.setTitle("Ôn từ");
        subTask.setDescription("Ôn 20 từ cũ");
        subTask.setScheduledDate(date);
        subTask.setOrderIndex(3);

        assertEquals(10L, subTask.getId());
        assertEquals(2L, subTask.getTaskId());
        assertEquals("Ôn từ", subTask.getTitle());
        assertEquals(subTask.getTitle(), subTask.getName());
        assertEquals("Ôn 20 từ cũ", subTask.getDescription());
        assertEquals(date, subTask.getScheduledDate());
        assertEquals(3, subTask.getOrderIndex());
        assertEquals(createdAt, subTask.getCreatedAt());

        // Các thông tin không bắt buộc có thể xóa mà không đổi trạng thái hoàn thành.
        subTask.setDescription(null);
        subTask.setScheduledDate(null);
        assertNull(subTask.getDescription());
        assertNull(subTask.getScheduledDate());
        assertFalse(subTask.isDone());
    }

    @Test
    @DisplayName("Constructor đầy đủ giữ nguyên dữ liệu SubTask đã lưu")
    void fullConstructorRestoresAllFields() {
        OffsetDateTime created = OffsetDateTime.parse("2026-10-27T09:00:00+07:00");
        LocalDate scheduled = LocalDate.of(2026, 10, 28);
        SubTask subTask = new SubTask(10L, 2L, "Học từ", "20 từ",
                scheduled, false, null, 4, created);

        assertEquals(10L, subTask.getId());
        assertEquals(2L, subTask.getTaskId());
        assertEquals("Học từ", subTask.getTitle());
        assertEquals("20 từ", subTask.getDescription());
        assertEquals(scheduled, subTask.getScheduledDate());
        assertEquals(4, subTask.getOrderIndex());
        assertEquals(created, subTask.getCreatedAt());
        assertFalse(subTask.isDone());
        assertNull(subTask.getCompletedAt());
    }

    @Test
    @DisplayName("Dữ liệu không hợp lệ bị từ chối và không ghi đè dữ liệu cũ")
    void invalidUpdatesPreserveValidSubTaskData() {
        SubTask subTask = new SubTask("Học từ");
        subTask.setId(10L);
        subTask.setTaskId(2L);
        subTask.setOrderIndex(3);

        assertThrows(IllegalArgumentException.class, () -> subTask.setId(0L));
        assertThrows(IllegalArgumentException.class, () -> subTask.setTaskId(-1L));
        assertThrows(IllegalArgumentException.class, () -> subTask.setTitle(null));
        assertThrows(IllegalArgumentException.class, () -> subTask.setOrderIndex(-1));
        assertEquals(10L, subTask.getId());
        assertEquals(2L, subTask.getTaskId());
        assertEquals("Học từ", subTask.getTitle());
        assertEquals(3, subTask.getOrderIndex());

        assertThrows(IllegalArgumentException.class,
                () -> new SubTask(null, null, "Học từ", null, null,
                        false, null, 0, null));
        // Giới hạn VARCHAR(255): đúng 255 ký tự vẫn hợp lệ.
        subTask.setTitle("a".repeat(255));
        assertEquals(255, subTask.getTitle().length());
    }

    @Test
    @DisplayName("Bỏ tick nhiều lần an toàn; tick lại tạo thời điểm hiện tại")
    void completionCanBeClearedAndRecordedAgain() {
        OffsetDateTime oldTime = OffsetDateTime.parse("2000-01-01T00:00:00Z");
        SubTask subTask = new SubTask(10L, 2L, "Học từ", null, null,
                true, oldTime, 0, oldTime);

        subTask.markUndone();
        subTask.markUndone();
        assertFalse(subTask.isDone());
        assertNull(subTask.getCompletedAt());

        OffsetDateTime before = OffsetDateTime.now();
        subTask.markDone();
        OffsetDateTime after = OffsetDateTime.now();
        assertTrue(subTask.isDone());
        assertNotNull(subTask.getCompletedAt());
        assertFalse(subTask.getCompletedAt().isBefore(before));
        assertFalse(subTask.getCompletedAt().isAfter(after));
        assertEquals(oldTime, subTask.getCreatedAt());
    }

    @Test
    void restoringSavedSubTaskPreservesCompletionTime() {
        OffsetDateTime completed = OffsetDateTime.parse("2026-10-28T10:00:00+07:00");
        SubTask subTask = new SubTask(1L, 2L, "Học từ", null, null,
                true, completed, 0, completed.minusDays(1));

        subTask.markDone();

        assertEquals(completed, subTask.getCompletedAt());
        assertEquals(2L, subTask.getTaskId());
        assertThrows(IllegalArgumentException.class,
                () -> new SubTask(1L, 2L, "Học từ", null, null,
                        false, completed, 0, completed));
        assertThrows(IllegalArgumentException.class,
                () -> new SubTask(1L, 2L, "Học từ", null, null,
                        true, null, 0, completed));
    }

    @Test
    void editableFieldsAllowUnscheduledWorkAndRejectInvalidValues() {
        SubTask subTask = new SubTask("Học từ");
        subTask.setTitle("Ôn từ");
        subTask.setScheduledDate(java.time.LocalDate.of(2026, 10, 28));
        subTask.setScheduledDate(null);

        assertEquals("Ôn từ", subTask.getName());
        assertNull(subTask.getScheduledDate());
        assertThrows(IllegalArgumentException.class, () -> subTask.setTitle(" "));
        assertThrows(IllegalArgumentException.class, () -> subTask.setTitle("a".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> subTask.setOrderIndex(-1));
    }

    @Test
    void newSubTaskIsUndone() {
        SubTask subTask = new SubTask("Học 20 từ");

        assertFalse(subTask.isDone());
        assertNull(subTask.getCompletedAt());
        assertEquals(1, subTask.getTotalCount());
        assertEquals(0, subTask.getCompletedCount());
        assertEquals(0.0, subTask.getProgress(), 0.001);
        assertTrue(subTask.getChildren().isEmpty());
    }

    @Test
    void markingDoneRecordsCompletionTime() {
        SubTask subTask = new SubTask("Học 20 từ");

        subTask.markDone();

        assertTrue(subTask.isDone());
        assertNotNull(subTask.getCompletedAt());
        assertEquals(1, subTask.getCompletedCount());
        assertEquals(100.0, subTask.getProgress(), 0.001);
    }

    @Test
    void markingDoneAgainKeepsOriginalCompletionTime() {
        SubTask subTask = new SubTask("Học 20 từ");
        subTask.markDone();

        OffsetDateTime originalTime = subTask.getCompletedAt();

        subTask.markDone();

        assertTrue(subTask.isDone());
        assertEquals(originalTime, subTask.getCompletedAt());
    }

    @Test
    void markingUndoneClearsCompletionTime() {
        SubTask subTask = new SubTask("Học 20 từ");
        subTask.markDone();

        subTask.markUndone();

        assertFalse(subTask.isDone());
        assertNull(subTask.getCompletedAt());
        assertEquals(0, subTask.getCompletedCount());
        assertEquals(0.0, subTask.getProgress(), 0.001);
    }
}
