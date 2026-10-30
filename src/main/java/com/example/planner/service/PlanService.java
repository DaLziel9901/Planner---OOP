package com.example.planner.service;

import com.example.planner.model.Plan;
import com.example.planner.repository.PlanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
public class PlanService {

    private final PlanRepository planRepository;

    public PlanService(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    // Chỉ đọc thuộc tính Plan, chưa nạp cây và tiến độ.
    public List<Plan> findAll() {
        return planRepository.findAll();
    }

    public Plan getById(long id) {
        validateId(id);

        return planRepository.findById(id)
                .orElseThrow(() -> notFound(id));
    }

    public long create(
            String title,
            String goal,
            LocalDate startDate,
            LocalDate endDate) {
        validateInput(title, startDate, endDate);

        Plan plan = new Plan(title);
        plan.setGoal(normalizeGoal(goal));
        plan.setDates(startDate, endDate);

        return planRepository.insert(plan);
    }

    public void update(
            long id,
            String title,
            String goal,
            LocalDate startDate,
            LocalDate endDate) {
        Plan plan = getById(id);

        validateInput(title, startDate, endDate);

        plan.setTitle(title);
        plan.setGoal(normalizeGoal(goal));
        plan.setDates(startDate, endDate);

        boolean updated = planRepository.update(plan);

        // Plan có thể đã bị xóa sau lúc đọc và trước lúc UPDATE.
        if (!updated) {
            throw notFound(id);
        }
    }

    public void delete(long id) {
        validateId(id);

        boolean deleted = planRepository.deleteById(id);

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