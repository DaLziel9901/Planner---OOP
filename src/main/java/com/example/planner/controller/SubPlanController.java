package com.example.planner.controller;

import com.example.planner.model.SubPlan;
import com.example.planner.service.PlanService;
import com.example.planner.service.SubPlanService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@Controller
@RequestMapping("/subplans")
public class SubPlanController {
    private final SubPlanService service;
    private final PlanService plans;

    public SubPlanController(SubPlanService service, PlanService plans) {
        this.service = service;
        this.plans = plans;
    }

    @GetMapping
    public String list(@RequestParam(name = "planId", required = false) Long planId, Model model) {
        model.addAttribute("plans", plans.findAll());
        if (planId != null) {
            model.addAttribute("plan", service.getPlan(planId));
            model.addAttribute("subPlans", service.findByPlanId(planId));
        }
        return "subplans/list";
    }

    @GetMapping("/new")
    public String showCreateForm(@RequestParam("planId") long planId,
                                 @RequestParam(name = "parentSubPlanId", required = false) Long parentId,
                                 Model model) {
        Map<String, String> form = readForm(Map.of());
        form.put("parentSubPlanId", text(parentId));
        return prepareForm(planId, null, form, Map.of(), model);
    }

    @PostMapping
    public String create(@RequestParam("planId") long planId,
                         @RequestParam Map<String, String> parameters,
                         Model model, RedirectAttributes redirect) {
        service.getPlan(planId);
        return save(null, planId, parameters, model, redirect);
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable("id") long id, Model model) {
        SubPlan subPlan = service.getById(id);
        model.addAttribute("subPlan", subPlan);
        model.addAttribute("taskListUrl", "/tasks?subPlanId=" + id);
        model.addAttribute("newTaskUrl", "/tasks/new?subPlanId=" + id);
        model.addAttribute("plan", service.getPlan(subPlan.getPlanId()));
        model.addAttribute("children", service.getDirectChildren(subPlan));
        model.addAttribute("warnings", service.getDateWarnings(subPlan));
        model.addAttribute("progressAvailable", false);
        return "subplans/detail";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable("id") long id, Model model) {
        SubPlan subPlan = service.getById(id);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("title", subPlan.getTitle());
        form.put("description", text(subPlan.getDescription()));
        form.put("parentSubPlanId", text(subPlan.getParentSubPlanId()));
        form.put("startDate", text(subPlan.getStartDate()));
        form.put("endDate", text(subPlan.getEndDate()));
        form.put("orderIndex", text(subPlan.getOrderIndex()));
        return prepareForm(subPlan.getPlanId(), id, form, Map.of(), model);
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable("id") long id, @RequestParam Map<String, String> parameters,
                         Model model, RedirectAttributes redirect) {
        // Không lấy planId từ form: chưa hỗ trợ chuyển SubPlan sang Plan khác.
        SubPlan current = service.getById(id);
        return save(id, current.getPlanId(), parameters, model, redirect);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") long id, RedirectAttributes redirect) {
        long planId = service.delete(id);
        redirect.addFlashAttribute("message", "Đã xóa SubPlan và toàn bộ cây con");
        return "redirect:/subplans?planId=" + planId;
    }

    private String save(Long id, long planId, Map<String, String> parameters,
                        Model model, RedirectAttributes redirect) {
        Map<String, String> form = readForm(parameters);
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDate start = readDate(form, "startDate", errors);
        LocalDate end = readDate(form, "endDate", errors);
        Long parentId = readParent(form.get("parentSubPlanId"), errors);
        int order = readOrder(form.get("orderIndex"), errors);
        if (!errors.isEmpty()) return prepareForm(planId, id, form, errors, model);

        long savedId;
        try {
            if (id == null) {
                savedId = service.create(planId, parentId, form.get("title"), form.get("description"), start, end, order);
            } else {
                service.update(id, parentId, form.get("title"), form.get("description"), start, end, order);
                savedId = id;
            }
        } catch (SubPlanService.InputException exception) {
            errors.put(exception.getField(), exception.getMessage());
            return prepareForm(planId, id, form, errors, model);
        }
        redirect.addFlashAttribute("message", "Đã lưu SubPlan");
        return "redirect:/subplans/" + savedId;
    }

    private String prepareForm(long planId, Long id, Map<String, String> form,
                               Map<String, String> errors, Model model) {
        model.addAttribute("plan", service.getPlan(planId));
        List<SubPlan> parents = service.findByPlanId(planId).stream()
                .filter(item -> !item.getId().equals(id)).toList();
        model.addAttribute("parents", parents);
        model.addAttribute("parentSelectionKnown", form.get("parentSubPlanId").isBlank()
                || parents.stream().anyMatch(item -> item.getId().toString().equals(form.get("parentSubPlanId"))));
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("editing", id != null);
        model.addAttribute("formAction", id == null ? "/subplans" : "/subplans/" + id + "/edit");
        return "subplans/form";
    }

    private Map<String, String> readForm(Map<String, String> parameters) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String key : new String[]{"title", "description", "parentSubPlanId", "startDate", "endDate"}) {
            form.put(key, parameters.getOrDefault(key, ""));
        }
        form.put("orderIndex", parameters.getOrDefault("orderIndex", "0"));
        return form;
    }

    private LocalDate readDate(Map<String, String> form, String field, Map<String, String> errors) {
        if (form.get(field).isBlank()) return null;
        try {
            return LocalDate.parse(form.get(field));
        } catch (DateTimeParseException exception) {
            errors.put(field, "Ngày không hợp lệ, dùng yyyy-MM-dd");
            return null;
        }
    }

    private Long readParent(String value, Map<String, String> errors) {
        if (value.isBlank()) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            errors.put("parentSubPlanId", "ID cha phải là số nguyên");
            return null;
        }
    }

    private int readOrder(String value, Map<String, String> errors) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            errors.put("orderIndex", "Thứ tự phải là số nguyên từ 0 trở lên");
            return 0;
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
