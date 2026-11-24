package com.example.planner.service;

import com.example.planner.model.SubTask;
import com.example.planner.repository.SubTaskRepository;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;

@Service
public class SubTaskService {
    private final SubTaskRepository repository;
    private final TaskService tasks;
    public SubTaskService(SubTaskRepository repository, TaskService tasks) { this.repository=repository; this.tasks=tasks; }
    public List<SubTask> findByTaskId(long taskId) {
        validateTask(taskId); tasks.getById(taskId);
        return repository.findByTaskId(taskId);
    }
    public SubTask getById(long id) {
        if (id <= 0) throw notFound(id);
        return repository.findById(id).orElseThrow(()->notFound(id));
    }
    @Transactional
    public long create(long taskId,String title,String description,LocalDate date,int order) {
        validateTask(taskId);
        SubTask leaf=new SubTask(title); leaf.setTaskId(taskId); leaf.setDescription(description);
        leaf.setScheduledDate(date); leaf.setOrderIndex(order);
        tasks.lockForWrite(taskId);
        return repository.insert(leaf);
    }
    @Transactional
    public void update(long id,String title,String description,LocalDate date,int order) {
        SubTask current=lockAndRead(id);
        SubTask updated=new SubTask(current.getId(),current.getTaskId(),title,description,date,
                current.isDone(),current.getCompletedAt(),order,current.getCreatedAt());
        if (!repository.update(updated)) throw notFound(id);
    }
    @Transactional
    public long setCompletion(long id, boolean done) {
        SubTask current = lockAndRead(id);
        if (done) current.markDone();
        else current.markUndone();
        if (!repository.updateCompletion(current)) throw notFound(id);
        return current.getTaskId();
    }
    @Transactional
    public long delete(long id) {
        SubTask current=lockAndRead(id);
        if (!repository.deleteById(id)) throw notFound(id);
        return current.getTaskId();
    }
    private SubTask lockAndRead(long id) {
        SubTask before=getById(id);
        tasks.lockForWrite(before.getTaskId());
        SubTask current=getById(id);
        if (!Objects.equals(before.getTaskId(),current.getTaskId())) throw notFound(id);
        return current;
    }
    private void validateTask(long id) {
        if (id <= 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy Task có ID " + id);
    }
    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy SubTask có ID " + id);
    }
}
