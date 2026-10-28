package com.example.planner.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public class SubTask implements PlanNode {

    private Long id;
    private Long taskId;
    private String title;
    private String description;
    private LocalDate scheduledDate;
    private boolean done;
    private OffsetDateTime completedAt;
    private int orderIndex;
    private final OffsetDateTime createdAt;

    //Default constructor
    public SubTask(String title) {
        this(null, null, title, null, null, false, null, 0,
                OffsetDateTime.now());
    }

    //Constructor
    public SubTask(Long id, Long taskId, String title, String description,
                   LocalDate scheduledDate, boolean done,
                   OffsetDateTime completedAt, int orderIndex,
                   OffsetDateTime createdAt) {
        if (done != (completedAt != null)) {
            throw new IllegalArgumentException("done và completedAt không nhất quán");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Thời gian tạo không được null");
        }
        setId(id);
        setTaskId(taskId);
        setTitle(title);
        setOrderIndex(orderIndex);
        this.description = description;
        this.scheduledDate = scheduledDate;
        this.done = done;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("id phải lớn hơn 0");
        }
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        if (taskId != null && taskId <= 0) {
            throw new IllegalArgumentException("taskId phải lớn hơn 0");
        }
        this.taskId = taskId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title == null || title.isBlank()
                || title.codePointCount(0, title.length()) > 255) {
            throw new IllegalArgumentException("Tên SubTask phải có 1–255 ký tự, không chỉ chứa khoảng trắng");
        }
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getScheduledDate() {
        return scheduledDate;
    }

    public void setScheduledDate(LocalDate scheduledDate) {
        this.scheduledDate = scheduledDate;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        if (orderIndex < 0) {
            throw new IllegalArgumentException("orderIndex phải mang giá trị dương");
        }
        this.orderIndex = orderIndex;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String getName() {
        return title;
    }

    public boolean isDone() {
        return done;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void markDone() {
        if (!done) {
            done = true;
            completedAt = OffsetDateTime.now();
        }
    }

    public void markUndone() {
        done = false;
        completedAt = null;
    }

    @Override
    public int getCompletedCount() {
        return done ? 1 : 0;
    }

    @Override
    public int getTotalCount() {
        return 1;
    }

    @Override
    public double getProgress() {
        return done ? 100.0 : 0.0;
    }

    @Override
    public List<PlanNode> getChildren() {
        return List.of();
    }
}
