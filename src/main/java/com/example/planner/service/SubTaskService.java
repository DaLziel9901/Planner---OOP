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
    public List<SubTask> findByTaskId(long ownerId, long taskId) {
        OwnerScope.require(ownerId);
        validateTask(taskId); tasks.getById(ownerId, taskId);
        return repository.findByTaskIdForOwner(ownerId, taskId);
    }
    public SubTask getById(long ownerId, long id) {
        OwnerScope.require(ownerId);
        if (id <= 0) throw notFound(id);
        return repository.findByIdForOwner(ownerId, id).orElseThrow(()->notFound(id));
    }
    @Transactional
    public long create(long ownerId, long taskId,String title,String description,LocalDate date,int order) {
        OwnerScope.require(ownerId);
        validateTask(taskId);
        SubTask leaf=new SubTask(title); leaf.setTaskId(taskId); leaf.setDescription(description);
        leaf.setScheduledDate(date); leaf.setOrderIndex(order);
        tasks.lockForWrite(ownerId, taskId);
        return repository.insertForOwner(ownerId, leaf).orElseThrow(() -> taskNotFound(taskId));
    }
    @Transactional
    public void update(long ownerId, long id,String title,String description,LocalDate date,int order) {
        OwnerScope.require(ownerId);
        SubTask current=lockAndRead(ownerId, id);
        SubTask updated=new SubTask(current.getId(),current.getTaskId(),title,description,date,
                current.isDone(),current.getCompletedAt(),order,current.getCreatedAt());
        if (!repository.updateForOwner(ownerId, updated)) throw notFound(id);
    }
    @Transactional
    public long setCompletion(long ownerId, long id, boolean done) {
        OwnerScope.require(ownerId);
        SubTask current = lockAndRead(ownerId, id);
        if (done) current.markDone();
        else current.markUndone();
        if (!repository.updateCompletionForOwner(ownerId, current)) throw notFound(id);
        return current.getTaskId();
    }
    @Transactional
    public long delete(long ownerId, long id) {
        OwnerScope.require(ownerId);
        SubTask current=lockAndRead(ownerId, id);
        if (!repository.deleteByIdForOwner(ownerId, id)) throw notFound(id);
        return current.getTaskId();
    }
    private SubTask lockAndRead(long ownerId, long id) {
        SubTask before=getById(ownerId, id);
        tasks.lockForWrite(ownerId, before.getTaskId());
        SubTask current=getById(ownerId, id);
        if (!Objects.equals(before.getTaskId(),current.getTaskId())) throw notFound(id);
        return current;
    }
    private void validateTask(long id) {
        if (id <= 0) throw taskNotFound(id);
    }
    private ResponseStatusException taskNotFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy Task có ID " + id);
    }
    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy SubTask có ID " + id);
    }

}
