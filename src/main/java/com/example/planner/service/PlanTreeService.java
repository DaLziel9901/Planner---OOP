package com.example.planner.service;

import com.example.planner.model.*;
import com.example.planner.repository.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW)
public class PlanTreeService {
    private final PlanRepository plans;
    private final SubPlanRepository subs;
    private final TaskRepository tasks;
    private final SubTaskRepository leaves;
    public PlanTreeService(PlanRepository plans,SubPlanRepository subs,TaskRepository tasks,SubTaskRepository leaves) {
        this.plans=plans; this.subs=subs; this.tasks=tasks; this.leaves=leaves;
    }
    public record PlanSnapshot(Plan plan,List<SubPlan> subPlans) { public PlanSnapshot { subPlans=List.copyOf(subPlans); } }
    public record SubPlanSnapshot(Plan plan,SubPlan subPlan,List<SubPlan> allSubPlans) { public SubPlanSnapshot { allSubPlans=List.copyOf(allSubPlans); } }
    public record TaskSnapshot(Task task,Plan plan,SubPlan subPlan) { }
    public record TaskListSnapshot(List<Task> tasks,Plan plan,SubPlan subPlan) { public TaskListSnapshot { tasks=List.copyOf(tasks); } }
    public static class InvalidTreeException extends RuntimeException { public InvalidTreeException(String message) { super(message); } }
    public List<Plan> loadPlans(long ownerId) {
        OwnerScope.require(ownerId);
        List<Plan> rows=plans.findAllForOwner(ownerId);
        if (rows.isEmpty()) return List.of();
        List<Long> ids=rows.stream().map(Plan::getId).toList();
        return List.copyOf(assemble(ownerId,rows,subs.findByPlanIdsForOwner(ownerId,ids),tasks.findByPlanIdsForOwner(ownerId,ids)).plans().values());
    }
    public PlanSnapshot loadPlan(long ownerId,long id) {
        OwnerScope.require(ownerId);
        Graph graph=planGraph(ownerId,id);
        return new PlanSnapshot(graph.plans().get(id),List.copyOf(graph.subs().values()));
    }
    public SubPlanSnapshot loadSubPlan(long ownerId,long id) {
        OwnerScope.require(ownerId);
        SubPlan main=requireSubPlan(ownerId,id);
        Plan root=plans.findByIdForOwner(ownerId,main.getPlanId()).orElseThrow(()->bad("SubPlan thiếu Plan cha"));
        Graph graph=planGraph(ownerId,root);
        SubPlan selected=graph.subs().get(id);
        if (selected == null) throw bad("SubPlan thiếu trong cây");
        return new SubPlanSnapshot(graph.plans().get(main.getPlanId()),selected,List.copyOf(graph.subs().values()));
    }
    public TaskSnapshot loadTask(long ownerId,long id) {
        OwnerScope.require(ownerId);
        if (id<=0) throw missing();
        Task row=tasks.findByIdForOwner(ownerId,id).orElseThrow(PlanTreeService::missing);
        Plan plan=null; SubPlan sub=null;
        if (row.getPlanId()!=null) plan=plans.findByIdForOwner(ownerId,row.getPlanId()).orElseThrow(()->bad("Task thiếu Plan cha"));
        if (row.getSubPlanId()!=null) {
            sub=subs.findByIdForOwner(ownerId,row.getSubPlanId()).orElseThrow(()->bad("Task thiếu SubPlan cha"));
            plan=plans.findByIdForOwner(ownerId,sub.getPlanId()).orElseThrow(()->bad("SubPlan thiếu Plan cha"));
        }
        var result=index(List.of(row),Task::getId,PlanTreeService::copyTask);
        attachLeaves(ownerId,result);
        return new TaskSnapshot(result.get(id),plan==null?null:copyPlan(plan),sub==null?null:copySub(sub));
    }
    public TaskListSnapshot loadTasks(long ownerId,Long planId,Long subPlanId) {
        OwnerScope.require(ownerId);
        if (planId!=null && subPlanId!=null) throw new IllegalArgumentException("Chỉ một nơi chứa Task");
        Plan plan=null; SubPlan sub=null; List<Task> rows;
        if (planId!=null) { plan=requirePlan(ownerId,planId); rows=tasks.findByPlanIdForOwner(ownerId,planId); }
        else if (subPlanId!=null) {
            sub=requireSubPlan(ownerId,subPlanId); plan=plans.findByIdForOwner(ownerId,sub.getPlanId()).orElseThrow(()->bad("SubPlan thiếu Plan cha"));
            rows=tasks.findBySubPlanIdForOwner(ownerId,subPlanId);
        }
        else rows=tasks.findIndependentForOwner(ownerId);
        var result=index(rows,Task::getId,PlanTreeService::copyTask);
        for (Task task:result.values()) {
            if (!Objects.equals(task.getPlanId(),planId) || !Objects.equals(task.getSubPlanId(),subPlanId))
                throw bad("Task sai phạm vi nơi chứa");
        }
        attachLeaves(ownerId,result);
        return new TaskListSnapshot(List.copyOf(result.values()),plan==null?null:copyPlan(plan),sub==null?null:copySub(sub));
    }

