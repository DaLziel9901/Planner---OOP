package com.example.planner.controller;

import com.example.planner.service.TaskService;
import com.example.planner.service.PlanService;
import com.example.planner.service.SubPlanService;
import com.example.planner.service.PlanTreeService;
import com.example.planner.service.ProgressPresentation;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.example.planner.model.Task;

@Controller
@RequestMapping("/tasks")
public class TaskController {
    private final TaskService service;
    private final PlanService plans;
    private final SubPlanService subPlans;
    private final PlanTreeService trees;
    public TaskController(TaskService service, PlanService plans, SubPlanService subPlans, PlanTreeService trees) {
        this.service = service; this.plans = plans; this.subPlans = subPlans; this.trees = trees;
    }
    @GetMapping
    public String list(@RequestParam Map<String,String> params, Model model) {
        long ownerId = RequestOwner.require();
        Location location = requestLocation(params);
        locationContext(location, model);
        List<Task> main=ProgressReadSupport.primary(()->location.planId()!=null ? service.findByPlanId(ownerId, location.planId())
                : location.subPlanId()!=null ? service.findBySubPlanId(ownerId, location.subPlanId()) : service.findIndependent(ownerId));
        var snapshot=ProgressReadSupport.tree(()->trees.loadTasks(ownerId, location.planId(),location.subPlanId()));
        List<Task> displayed=snapshot.map(PlanTreeService.TaskListSnapshot::tasks).orElse(main);
        snapshot.ifPresent(s->setLocationContext(location,s.plan(),s.subPlan(),model));
        model.addAttribute("tasks",displayed);
        model.addAttribute("progressById",ProgressReadSupport.progress(displayed,Task::getId,snapshot.isPresent()));
        model.addAttribute("progressAvailable",snapshot.isPresent());
        return "tasks/list";
    }

    @GetMapping("/new")
    public String newForm(@RequestParam Map<String,String> params, Model model) {
        Location location = requestLocation(params);
        locationContext(location, model);
        model.addAttribute("locationForm", locationForm(params));
        return prepareForm(null, readForm(Map.of()), Map.of(), model);
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable("id") long id, Model model) {
        long ownerId = RequestOwner.require();
        Task task = ProgressReadSupport.primary(()->service.getById(ownerId, id));
        locationContext(new Location(task.getPlanId(), task.getSubPlanId()), model);
        var snapshot=ProgressReadSupport.tree(()->trees.loadTask(ownerId, id));
        Task displayed=snapshot.map(PlanTreeService.TaskSnapshot::task).orElse(task);
        snapshot.ifPresent(s->setLocationContext(new Location(displayed.getPlanId(),displayed.getSubPlanId()),s.plan(),s.subPlan(),model));
        model.addAttribute("task",displayed);
        model.addAttribute("subTasks",snapshot.isPresent()?displayed.getSubTasks():List.of());
        model.addAttribute("progress",snapshot.isPresent()?ProgressPresentation.from(displayed):ProgressPresentation.unavailable());
        model.addAttribute("progressAvailable",snapshot.isPresent()); model.addAttribute("treeAvailable",snapshot.isPresent());
        return "tasks/detail";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable("id") long id, Model model) {
        long ownerId = RequestOwner.require();
        Task task = service.getById(ownerId, id);
        locationContext(new Location(task.getPlanId(), task.getSubPlanId()), model);
        Map<String,String> form = new LinkedHashMap<>();
        form.put("title", task.getTitle()); form.put("description", text(task.getDescription()));
        form.put("startDate", text(task.getStartDate())); form.put("endDate", text(task.getEndDate()));
        form.put("orderIndex", text(task.getOrderIndex()));
        return prepareForm(id, form, Map.of(), model);
    }

    @PostMapping
    public String create(@RequestParam Map<String,String> params, Model model, RedirectAttributes flash) {
        return save(null, params, model, flash);
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable("id") long id, @RequestParam Map<String,String> params,
                         Model model, RedirectAttributes flash) {
        long ownerId = RequestOwner.require();
        Task task = service.getById(ownerId, id); // ID thiếu vẫn 404 ngay cả khi form sai.
        locationContext(new Location(task.getPlanId(), task.getSubPlanId()), model);
        return save(id, params, model, flash);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") long id, RedirectAttributes flash) {
        long ownerId = RequestOwner.require();
        Task task = service.getById(ownerId, id);
        service.delete(ownerId, id);
        flash.addFlashAttribute("message", "Đã xóa Task và toàn bộ SubTask bên dưới");
        return "redirect:" + listUrl(new Location(task.getPlanId(), task.getSubPlanId()));
    }

    private String save(Long id, Map<String,String> params, Model model, RedirectAttributes flash) {
        long ownerId = RequestOwner.require();
        Map<String,String> form = readForm(params);
        Map<String,String> errors = new LinkedHashMap<>();
        Location location = new Location(null, null);
        if (id == null) {
            model.addAttribute("locationForm", locationForm(params));
            location = readLocation(params, errors);
        }
        String title = form.get("title");
        if (title.isBlank() || title.codePointCount(0, title.length()) > 255)
            errors.put("title", "Tên Task cần 1–255 ký tự và không chỉ chứa khoảng trắng");
        LocalDate start = readDate(form, "startDate", errors);
        LocalDate end = readDate(form, "endDate", errors);
        if (start != null && end != null && start.isAfter(end))
            errors.put("endDate", "Ngày kết thúc không được trước ngày bắt đầu");
        int order = 0;
        try {
            if (!form.get("orderIndex").isBlank()) order = Integer.parseInt(form.get("orderIndex"));
            if (order < 0) errors.put("orderIndex", "Thứ tự không được âm");
        } catch (NumberFormatException exception) {
            errors.put("orderIndex", "Thứ tự phải là số nguyên không âm trong phạm vi cho phép");
        }
        if (!errors.isEmpty()) return prepareForm(id, form, errors, model);
        long savedId;
        if (id == null) {
            savedId = location.planId() == null && location.subPlanId() == null
                    ? service.create(ownerId, title, form.get("description"), start, end, order)
                    : service.create(ownerId, location.planId(), location.subPlanId(), title, form.get("description"), start, end, order);
        }
        else {
            service.update(ownerId, id, title, form.get("description"), start, end, order);
            savedId = id;
        }
        flash.addFlashAttribute("message", id == null ? "Đã tạo Task" : "Đã cập nhật Task");
        return "redirect:/tasks/" + savedId;
    }

