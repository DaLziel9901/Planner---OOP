package com.example.planner.controller;

import com.example.planner.service.SubTaskService;
import com.example.planner.service.TaskService;
import com.example.planner.service.PlanTreeService;
import com.example.planner.service.ProgressPresentation;
import com.example.planner.model.Task;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import com.example.planner.model.SubTask;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
@RequestMapping("/subtasks")
public class SubTaskController {
    private final SubTaskService service;
    private final TaskService tasks;
    private final PlanTreeService trees;
    public SubTaskController(SubTaskService service,TaskService tasks,PlanTreeService trees) { this.service=service; this.tasks=tasks; this.trees=trees; }
    @GetMapping
    public String list(@RequestParam(name="taskId",required=false) Long taskId,Model model) {
        Task main=taskId==null?null:ProgressReadSupport.primary(()->tasks.getById(taskId));
        if(taskId!=null) ProgressReadSupport.primary(()->service.findByTaskId(taskId));
        var snapshot=taskId==null?java.util.Optional.<PlanTreeService.TaskSnapshot>empty():ProgressReadSupport.tree(()->trees.loadTask(taskId));
        Task displayed=snapshot.map(PlanTreeService.TaskSnapshot::task).orElse(main);
        model.addAttribute("task",displayed);
        model.addAttribute("subTasks",snapshot.isPresent()?displayed.getSubTasks():List.of());
        model.addAttribute("progress",snapshot.isPresent()?ProgressPresentation.from(displayed):ProgressPresentation.unavailable());
        model.addAttribute("treeAvailable",snapshot.isPresent());
        model.addAttribute("errors",taskId == null ? Map.of("taskId","Chọn một Task để xem SubTask") : Map.of());
        return "subtasks/list";
    }
    @GetMapping("/new")
    public String newForm(@RequestParam(name="taskId",required=false) Long taskId,Model model) {
        return prepareForm(null,taskId,taskId == null ? "" : taskId.toString(),readForm(Map.of()),
                taskId == null ? Map.of("taskId","Chọn một Task trước khi tạo SubTask") : Map.of(),model);
    }
    @GetMapping("/{id}")
    public String detail(@PathVariable("id") long id,Model model) {
        SubTask leaf=ProgressReadSupport.primary(()->service.getById(id));
        Task main=ProgressReadSupport.primary(()->tasks.getById(leaf.getTaskId()));
        var snapshot=ProgressReadSupport.tree(()->trees.loadTask(leaf.getTaskId()));
        Task displayed=snapshot.map(PlanTreeService.TaskSnapshot::task).orElse(main);
        SubTask current=snapshot.isEmpty()?leaf:displayed.getSubTasks().stream().filter(s->s.getId()==id).findFirst()
                .orElseThrow(()->new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Không tìm thấy SubTask"));
        model.addAttribute("subTask",current); model.addAttribute("task",displayed);
        model.addAttribute("progress",snapshot.isPresent()?ProgressPresentation.from(displayed):ProgressPresentation.unavailable());
        model.addAttribute("treeAvailable",snapshot.isPresent());
        return "subtasks/detail";
    }
    @GetMapping("/{id}/edit")
    public String edit(@PathVariable("id") long id,Model model) {
        SubTask leaf=service.getById(id);
        Map<String,String> form=Map.of("title",leaf.getTitle(),"description",text(leaf.getDescription()),
                "scheduledDate",text(leaf.getScheduledDate()),"orderIndex",text(leaf.getOrderIndex()));
        return prepareForm(id,leaf.getTaskId(),text(leaf.getTaskId()),form,Map.of(),model);
    }
    @PostMapping
    public String create(@RequestParam Map<String,String> params,Model model,RedirectAttributes flash) {
        return save(null,null,params,model,flash);
    }
    @PostMapping("/{id}/edit")
    public String update(@PathVariable("id") long id,@RequestParam Map<String,String> params,Model model,RedirectAttributes flash) {
        SubTask current=service.getById(id); // ID thiếu vẫn 404 ngay cả khi form sai.
        return save(id,current.getTaskId(),params,model,flash);
    }
    @PostMapping("/{id}/completion")
    public String completion(@PathVariable("id") long id, @RequestParam("done") String done,
                             @RequestParam(name="source", required=false) String source,
                             @RequestParam(name="sourceId", required=false) String sourceId,
                             RedirectAttributes flash) {
        if (!"true".equals(done) && !"false".equals(done)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "done phải là true hoặc false");
        }
        boolean completed = Boolean.parseBoolean(done);
        long taskId = service.setCompletion(id, completed);
        flash.addFlashAttribute("message", completed ? "Đã đánh dấu hoàn thành SubTask" : "Đã bỏ đánh dấu hoàn thành SubTask");
        return "redirect:" + completionSource(id, taskId, source, sourceId);
    }

