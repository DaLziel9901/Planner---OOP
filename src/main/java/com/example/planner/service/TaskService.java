package com.example.planner.service;

import com.example.planner.model.Task;
import com.example.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskService {
    private final TaskRepository repository;
    public TaskService(TaskRepository repository) { this.repository = repository; }
    public List<Task> findIndependent() { return repository.findIndependent(); }

    public Task getById(long id) {
        validateId(id);
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional
    public long create(String title, String description, LocalDate start, LocalDate end, int order) {
        Task task = new Task(title);
        task.setDescription(description);
        task.setDates(start, end);
        task.setOrderIndex(order);
        return repository.insert(task);
    }

    @Transactional
    public void update(long id, String title, String description, LocalDate start, LocalDate end, int order) {
        Task current = lockAndRead(id);
        // Dựng bản cập nhật đã validation đầy đủ trước khi ghi; giữ identity/nơi chứa/thời điểm tạo.
        Task updated = new Task(current.getId(), current.getPlanId(), current.getSubPlanId(),
                title, description, start, end, order, current.getCreatedAt());
        if (!repository.update(updated)) throw notFound(id);
    }

    @Transactional
    public void delete(long id) {
        lockAndRead(id);
        if (!repository.deleteById(id)) throw notFound(id);
    }

    private Task lockAndRead(long id) {
        validateId(id);
        if (!repository.lockTask(id)) throw notFound(id);
        return getById(id);
    }

    private void validateId(long id) { if (id <= 0) throw notFound(id); }
    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy Task độc lập có ID " + id);
    }
}
