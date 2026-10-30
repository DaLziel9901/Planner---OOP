package com.example.planner.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Composite gốc: chứa Task trực tiếp và SubPlan tầng 1. */
public class Plan implements PlanNode {

    private Long id;
    private String title;
    private String goal;
    private LocalDate startDate;
    private LocalDate endDate;
    private final OffsetDateTime createdAt;
    // Các danh sách dùng dựng cây Java, không phải cột trong bảng plan.
    private final List<Task> tasks = new ArrayList<>();
    private final List<SubPlan> subPlans = new ArrayList<>();

    public Plan(String title) {
        this(null, title, null, null, null, OffsetDateTime.now());
    }

    // Dùng khi đọc các cột từ DB; repository/service lắp các nút con riêng.
    public Plan(Long id, String title, String goal, LocalDate startDate,
                LocalDate endDate, OffsetDateTime createdAt) {
        if (createdAt == null) {
            throw new IllegalArgumentException("Thời điểm tạo không được null");
        }
        setId(id);
        setTitle(title);
        setDates(startDate, endDate);
        this.goal = goal;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title == null || title.isBlank()
                || title.codePointCount(0, title.length()) > 255) {
            throw new IllegalArgumentException("Tên Plan phải có 1–255 ký tự, không chỉ chứa khoảng trắng");
        }
        this.title = title;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void addTask(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("Task không được null");
        }
        if (task.getSubPlanId() != null
                || (task.getPlanId() != null && !task.getPlanId().equals(id))) {
            throw new IllegalArgumentException("Task không thuộc trực tiếp Plan này");
        }
        for (Task existing : tasks) {
            if (existing == task || (task.getId() != null && task.getId().equals(existing.getId()))) {
                throw new IllegalArgumentException("Task đã có trong Plan");
            }
        }
        tasks.add(task);
    }

    public void addSubPlan(SubPlan subPlan) {
        if (subPlan == null) {
            throw new IllegalArgumentException("SubPlan không được null");
        }
        if (subPlan.getParentSubPlanId() != null
                || (subPlan.getPlanId() != null && !subPlan.getPlanId().equals(id))) {
            throw new IllegalArgumentException("SubPlan không thuộc tầng 1 của Plan này");
        }
        for (SubPlan existing : subPlans) {
            if (existing == subPlan || (subPlan.getId() != null && subPlan.getId().equals(existing.getId()))) {
                throw new IllegalArgumentException("SubPlan đã có trong Plan");
            }
        }
        subPlans.add(subPlan);
    }

    // Chỉ thay đổi cây trong bộ nhớ, không xóa bản ghi trong DB.
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
        // Không có quy tắc thứ tự chung giữa hai loại: SubPlan trước, Task sau.
        List<PlanNode> children = new ArrayList<>();
        children.addAll(getSubPlans());
        children.addAll(getTasks());
        return List.copyOf(children);
    }

    @Override
    public int getCompletedCount() {
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