    private String completionSource(long id, long taskId, String source, String sourceId) {
        String fallback = "/subtasks/" + id;
        if (source == null || sourceId == null) return fallback;
        long target;
        try {
            target = Long.parseLong(sourceId);
        } catch (NumberFormatException exception) {
            return fallback;
        }
        if (target <= 0) return fallback;
        return switch (source) {
            case "task" -> target == taskId ? "/tasks/" + taskId : fallback;
            case "subtasks" -> target == taskId ? "/subtasks?taskId=" + taskId : fallback;
            case "plan" -> tasks.isAncestor(taskId, target, null) ? "/plans/" + target : fallback;
            case "subplan" -> tasks.isAncestor(taskId, null, target) ? "/subplans/" + target : fallback;
            default -> fallback;
        };
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") long id,RedirectAttributes flash) {
        long taskId=service.delete(id);
        flash.addFlashAttribute("message","Đã xóa SubTask");
        return "redirect:/tasks/" + taskId;
    }

    private String save(Long id,Long storedTaskId,Map<String,String> params,Model model,RedirectAttributes flash) {
        Map<String,String> form=readForm(params); Map<String,String> errors=new LinkedHashMap<>();
        String taskInput=id == null ? params.getOrDefault("taskId","") : storedTaskId.toString();
        Long taskId=id == null ? readTaskId(taskInput,errors) : storedTaskId;
        if (taskId != null) try { tasks.getById(taskId); }
        catch (ResponseStatusException exception) {
            if (id != null || exception.getStatusCode().value() != 404) throw exception;
            errors.put("taskId","Task không tồn tại. Chọn lại Task và lưu SubTask"); taskId=null;
        }
        String title=form.get("title");
        if (title.isBlank() || title.codePointCount(0,title.length())>255)
            errors.put("title","Tên SubTask cần 1–255 ký tự, không chỉ chứa khoảng trắng");
        LocalDate date=null;
        if (!form.get("scheduledDate").isBlank()) try { date=LocalDate.parse(form.get("scheduledDate")); }
        catch (DateTimeParseException exception) { errors.put("scheduledDate","Ngày không hợp lệ, dùng yyyy-MM-dd"); }
        int order=0;
        try {
            if (!form.get("orderIndex").isBlank()) order=Integer.parseInt(form.get("orderIndex"));
            if (order<0) errors.put("orderIndex","Thứ tự không được âm");
        } catch (NumberFormatException exception) { errors.put("orderIndex","Thứ tự phải là số nguyên không âm trong phạm vi cho phép"); }
        if (!errors.isEmpty()) return prepareForm(id,taskId,taskInput,form,errors,model);
        long savedId;
        if (id == null) try { savedId=service.create(taskId,title,form.get("description"),date,order); }
        catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 404) throw exception;
            // Service đã thoát giao dịch; Task có thể vừa bị xóa sau lần kiểm tra form.
            errors.put("taskId","Task không còn tồn tại. Chọn lại Task và lưu SubTask");
            return prepareForm(null,null,taskInput,form,errors,model);
        }
        else { service.update(id,title,form.get("description"),date,order); savedId=id; }
        flash.addFlashAttribute("message",id == null ? "Đã tạo SubTask" : "Đã cập nhật SubTask");
        return "redirect:/subtasks/" + savedId;
    }
    private Long readTaskId(String value,Map<String,String> errors) {
        if (!value.isBlank()) try { long id=Long.parseLong(value); if (id>0) return id; }
        catch (NumberFormatException exception) { /* Hiển thị lỗi ngay trên form. */ }
        errors.put("taskId","Chọn một Task có ID là số nguyên dương"); return null;
    }
    private Map<String,String> readForm(Map<String,String> params) {
        Map<String,String> form=new LinkedHashMap<>();
        // Chỉ nhận nội dung; taskId tạo mới đọc riêng, trạng thái/identity không nhận từ form.
        for (String field:new String[]{"title","description","scheduledDate","orderIndex"})
            form.put(field,params.getOrDefault(field,field.equals("orderIndex") ? "0" : ""));
        return form;
    }
    private String prepareForm(Long id,Long taskId,String input,Map<String,String> form,Map<String,String> errors,Model model) {
        model.addAttribute("task",taskId == null ? null : tasks.getById(taskId));
        model.addAttribute("taskIdInput",input); model.addAttribute("form",form); model.addAttribute("errors",errors);
        model.addAttribute("editing",id != null); model.addAttribute("formAction",id == null ? "/subtasks" : "/subtasks/" + id + "/edit");
        return "subtasks/form";
    }
    private String text(Object value) { return value == null ? "" : value.toString(); }
}
