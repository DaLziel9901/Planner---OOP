package com.example.planner.controller;

import com.example.planner.service.TaskService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.example.planner.model.Task;

@Controller
@RequestMapping("/tasks")
public class TaskController {
    private final TaskService service;
    public TaskController(TaskService service) { this.service = service; }
    @GetMapping
    public String list(@RequestParam Map<String,String> params, Model model) {
        requireIndependentRequest(params);
        model.addAttribute("tasks", service.findIndependent());
        model.addAttribute("progressAvailable", false);
        return "tasks/list";
    }

    @GetMapping("/new")
    public String newForm(@RequestParam Map<String,String> params, Model model) {
        requireIndependentRequest(params);
        return prepareForm(null, readForm(Map.of()), Map.of(), model);
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable("id") long id, Model model) {
        model.addAttribute("task", service.getById(id));
        model.addAttribute("progressAvailable", false);
        return "tasks/detail";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable("id") long id, Model model) {
        Task task = service.getById(id);
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
        service.getById(id); // ID thiếu vẫn 404 ngay cả khi form sai.
        return save(id, params, model, flash);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") long id, RedirectAttributes flash) {
        service.delete(id);
        flash.addFlashAttribute("message", "Đã xóa Task và toàn bộ SubTask bên dưới");
        return "redirect:/tasks";
    }

    private String save(Long id, Map<String,String> params, Model model, RedirectAttributes flash) {
        Map<String,String> form = readForm(params);
        Map<String,String> errors = new LinkedHashMap<>();
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
        if (id == null) savedId = service.create(title, form.get("description"), start, end, order);
        else {
            service.update(id, title, form.get("description"), start, end, order);
            savedId = id;
        }
        flash.addFlashAttribute("message", id == null ? "Đã tạo Task" : "Đã cập nhật Task");
        return "redirect:/tasks/" + savedId;
    }

    private Map<String,String> readForm(Map<String,String> params) {
        Map<String,String> form = new LinkedHashMap<>();
        // Chỉ nhận nội dung; ID, FK, createdAt và trạng thái không lấy từ form.
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
        model.addAttribute("form", form); model.addAttribute("errors", errors);
        model.addAttribute("editing", id != null);
        model.addAttribute("formAction", id == null ? "/tasks" : "/tasks/" + id + "/edit");
        return "tasks/form";
    }

    private void requireIndependentRequest(Map<String,String> params) {
        if (params.containsKey("planId") || params.containsKey("subPlanId"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Luồng hiện tại chỉ quản lý Task độc lập");
    }

    private String text(Object value) { return value == null ? "" : value.toString(); }
}
