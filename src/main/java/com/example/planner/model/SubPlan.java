package com.example.planner.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class SubPlan implements PlanNode {

    private Long id;
    private Long planId;
    private Long parentSubPlanId;
    private String title;
    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
    private int orderIndex;
    private final OffsetDateTime createdAt;
    private final List<Task> tasks = new ArrayList<>();
    private final List<SubPlan> subPlans = new ArrayList<>();

   //Default constructor
    public SubPlan(String title) {
        this(null, null, null, title, null, null, null, 0, OffsetDateTime.now());
    }
    
    // Constructor
    public SubPlan(Long id, Long planId, Long parentSubPlanId, String title,
                   String description, LocalDate startDate, LocalDate endDate,
                   int orderIndex, OffsetDateTime createdAt) {
        if (createdAt == null) {
            throw new IllegalArgumentException("Thời điểm tạo không được null");
        }
        setLocation(planId, parentSubPlanId);
        setId(id);
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

    public Long getParentSubPlanId() {
        return parentSubPlanId;
    }

    public void setLocation(Long planId, Long parentSubPlanId) {
        if ((planId != null && planId <= 0)
                || (parentSubPlanId != null && parentSubPlanId <= 0)) {
            throw new IllegalArgumentException("ID nơi chứa phải lớn hơn 0");
        }
        if (id != null && id.equals(parentSubPlanId)) {
            throw new IllegalArgumentException("SubPlan không thể làm parent của chính nó");
        }
        this.planId = planId;
        this.parentSubPlanId = parentSubPlanId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title == null || title.isBlank()
                || title.codePointCount(0, title.length()) > 255) {
            throw new IllegalArgumentException("Tên SubPlan không được rỗng, nhỏ hơn 255 ký tự");
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

    public void addTask(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("Task không được null");
        }
        if (task.getPlanId() != null
                || (task.getSubPlanId() != null && !task.getSubPlanId().equals(id))) {
            throw new IllegalArgumentException("Task không thuộc SubPlan này");
        }
        tasks.add(task);
    }

    public void addSubPlan(SubPlan subPlan) {
        if (subPlan == null) {
            throw new IllegalArgumentException("SubPlan không được null");
        }
        if (!Objects.equals(planId, subPlan.getPlanId())
                || (subPlan.getParentSubPlanId() != null
                && !subPlan.getParentSubPlanId().equals(id))) {
            throw new IllegalArgumentException("SubPlan con khác Plan hoặc khác cha");
        }
        subPlans.add(subPlan);
    }

    public boolean removeTask(Task task) {
        return tasks.remove(task);
    }

    public boolean removeSubPlan(SubPlan subPlan) {
        return subPlans.remove(subPlan);
    }

    public List<Task> getTasks() {
        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparingInt(Task::getOrderIndex)
                .thenComparing(Task::getId, Comparator.nullsLast(Long::compareTo)));
        return List.copyOf(sorted);
    }

    public List<SubPlan> getSubPlans() {
        List<SubPlan> sorted = new ArrayList<>(subPlans);
        sorted.sort(Comparator.comparingInt(SubPlan::getOrderIndex)
                .thenComparing(SubPlan::getId, Comparator.nullsLast(Long::compareTo)));
        return List.copyOf(sorted);
    }

    @Override
    public String getName() {
        return title;
    }

    @Override
    public List<PlanNode> getChildren() {
        List<PlanNode> children = new ArrayList<>();
        children.addAll(getSubPlans());
        children.addAll(getTasks());
        return List.copyOf(children);
    }

    @Override
    public int getCompletedCount() {
        // Đệ quy/đa hình: SubPlan con tiếp tục hỏi con của nó, tới lá SubTask.
        int completed = 0;
        for (PlanNode child : getChildren()) {
            completed += child.getCompletedCount();
        }
        return completed;
    }

    @Override
    public int getTotalCount() {
        int total = 0;
        for (PlanNode child : getChildren()) {
            total += child.getTotalCount();
        }
        return total;
    }

    @Override
    public double getProgress() {
        int total = getTotalCount();
        return total == 0 ? 0.0 : getCompletedCount() * 100.0 / total;
    }
}