    private record Graph(Map<Long,Plan> plans,Map<Long,SubPlan> subs) { }
    private Graph planGraph(long ownerId,long id) {
        return planGraph(ownerId,requirePlan(ownerId,id));
    }
    private Graph planGraph(long ownerId,Plan main) {
        return assemble(ownerId,List.of(main),subs.findByPlanIdForOwner(ownerId,main.getId()),tasks.findByPlanIdsForOwner(ownerId,List.of(main.getId())));
    }
    private Graph assemble(long ownerId,List<Plan> planRows,List<SubPlan> subRows,List<Task> taskRows) {
        var roots=index(planRows,Plan::getId,PlanTreeService::copyPlan);
        var nodes=index(subRows,SubPlan::getId,PlanTreeService::copySub);
        var work=index(taskRows,Task::getId,PlanTreeService::copyTask);
        // Kiểm toàn bộ quan hệ trước khi gắn/đệ quy tính counts.
        for (SubPlan node:nodes.values()) {
            if (!roots.containsKey(node.getPlanId())) throw bad("SubPlan khác Plan");
            if (node.getParentSubPlanId()!=null) {
                SubPlan parent=nodes.get(node.getParentSubPlanId());
                if (parent==null || !parent.getPlanId().equals(node.getPlanId())) throw bad("Cha SubPlan thiếu hoặc khác Plan");
            }
        }
        for (SubPlan node:nodes.values()) {
            Set<Long> path=new HashSet<>(); int depth=0; SubPlan current=node;
            while (current!=null) {
                if (!path.add(current.getId())) throw bad("Cây có vòng lặp");
                if (++depth>5) throw bad("Cây vượt năm tầng SubPlan");
                current=current.getParentSubPlanId()==null?null:nodes.get(current.getParentSubPlanId());
            }
        }
        for (Task task:work.values()) {
            if (task.getSubPlanId()!=null) {
                if (!nodes.containsKey(task.getSubPlanId())) throw bad("Task thiếu SubPlan cha");
            } else if (task.getPlanId()==null || !roots.containsKey(task.getPlanId())) throw bad("Task sai Plan cha");
        }
        attachLeaves(ownerId,work);
        for (Task task:work.values()) {
            if (task.getSubPlanId()!=null) nodes.get(task.getSubPlanId()).addTask(task);
            else roots.get(task.getPlanId()).addTask(task);
        }
        for (SubPlan node:nodes.values()) {
            if (node.getParentSubPlanId()==null) roots.get(node.getPlanId()).addSubPlan(node);
            else nodes.get(node.getParentSubPlanId()).addSubPlan(node);
        }
        return new Graph(roots,nodes);
    }
    private void attachLeaves(long ownerId,Map<Long,Task> work) {
        if (work.isEmpty()) return;
        var selected=index(leaves.findByTaskIdsForOwner(ownerId,List.copyOf(work.keySet())),SubTask::getId,PlanTreeService::copyLeaf);
        for (SubTask leaf:selected.values()) if (!work.containsKey(leaf.getTaskId())) throw bad("SubTask thiếu Task cha");
        for (SubTask leaf:selected.values()) work.get(leaf.getTaskId()).addSubTask(leaf);
    }
    private Plan requirePlan(long ownerId,long id) {
        if (id<=0) throw missing();
        return plans.findByIdForOwner(ownerId,id).orElseThrow(PlanTreeService::missing);
    }
    private SubPlan requireSubPlan(long ownerId,long id) {
        if (id<=0) throw missing();
        return subs.findByIdForOwner(ownerId,id).orElseThrow(PlanTreeService::missing);
    }
    private static <T> Map<Long,T> index(List<T> rows,Function<T,Long> identity,Function<T,T> copy) {
        Map<Long,T> result=new LinkedHashMap<>();
        for (T row:rows) {
            Long id=identity.apply(row);
            if (id==null || id<=0 || result.containsKey(id)) throw bad("Identity thiếu hoặc trùng trong cây");
            result.put(id,copy.apply(row));
        }
        return result;
    }
    private static Plan copyPlan(Plan p) { return new Plan(p.getId(),p.getTitle(),p.getGoal(),p.getStartDate(),p.getEndDate(),p.getCreatedAt()); }
    private static SubPlan copySub(SubPlan s) { return new SubPlan(s.getId(),s.getPlanId(),s.getParentSubPlanId(),s.getTitle(),s.getDescription(),s.getStartDate(),s.getEndDate(),s.getOrderIndex(),s.getCreatedAt()); }
    private static Task copyTask(Task t) { return new Task(t.getId(),t.getPlanId(),t.getSubPlanId(),t.getTitle(),t.getDescription(),t.getStartDate(),t.getEndDate(),t.getOrderIndex(),t.getCreatedAt()); }
    private static SubTask copyLeaf(SubTask s) { return new SubTask(s.getId(),s.getTaskId(),s.getTitle(),s.getDescription(),s.getScheduledDate(),s.isDone(),s.getCompletedAt(),s.getOrderIndex(),s.getCreatedAt()); }
    private static InvalidTreeException bad(String message) { return new InvalidTreeException(message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy đối tượng kế hoạch"); }
}
