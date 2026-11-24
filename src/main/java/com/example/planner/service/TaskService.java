package com.example.planner.service;

import com.example.planner.model.Task;
import com.example.planner.model.SubPlan;
import java.util.Objects;
import com.example.planner.repository.TaskRepository;
import com.example.planner.repository.PlanRepository;
import com.example.planner.repository.SubPlanRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskService {
    private final TaskRepository repository;
    private final PlanRepository plans;
    private final SubPlanRepository subPlans;
    public TaskService(TaskRepository repository, PlanRepository plans, SubPlanRepository subPlans) {
        this.repository = repository; this.plans = plans; this.subPlans = subPlans;
    }
    public List<Task> findByPlanId(long planId) {
        validateId(planId);
        if (plans.findById(planId).isEmpty()) throw parentNotFound("Plan", planId);
        return repository.findByPlanId(planId);
    }
    public List<Task> findBySubPlanId(long subPlanId) {
        getSubPlan(subPlanId);
        return repository.findBySubPlanId(subPlanId);
    }
    @Transactional
    public long create(Long planId, Long subPlanId, String title, String description, LocalDate start, LocalDate end, int order) {
        Task task = new Task(title);
        task.setLocation(planId, subPlanId);
        task.setDescription(description); task.setDates(start, end); task.setOrderIndex(order);
        lockLocation(planId, subPlanId);
        return repository.insert(task);
    }
    public List<Task> findIndependent() { return repository.findIndependent(); }

    public Task getById(long id) {
        validateId(id);
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    public boolean isAncestor(long taskId, Long planId, Long subPlanId) {
        Task task = repository.findById(taskId).orElse(null);
        if (task == null) return false;
        if (task.getPlanId() != null) return task.getPlanId().equals(planId);

        var visited = new HashSet<Long>();
        Long cursor = task.getSubPlanId();
        while (cursor != null && visited.add(cursor)) {
            SubPlan ancestor = subPlans.findById(cursor).orElse(null);
            if (ancestor == null) return false;
            if (cursor.equals(subPlanId) || Objects.equals(ancestor.getPlanId(), planId)) return true;
            cursor = ancestor.getParentSubPlanId();
        }
        return false;
    }

    @Transactional
    public long create(String title, String description, LocalDate start, LocalDate end, int order) {
        return create(null, null, title, description, start, end, order);
    }

    @Transactional
    public void update(long id, String title, String description, LocalDate start, LocalDate end, int order) {
        Task current = lockAndRead(id);
        // Dựng bản cập nhật đã validation đầy đủ trước khi ghi; giữ identity/nơi chứa/thời điểm tạo.
        Task updated = new Task(current.getId(), current.getPlanId(), current.getSubPlanId(),
                title, description, start, end, order, current.getCreatedAt());
        if (!repository.update(updated)) throw notFound(id);
    }

    @Transactional
    public void delete(long id) {
        lockAndRead(id);
        if (!repository.deleteById(id)) throw notFound(id);
    }

    private Task lockAndRead(long id) {
        Task before = getById(id); // Chỉ xác định nơi chứa để chọn khóa Plan trước Task.
        lockLocation(before.getPlanId(), before.getSubPlanId());
        if (!repository.lockTask(id)) throw notFound(id);
        Task current = getById(id);
        if (!Objects.equals(before.getPlanId(), current.getPlanId())
                || !Objects.equals(before.getSubPlanId(), current.getSubPlanId())) throw notFound(id);
        return current;
    }

    // SubTaskService gọi trong giao dịch ghi để tái dùng đúng thứ tự khóa Plan → Task.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Task lockForWrite(long id) { return lockAndRead(id); }

    private void lockLocation(Long planId, Long subPlanId) {
        if (planId == null && subPlanId == null) return;
        long ownerPlan = planId != null ? planId : getSubPlan(subPlanId).getPlanId();
        if (!subPlans.lockPlan(ownerPlan)) throw parentNotFound("Plan", ownerPlan);
        if (subPlanId != null && getSubPlan(subPlanId).getPlanId() != ownerPlan)
            throw parentNotFound("SubPlan", subPlanId);
    }

    private SubPlan getSubPlan(long id) {
        if (id <= 0) throw parentNotFound("SubPlan", id);
        return subPlans.findById(id).orElseThrow(() -> parentNotFound("SubPlan", id));
    }

    private ResponseStatusException parentNotFound(String type, long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy " + type + " có ID " + id);
    }

    private void validateId(long id) { if (id <= 0) throw notFound(id); }
    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy Task có ID " + id);
    }
}
