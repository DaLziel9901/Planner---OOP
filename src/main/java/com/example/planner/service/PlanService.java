package com.example.planner.service;

import com.example.planner.model.Plan;
import com.example.planner.repository.PlanRepository;
import com.example.planner.repository.SubPlanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
public class PlanService {

    private final PlanRepository planRepository;

    private final SubPlanRepository subPlanRepository;

    public PlanService(PlanRepository planRepository, SubPlanRepository subPlanRepository) {
        this.planRepository = planRepository;
        this.subPlanRepository = subPlanRepository;
    }

    // Chỉ đọc thuộc tính Plan, chưa nạp cây và tiến độ.
    public List<Plan> findAll(long ownerId) {
        OwnerScope.require(ownerId);
        return planRepository.findAllForOwner(ownerId);
    }

    public Plan getById(long ownerId, long id) {
        OwnerScope.require(ownerId);
        validateId(id);

        return planRepository.findByIdForOwner(ownerId, id)
                .orElseThrow(() -> notFound(id));
    }

    public long create(
            long ownerId,
            String title,
            String goal,
            LocalDate startDate,
            LocalDate endDate) {
        OwnerScope.require(ownerId);
        validateInput(title, startDate, endDate);

        Plan plan = new Plan(title);
        plan.setGoal(normalizeGoal(goal));
        plan.setDates(startDate, endDate);

        return planRepository.insertForOwner(ownerId, plan);
    }

    public void update(
            long ownerId,
            long id,
            String title,
            String goal,
            LocalDate startDate,
            LocalDate endDate) {
        Plan plan = getById(ownerId, id);

        validateInput(title, startDate, endDate);

        plan.setTitle(title);
        plan.setGoal(normalizeGoal(goal));
        plan.setDates(startDate, endDate);

        boolean updated = planRepository.updateForOwner(ownerId, plan);

        // Plan có thể đã bị xóa sau lúc đọc và trước lúc UPDATE.
        if (!updated) {
            throw notFound(id);
        }
    }

    @Transactional
    public void delete(long ownerId, long id) {
        OwnerScope.require(ownerId);
        validateId(id);
        if (!subPlanRepository.lockPlanForOwner(ownerId, id)) {
            throw notFound(id);
        }

        boolean deleted = planRepository.deleteByIdForOwner(ownerId, id);

        if (!deleted) {
            throw notFound(id);
        }
    }

    private void validateId(long id) {
        if (id <= 0) {
            throw notFound(id);
        }
    }

    private void validateInput(
            String title,
            LocalDate startDate,
            LocalDate endDate) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(
                    "Tên kế hoạch không được để trống"
            );
        }

        if (title.codePointCount(0, title.length()) > 255) {
            throw new IllegalArgumentException(
                    "Tên kế hoạch không được vượt quá 255 ký tự"
            );
        }

        if (startDate != null
                && endDate != null
                && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException(
                    "Ngày bắt đầu không được sau ngày kết thúc"
            );
        }
    }

    private String normalizeGoal(String goal) {
        if (goal == null || goal.isBlank()) {
            return null;
        }

        return goal;
    }

    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Không tìm thấy kế hoạch có ID " + id
        );
    }
}