    private Map<String,String> readForm(Map<String,String> params) {
        Map<String,String> form = new LinkedHashMap<>();
        // Nội dung chỉ nhận 5 trường; nơi chứa tạo mới đọc riêng, khi sửa bỏ qua FK gửi lên.
        for (String field : new String[]{"title", "description", "startDate", "endDate", "orderIndex"})
            form.put(field, params.getOrDefault(field, field.equals("orderIndex") ? "0" : ""));
        return form;
    }

    private LocalDate readDate(Map<String,String> form, String field, Map<String,String> errors) {
        if (form.get(field).isBlank()) return null;
        try { return LocalDate.parse(form.get(field)); }
        catch (DateTimeParseException exception) {
            errors.put(field, "Ngày không hợp lệ, dùng dạng yyyy-MM-dd"); return null;
        }
    }

    private String prepareForm(Long id, Map<String,String> form, Map<String,String> errors, Model model) {
        long ownerId = RequestOwner.require();
        model.addAttribute("form", form); model.addAttribute("errors", errors);
        model.addAttribute("editing", id != null);
        model.addAttribute("formAction", id == null ? "/tasks" : "/tasks/" + id + "/edit");
        if (id == null) {
            List<Plan> choices = plans.findAll(ownerId);
            List<SubPlan> subChoices = choices.stream().flatMap(plan -> subPlans.findByPlanId(ownerId, plan.getId()).stream()).toList();
            model.addAttribute("plans", choices); model.addAttribute("subPlans", subChoices);
            @SuppressWarnings("unchecked")
            Map<String,String> selected = (Map<String,String>) model.getAttribute("locationForm");
            model.addAttribute("planSelectionKnown", selected.get("planId").isBlank()
                    || choices.stream().anyMatch(plan -> plan.getId().toString().equals(selected.get("planId"))));
            model.addAttribute("subPlanSelectionKnown", selected.get("subPlanId").isBlank()
                    || subChoices.stream().anyMatch(sub -> sub.getId().toString().equals(selected.get("subPlanId"))));
        }
        return "tasks/form";
    }

    private record Location(Long planId, Long subPlanId) { }

    private Map<String,String> locationForm(Map<String,String> params) {
        return Map.of("planId", params.getOrDefault("planId", ""), "subPlanId", params.getOrDefault("subPlanId", ""));
    }

    private Location readLocation(Map<String,String> params, Map<String,String> errors) {
        Long planId = readParent(params.getOrDefault("planId", ""), "planId", errors);
        Long subPlanId = readParent(params.getOrDefault("subPlanId", ""), "subPlanId", errors);
        if (planId != null && subPlanId != null) errors.put("location", "Chỉ chọn một Plan hoặc một SubPlan, hoặc để trống cả hai");
        return new Location(planId, subPlanId);
    }

    private Long readParent(String value, String field, Map<String,String> errors) {
        if (value.isBlank()) return null;
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException exception) { /* Lỗi cú pháp trả lại cùng form. */ }
        errors.put(field, "ID nơi chứa phải là số nguyên dương"); return null;
    }

    private Location requestLocation(Map<String,String> params) {
        Map<String,String> errors = new LinkedHashMap<>();
        Location location = readLocation(params, errors);
        if (!errors.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nơi chứa Task không hợp lệ");
        return location;
    }

    private String listUrl(Location location) {
        return location.planId() != null ? "/tasks?planId=" + location.planId()
                : location.subPlanId() != null ? "/tasks?subPlanId=" + location.subPlanId() : "/tasks";
    }

    private void locationContext(Location location, Model model) {
        long ownerId = RequestOwner.require();
        Plan plan=location.planId()==null?null:ProgressReadSupport.primary(()->plans.getById(ownerId, location.planId()));
        SubPlan sub=location.subPlanId()==null?null:ProgressReadSupport.primary(()->subPlans.getById(ownerId, location.subPlanId()));
        setLocationContext(location,plan,sub,model);
    }
    private void setLocationContext(Location location,Plan plan,SubPlan sub,Model model) {
        String label = "Task độc lập";
        String parentUrl = null;
        if (location.planId() != null) {
            label = "Task trực tiếp của Plan: " + plan.getTitle();
            parentUrl = "/plans/" + location.planId();
        } else if (location.subPlanId() != null) {
            label = "Task trực tiếp của SubPlan: " + sub.getTitle();
            parentUrl = "/subplans/" + location.subPlanId();
        }
        model.addAttribute("locationLabel", label); model.addAttribute("parentUrl", parentUrl);
        model.addAttribute("taskListUrl", listUrl(location));
        model.addAttribute("newTaskUrl", listUrl(location).replace("/tasks", "/tasks/new"));
    }

    private String text(Object value) { return value == null ? "" : value.toString(); }
}
