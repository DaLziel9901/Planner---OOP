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
    public List<Task> findByPlanId(long ownerId, long planId) {
        OwnerScope.require(ownerId);
        validateId(planId);
        if (plans.findByIdForOwner(ownerId, planId).isEmpty()) throw parentNotFound("Plan", planId);
        return repository.findByPlanIdForOwner(ownerId, planId);
    }
    public List<Task> findBySubPlanId(long ownerId, long subPlanId) {
        OwnerScope.require(ownerId);
        getSubPlan(ownerId, subPlanId);
        return repository.findBySubPlanIdForOwner(ownerId, subPlanId);
    }
    @Transactional
    public long create(long ownerId, Long planId, Long subPlanId, String title, String description, LocalDate start, LocalDate end, int order) {
        OwnerScope.require(ownerId);
        Task task = new Task(title);
        task.setLocation(planId, subPlanId);
        task.setDescription(description); task.setDates(start, end); task.setOrderIndex(order);
        lockLocation(ownerId, planId, subPlanId);
        return repository.insertForOwner(ownerId, task).orElseThrow(() -> {
            if (subPlanId != null) return parentNotFound("SubPlan", subPlanId);
            if (planId != null) return parentNotFound("Plan", planId);
            return new IllegalStateException("Database không trả về ID Task độc lập vừa tạo");
        });
    }
    public List<Task> findIndependent(long ownerId) {
        OwnerScope.require(ownerId);
        return repository.findIndependentForOwner(ownerId);
    }

    public Task getById(long ownerId, long id) {
        OwnerScope.require(ownerId);
        validateId(id);
        return repository.findByIdForOwner(ownerId, id).orElseThrow(() -> notFound(id));
    }

    public boolean isAncestor(long ownerId, long taskId, Long planId, Long subPlanId) {
        OwnerScope.require(ownerId);
        Task task = repository.findByIdForOwner(ownerId, taskId).orElse(null);
        if (task == null) return false;
        if (task.getPlanId() != null) return task.getPlanId().equals(planId);

        var visited = new HashSet<Long>();
        Long cursor = task.getSubPlanId();
        while (cursor != null && visited.add(cursor)) {
            SubPlan ancestor = subPlans.findByIdForOwner(ownerId, cursor).orElse(null);
            if (ancestor == null) return false;
            if (cursor.equals(subPlanId) || Objects.equals(ancestor.getPlanId(), planId)) return true;
            cursor = ancestor.getParentSubPlanId();
        }
        return false;
    }

    @Transactional
    public long create(long ownerId, String title, String description, LocalDate start, LocalDate end, int order) {
        OwnerScope.require(ownerId);
        return create(ownerId, null, null, title, description, start, end, order);
    }

    @Transactional
    public void update(long ownerId, long id, String title, String description, LocalDate start, LocalDate end, int order) {
        OwnerScope.require(ownerId);
        Task current = lockAndRead(ownerId, id);
        // Dựng bản cập nhật đã validation đầy đủ trước khi ghi; giữ identity/nơi chứa/thời điểm tạo.
        Task updated = new Task(current.getId(), current.getPlanId(), current.getSubPlanId(),
                title, description, start, end, order, current.getCreatedAt());
        if (!repository.updateForOwner(ownerId, updated)) throw notFound(id);
    }

    @Transactional
    public void delete(long ownerId, long id) {
        OwnerScope.require(ownerId);
        lockAndRead(ownerId, id);
        if (!repository.deleteByIdForOwner(ownerId, id)) throw notFound(id);
    }

    private Task lockAndRead(long ownerId, long id) {
        Task before = getById(ownerId, id); // Chỉ xác định nơi chứa để chọn khóa Plan trước Task.
        lockLocation(ownerId, before.getPlanId(), before.getSubPlanId());
        if (!repository.lockTaskForOwner(ownerId, id)) throw notFound(id);
        Task current = getById(ownerId, id);
        if (!Objects.equals(before.getPlanId(), current.getPlanId())
                || !Objects.equals(before.getSubPlanId(), current.getSubPlanId())) throw notFound(id);
        return current;
    }

    // SubTaskService gọi trong giao dịch ghi để tái dùng đúng thứ tự khóa Plan → Task.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Task lockForWrite(long ownerId, long id) {
        return lockAndRead(ownerId, id);
    }

    private void lockLocation(long ownerId, Long planId, Long subPlanId) {
        if (planId == null && subPlanId == null) return;
        long ownerPlan = planId != null ? planId : getSubPlan(ownerId, subPlanId).getPlanId();
        if (!subPlans.lockPlanForOwner(ownerId, ownerPlan)) throw parentNotFound("Plan", ownerPlan);
        if (subPlanId != null && getSubPlan(ownerId, subPlanId).getPlanId() != ownerPlan)
            throw parentNotFound("SubPlan", subPlanId);
    }

    private SubPlan getSubPlan(long ownerId, long id) {
        if (id <= 0) throw parentNotFound("SubPlan", id);
        return subPlans.findByIdForOwner(ownerId, id).orElseThrow(() -> parentNotFound("SubPlan", id));
    }

    private ResponseStatusException parentNotFound(String type, long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy " + type + " có ID " + id);
    }

    private void validateId(long id) { if (id <= 0) throw notFound(id); }
    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy Task có ID " + id);
    }

}
