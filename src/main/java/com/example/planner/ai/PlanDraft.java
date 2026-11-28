package com.example.planner.ai;

import java.time.LocalDate;
import java.util.List;

public record PlanDraft(String title, String goal, LocalDate startDate, LocalDate endDate,
                        List<SubPlanDraft> subPlans, List<TaskDraft> tasks) {
    public record SubPlanDraft(String title, String description, LocalDate startDate, LocalDate endDate,
                               List<SubPlanDraft> subPlans, List<TaskDraft> tasks) {
    }

    public record TaskDraft(String title, String description, LocalDate startDate, LocalDate endDate,
                            List<SubTaskDraft> subTasks) {
    }

    public record SubTaskDraft(String title, String description, LocalDate scheduledDate) {
    }
}
