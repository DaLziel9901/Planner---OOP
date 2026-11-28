package com.example.planner.ai;

import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import com.example.planner.model.SubTask;
import com.example.planner.model.Task;
import com.example.planner.repository.PlanRepository;
import com.example.planner.repository.SubPlanRepository;
import com.example.planner.repository.SubTaskRepository;
import com.example.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GeneratedPlanService {
    private final PlanDraftService drafts;
    private final PlanRepository plans;
    private final SubPlanRepository subPlans;
    private final TaskRepository tasks;
    private final SubTaskRepository subTasks;

    public GeneratedPlanService(PlanDraftService drafts, PlanRepository plans, SubPlanRepository subPlans,
                                TaskRepository tasks, SubTaskRepository subTasks) {
        this.drafts = drafts;
        this.plans = plans;
        this.subPlans = subPlans;
        this.tasks = tasks;
        this.subTasks = subTasks;
    }

    @Transactional
    public long confirm(long ownerId, String json) {
        if (ownerId <= 0) {
            throw new IllegalArgumentException("Thiếu tài khoản xác thực.");
        }
        PlanDraft draft = drafts.parse(json);
        var plan = new Plan(draft.title());
        plan.setGoal(draft.goal());
        plan.setDates(draft.startDate(), draft.endDate());
        long planId = plans.insertForOwner(ownerId, plan);
        // The new root is uncommitted and cannot be mutated by another transaction.
        saveSubPlans(ownerId, planId, null, draft.subPlans());
        saveTasks(ownerId, planId, null, draft.tasks());
        return planId;
    }

    private void saveSubPlans(long ownerId, long planId, Long parentId, List<PlanDraft.SubPlanDraft> drafts) {
        for (int index = 0; index < drafts.size(); index++) {
            var draft = drafts.get(index);
            var node = new SubPlan(draft.title());
            node.setLocation(planId, parentId);
            node.setDescription(draft.description());
            node.setDates(draft.startDate(), draft.endDate());
            node.setOrderIndex(index);
            long subPlanId = subPlans.insertForOwner(ownerId, node).orElseThrow(GeneratedPlanService::parentMissing);
            saveSubPlans(ownerId, planId, subPlanId, draft.subPlans());
            saveTasks(ownerId, null, subPlanId, draft.tasks());
        }
    }

    private void saveTasks(long ownerId, Long planId, Long subPlanId, List<PlanDraft.TaskDraft> drafts) {
        for (int index = 0; index < drafts.size(); index++) {
            var draft = drafts.get(index);
            var node = new Task(draft.title());
            node.setLocation(planId, subPlanId);
            node.setDescription(draft.description());
            node.setDates(draft.startDate(), draft.endDate());
            node.setOrderIndex(index);
            long taskId = tasks.insertForOwner(ownerId, node).orElseThrow(GeneratedPlanService::parentMissing);
            saveSubTasks(ownerId, taskId, draft.subTasks());
        }
    }

    private void saveSubTasks(long ownerId, long taskId, List<PlanDraft.SubTaskDraft> drafts) {
        for (int index = 0; index < drafts.size(); index++) {
            var draft = drafts.get(index);
            var node = new SubTask(draft.title());
            node.setTaskId(taskId);
            node.setDescription(draft.description());
            node.setScheduledDate(draft.scheduledDate());
            node.setOrderIndex(index);
            subTasks.insertForOwner(ownerId, node).orElseThrow(GeneratedPlanService::parentMissing);
        }
    }

    private static IllegalStateException parentMissing() {
        return new IllegalStateException("Không tìm thấy nơi chứa bản nháp trong phạm vi tài khoản.");
    }
}
