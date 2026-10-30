package com.example.planner.controller;

import com.example.planner.model.Plan;
import com.example.planner.service.PlanService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping("/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("plans", planService.findAll());
        model.addAttribute("progressAvailable", false);

        return "plans/list";
    }

    @GetMapping("/new")
    public String showCreateForm(Model model) {
        Map<String, String> form = readForm(Map.of());

        prepareForm(model, form, Map.of(), false, "/plans");

        return "plans/form";
    }

    @PostMapping
    public String create(
            @RequestParam Map<String, String> parameters,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        Map<String, String> form = readForm(parameters);
        Map<String, String> errors = new LinkedHashMap<>();

        validateTitle(form.get("title"), errors);

        LocalDate startDate = readDate(form, "startDate", errors);
        LocalDate endDate = readDate(form, "endDate", errors);
        validateDateRange(startDate, endDate, errors);

        if (!errors.isEmpty()) {
            prepareForm(model, form, errors, false, "/plans");
            return "plans/form";
        }

        long id = planService.create(
                form.get("title"),
                form.get("goal"),
                startDate,
                endDate
        );

        redirectAttributes.addFlashAttribute(
                "message", "Đã tạo kế hoạch"
        );

        return "redirect:/plans/" + id;
    }

    @GetMapping("/{id}")
    public String detail(
            @PathVariable("id") long id,
            Model model
    ) {
        model.addAttribute("plan", planService.getById(id));

        // Chưa nạp Task/SubPlan/SubTask, không hiển thị tiến độ.
        model.addAttribute("progressAvailable", false);

        return "plans/detail";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(
            @PathVariable("id") long id,
            Model model
    ) {
        Plan plan = planService.getById(id);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("title", plan.getTitle());
        form.put("goal", asText(plan.getGoal()));
        form.put("startDate", asText(plan.getStartDate()));
        form.put("endDate", asText(plan.getEndDate()));

        prepareForm(
                model, form, Map.of(), true,
                "/plans/" + id + "/edit"
        );

        return "plans/form";
    }

    @PostMapping("/{id}/edit")
    public String update(
            @PathVariable("id") long id,
            @RequestParam Map<String, String> parameters,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        // ID không tồn tại phải báo 404, kể cả khi form nhập sai.
        planService.getById(id);

        Map<String, String> form = readForm(parameters);
        Map<String, String> errors = new LinkedHashMap<>();

        validateTitle(form.get("title"), errors);

        LocalDate startDate = readDate(form, "startDate", errors);
        LocalDate endDate = readDate(form, "endDate", errors);
        validateDateRange(startDate, endDate, errors);

        if (!errors.isEmpty()) {
            prepareForm(
                    model, form, errors, true,
                    "/plans/" + id + "/edit"
            );
            return "plans/form";
        }

        planService.update(
                id,
                form.get("title"),
                form.get("goal"),
                startDate,
                endDate
        );

        redirectAttributes.addFlashAttribute(
                "message", "Đã cập nhật kế hoạch"
        );

        return "redirect:/plans/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable("id") long id,
            RedirectAttributes redirectAttributes
    ) {
        planService.delete(id);

        redirectAttributes.addFlashAttribute(
                "message", "Đã xóa kế hoạch và toàn bộ công việc bên dưới"
        );

        return "redirect:/plans";
    }

    private Map<String, String> readForm(
            Map<String, String> parameters
    ) {
        Map<String, String> form = new LinkedHashMap<>();

        // Chỉ nhận bốn trường cho phép, không lấy ID/createdAt từ form.
        form.put("title", parameters.getOrDefault("title", ""));
        form.put("goal", parameters.getOrDefault("goal", ""));
        form.put("startDate", parameters.getOrDefault("startDate", ""));
        form.put("endDate", parameters.getOrDefault("endDate", ""));

        return form;
    }

    private void validateTitle(
            String title,
            Map<String, String> errors
    ) {
        if (title.isBlank()) {
            errors.put("title", "Tên kế hoạch không được để trống");
        } else if (title.codePointCount(0, title.length()) > 255) {
            errors.put("title", "Tên kế hoạch tối đa 255 ký tự");
        }
    }

    private LocalDate readDate(
            Map<String, String> form,
            String field,
            Map<String, String> errors
    ) {
        String value = form.get(field);

        if (value.isBlank()) {
            return null; // Ngày được phép để trống.
        }

        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            errors.put(field, "Ngày không hợp lệ, dùng dạng yyyy-MM-dd");
            return null;
        }
    }

    private void validateDateRange(
            LocalDate startDate,
            LocalDate endDate,
            Map<String, String> errors
    ) {
        if (startDate != null
                && endDate != null
                && startDate.isAfter(endDate)) {
            errors.put(
                    "endDate",
                    "Ngày kết thúc không được trước ngày bắt đầu"
            );
        }
    }

    private void prepareForm(
            Model model,
            Map<String, String> form,
            Map<String, String> errors,
            boolean editing,
            String formAction
    ) {
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("editing", editing);
        model.addAttribute("formAction", formAction);
    }

    private String asText(Object value) {
        return value == null ? "" : value.toString();
    }
}