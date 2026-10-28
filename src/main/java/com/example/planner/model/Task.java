package com.example.planner.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Task implements PlanNode {

    private Long id;
    private Long planId;
    private Long subPlanId;
    private String title;
    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
    private int orderIndex;
    private final OffsetDateTime createdAt;
    private final List<SubTask> subTasks = new ArrayList<>();

    // Default constructor
    public Task(String title) {
        this(null, null, null, title, null, null, null, 0,
                OffsetDateTime.now());
    }

    public Task(Long id, Long planId, Long subPlanId, String title,
                String description, LocalDate startDate, LocalDate endDate,
                int orderIndex, OffsetDateTime createdAt) {
        if (createdAt == null) {
            throw new IllegalArgumentException("thời điểm tạo không được null");
        }
        setId(id);
        setLocation(planId, subPlanId);
        setTitle(title);
        setDates(startDate, endDate);
        setOrderIndex(orderIndex);
        this.description = description;
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

    public Long getPlanId() {
        return planId;
    }

    public Long getSubPlanId() {
        return subPlanId;
    }

    public void setLocation(Long planId, Long subPlanId) {
        if (planId != null && subPlanId != null) {
            throw new IllegalArgumentException("Task không thể thuộc cả Plan và SubPlan");
        }
        if ((planId != null && planId <= 0)
                || (subPlanId != null && subPlanId <= 0)) {
            throw new IllegalArgumentException("ID nơi chứa phải lớn hơn 0");
        }
        this.planId = planId;
        this.subPlanId = subPlanId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title == null || title.isBlank()
                || title.codePointCount(0, title.length()) > 255) {
            throw new IllegalArgumentException("Tên Task không được để trống, nhỏ hơn 255 ký tự");
        }
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setDates(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Ngày bắt đầu không được sau ngày kết thúc");
        }
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        if (orderIndex < 0) {
            throw new IllegalArgumentException("orderIndex không được âm");
        }
        this.orderIndex = orderIndex;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void addSubTask(SubTask subTask) {
        if (subTask == null) {
            throw new IllegalArgumentException(
                    "SubTask không được null"
            );
        }

        for (SubTask existing : subTasks) {
            if (existing == subTask || (subTask.getId() != null
                    && subTask.getId().equals(existing.getId()))) {
                throw new IllegalArgumentException("SubTask đã có trong Task này");
            }
        }
        if (subTask.getTaskId() != null && !subTask.getTaskId().equals(id)) {
            throw new IllegalArgumentException("SubTask có taskId khác Task này");
        }
        subTasks.add(subTask);
    }

    public List<SubTask> getSubTasks() {
        List<SubTask> sorted = new ArrayList<>(subTasks);
        sorted.sort(Comparator.comparingInt(SubTask::getOrderIndex)
                .thenComparing(SubTask::getId, Comparator.nullsLast(Long::compareTo)));
        return List.copyOf(sorted);
    }

    // Chỉ cập nhật cây trong bộ nhớ; không thực hiện DELETE trong database.
    public boolean removeSubTask(SubTask subTask) {
        return subTasks.remove(subTask);
    }

    @Override
    public String getName() {
        return title;
    }

    @Override
    public int getCompletedCount() {
        int completed = 0;

        for (SubTask subTask : subTasks) {
            completed += subTask.getCompletedCount();
        }

        return completed;
    }

    @Override
    public int getTotalCount() {
        int total = 0;

        for (SubTask subTask : subTasks) {
            total += subTask.getTotalCount();
        }

        return total;
    }

    @Override
    public double getProgress() {
        int total = getTotalCount();

        if (total == 0) {
            return 0.0;
        }

        return getCompletedCount() * 100.0 / total;
    }

    @Override
    public List<PlanNode> getChildren() {
        return List.copyOf(getSubTasks());
    }
}
