package com.example.planner.repository;

import com.example.planner.model.SubTask;
import com.example.planner.model.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.planner.support.AuthTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL", matches=".+")
class SubTaskRepositoryTest {
    @Test void batchLeavesStayScopedAndEmptyIdsAreSafe() {
        long other=tasks.insertForOwner(ownerId, new Task("Other")).orElseThrow();
        long first=repository.insertForOwner(ownerId, leaf(taskId,"Leaf A")).orElseThrow(); long second=repository.insertForOwner(ownerId, leaf(other,"Leaf B")).orElseThrow();
        assertEquals(List.of(first),repository.findByTaskIdsForOwner(ownerId, List.of(taskId,taskId)).stream().map(SubTask::getId).toList());
        assertEquals(List.of(first,second),repository.findByTaskIdsForOwner(ownerId, List.of(taskId,other)).stream().map(SubTask::getId).toList());
        assertTrue(repository.findByTaskIdsForOwner(ownerId, List.of()).isEmpty());
        assertTrue(repository.findByTaskIdsForOwner(ownerId, List.of(Long.MAX_VALUE)).isEmpty());
    }
    private long ownerId;
    private Connection connection;
    private JdbcTemplate jdbc;
    private SubTaskRepository repository;
    private TaskRepository tasks;
    private long taskId;

    @BeforeEach void setUp() throws Exception {
        connection = AuthTestDatabase.openConnection();
        connection.setAutoCommit(false);
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        ownerId = AuthTestDatabase.createFixtureAccount(jdbc);
        repository=new SubTaskRepository(jdbc); tasks=new TaskRepository(jdbc);
        taskId=tasks.insertForOwner(ownerId, new Task("Task fixture")).orElseThrow();
    }
    @AfterEach void tearDown() throws Exception {
        if (connection != null) try { connection.rollback(); } finally { connection.close(); }
    }
    private SubTask leaf(long parent, String title) { SubTask leaf=new SubTask(title); leaf.setTaskId(parent); return leaf; }

    @Test void crudRoundTripNullDateAndDefaultState() {
        long id=repository.insertForOwner(ownerId, leaf(taskId,"Tên")).orElseThrow(); assertTrue(id>0);
        SubTask saved=repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertEquals(taskId,saved.getTaskId()); assertNull(saved.getScheduledDate()); assertNull(saved.getDescription());
        assertFalse(saved.isDone()); assertNull(saved.getCompletedAt()); assertEquals(0,saved.getOrderIndex());
        assertTrue(saved.getChildren().isEmpty()); var created=saved.getCreatedAt().toInstant();
        saved.setTitle("Sửa"); saved.setDescription(""); saved.setScheduledDate(LocalDate.of(2026,11,16)); saved.setOrderIndex(2);
        assertTrue(repository.updateForOwner(ownerId, saved)); SubTask updated=repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertEquals("Sửa",updated.getTitle()); assertEquals("",updated.getDescription());
        assertEquals(LocalDate.of(2026,11,16),updated.getScheduledDate()); assertEquals(2,updated.getOrderIndex());
        assertEquals(created,updated.getCreatedAt().toInstant());
        saved.setScheduledDate(null); assertTrue(repository.updateForOwner(ownerId, saved)); assertNull(repository.findByIdForOwner(ownerId, id).orElseThrow().getScheduledDate());
        assertTrue(repository.deleteByIdForOwner(ownerId, id)); assertTrue(repository.findByIdForOwner(ownerId, id).isEmpty());
        assertFalse(repository.updateForOwner(ownerId, saved)); assertFalse(repository.deleteByIdForOwner(ownerId, id));
    }

    @Test void metadataCannotMoveParentOrOverwriteCompletionOrCreationTime() {
        long id=repository.insertForOwner(ownerId, leaf(taskId,"Đã làm")).orElseThrow();
        OffsetDateTime completed=OffsetDateTime.parse("2026-11-01T00:00:00Z");
        SubTask completion=new SubTask(id,taskId,"Không ghi tên này",null,null,true,completed,5,completed);
        assertTrue(repository.updateCompletionForOwner(ownerId, completion));
        SubTask saved=repository.findByIdForOwner(ownerId, id).orElseThrow(); var created=saved.getCreatedAt().toInstant();
        assertEquals("Đã làm",saved.getTitle());
        assertEquals(0,saved.getOrderIndex());
        long other=tasks.insertForOwner(ownerId, new Task("Task khác")).orElseThrow();
        saved.setTaskId(other); saved.markUndone(); saved.setTitle("Metadata mới");
        assertTrue(repository.updateForOwner(ownerId, saved)); SubTask actual=repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertEquals(taskId,actual.getTaskId()); assertTrue(actual.isDone());
        assertEquals(completed.toInstant(),actual.getCompletedAt().toInstant()); assertEquals(created,actual.getCreatedAt().toInstant());
        completion.markUndone();
        assertTrue(repository.updateCompletionForOwner(ownerId, completion));
        SubTask undone=repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertFalse(undone.isDone());
        assertNull(undone.getCompletedAt());
        assertEquals("Metadata mới",undone.getTitle());
        assertTrue(repository.deleteByIdForOwner(ownerId, id));
        assertFalse(repository.updateCompletionForOwner(ownerId, completion));
        SubTask prepared=leaf(taskId,"Tạo chưa hoàn thành"); prepared.markDone();
        SubTask inserted=repository.findByIdForOwner(ownerId, repository.insertForOwner(ownerId, prepared).orElseThrow()).orElseThrow();
        assertFalse(inserted.isDone()); assertNull(inserted.getCompletedAt());
    }

    @Test void duplicateUnicodeNamesSqlTextAndSortingStayScopedToTask() {
        String title="😀".repeat(255);
        long first=repository.insertForOwner(ownerId, leaf(taskId,title)).orElseThrow(); long second=repository.insertForOwner(ownerId, leaf(taskId,title)).orElseThrow();
        SubTask later=leaf(taskId,"'); DROP TABLE sub_task; --"); later.setOrderIndex(3); long last=repository.insertForOwner(ownerId, later).orElseThrow();
        long other=tasks.insertForOwner(ownerId, new Task("Khác")).orElseThrow(); repository.insertForOwner(ownerId, leaf(other,"Không trộn")).orElseThrow();
        assertEquals(List.of(first,second,last),repository.findByTaskIdForOwner(ownerId, taskId).stream().map(SubTask::getId).toList());
        assertEquals(title,repository.findByIdForOwner(ownerId, first).orElseThrow().getTitle());
        assertEquals(later.getTitle(),repository.findByIdForOwner(ownerId, last).orElseThrow().getTitle());
        assertThrows(IllegalArgumentException.class,()->repository.insertForOwner(ownerId, new SubTask("Không cha")).orElseThrow());
        assertThrows(IllegalArgumentException.class,()->new SubTask("😀".repeat(256)));
        assertThrows(IllegalArgumentException.class,()->new SubTask("\u2003"));
    }

    @Test void foreignKeyRejectsMissingTaskAndCascadeKeepsOtherTaskAndLeaves() throws Exception {
        assertTrue(repository.insertForOwner(ownerId, leaf(Long.MAX_VALUE, "Cha thiếu")).isEmpty());
        var savepoint=connection.setSavepoint();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO sub_task (task_id, title) VALUES (?, ?)", Long.MAX_VALUE, "Cha thiếu"));
        connection.rollback(savepoint);
        long first=repository.insertForOwner(ownerId, leaf(taskId,"Xóa riêng")).orElseThrow(); long sibling=repository.insertForOwner(ownerId, leaf(taskId,"Giữ lá")).orElseThrow();
        long other=tasks.insertForOwner(ownerId, new Task("Task giữ")).orElseThrow(); long kept=repository.insertForOwner(ownerId, leaf(other,"Giữ khác")).orElseThrow();
        assertTrue(repository.deleteByIdForOwner(ownerId, first)); assertTrue(repository.findByIdForOwner(ownerId, sibling).isPresent());
        assertTrue(tasks.deleteByIdForOwner(ownerId, taskId)); assertTrue(repository.findByIdForOwner(ownerId, sibling).isEmpty());
        assertTrue(repository.findByIdForOwner(ownerId, kept).isPresent()); assertTrue(tasks.findByIdForOwner(ownerId, other).isPresent());
    }

    @Test void realCrudServicesCoverThreeLocationsDateBoundariesMetadataAndCascade() {
        var plans=new PlanRepository(jdbc); var subPlans=new SubPlanRepository(jdbc);
        var taskService=new com.example.planner.service.TaskService(tasks,plans,subPlans);
        var service=new com.example.planner.service.SubTaskService(repository,taskService);
        LocalDate date=LocalDate.of(2026,11,16);
        var planModel=new com.example.planner.model.Plan("Plan fixture"); planModel.setDates(date,date);
        long plan=plans.insertForOwner(ownerId, planModel);
        long sub=subPlans.insertForOwner(ownerId, new com.example.planner.model.SubPlan(null,plan,null,"Sub",null,date,date,0,OffsetDateTime.now())).orElseThrow();
        long direct=taskService.create(ownerId, plan,null,"Direct","",date.minusDays(9),null,0);
        long nested=taskService.create(ownerId, null,sub,"Nested","",null,date.plusDays(9),0);
        assertEquals(date.minusDays(9),taskService.getById(ownerId, direct).getStartDate());
        assertEquals(date.plusDays(9),taskService.getById(ownerId, nested).getEndDate());
        long kept=taskService.create(ownerId, "Giữ độc lập","",null,null,0); long keptLeaf=service.create(ownerId, kept,"Giữ lá","",null,0);
        for (long parent:new long[]{taskId,direct,nested}) {
            for (LocalDate[] range:new LocalDate[][]{{null,null},{date,null},{null,date},{date,date}}) {
                taskService.update(ownerId, parent,"😀".repeat(255),"",range[0],range[1],0);
                Task saved=taskService.getById(ownerId, parent); assertEquals(range[0],saved.getStartDate()); assertEquals(range[1],saved.getEndDate());
            }
            assertThrows(IllegalArgumentException.class,()->taskService.update(ownerId, parent,"Ngày sai","",date,date.minusDays(1),0));
            assertThrows(IllegalArgumentException.class,()->taskService.update(ownerId, parent,"😀".repeat(256),"",null,null,0));
            assertThrows(IllegalArgumentException.class,()->taskService.update(ownerId, parent," ","",null,null,0));
            assertThrows(IllegalArgumentException.class,()->taskService.update(ownerId, parent,"Tên","",null,null,-1));
            assertEquals("😀".repeat(255),taskService.getById(ownerId, parent).getTitle());
            long id=service.create(ownerId, parent,"😀".repeat(255),"",null,0);
            long sibling=service.create(ownerId, parent,"😀".repeat(255),null,date.minusDays(8),1);
            assertEquals(List.of(id,sibling),service.findByTaskId(ownerId, parent).stream().map(SubTask::getId).toList());
            assertEquals(date.minusDays(8),service.getById(ownerId, sibling).getScheduledDate());
            OffsetDateTime completed=OffsetDateTime.parse("2026-11-01T00:00:00Z");
            jdbc.update("UPDATE sub_task SET done=true, completed_at=? WHERE id=?",completed,id);
            var created=service.getById(ownerId, id).getCreatedAt().toInstant();
            service.update(ownerId, id,"😀","",date.plusDays(8),2);
            SubTask updated=service.getById(ownerId, id); assertEquals(parent,updated.getTaskId());
            assertEquals(date.plusDays(8),updated.getScheduledDate()); assertTrue(updated.isDone());
            assertEquals(completed.toInstant(),updated.getCompletedAt().toInstant()); assertEquals(created,updated.getCreatedAt().toInstant());
            for (String invalid:new String[]{"","\u2003","😀".repeat(256)})
                assertThrows(IllegalArgumentException.class,()->service.update(ownerId, id,invalid,"",null,0));
            assertThrows(IllegalArgumentException.class,()->service.update(ownerId, id,"Tên","",null,-1));
            assertEquals("😀",service.getById(ownerId, id).getTitle());
            service.update(ownerId, id,"😀",null,null,0); assertNull(service.getById(ownerId, id).getScheduledDate());
            assertTrue(service.getById(ownerId, id).isDone()); assertEquals(parent,service.delete(ownerId, id));
            assertTrue(repository.findByIdForOwner(ownerId, sibling).isPresent()); assertTrue(tasks.findByIdForOwner(ownerId, parent).isPresent());
            taskService.delete(ownerId, parent); assertTrue(repository.findByIdForOwner(ownerId, sibling).isEmpty());
            assertTrue(repository.findByIdForOwner(ownerId, keptLeaf).isPresent());
        }
        assertTrue(plans.findByIdForOwner(ownerId, plan).isPresent()); assertTrue(subPlans.findByIdForOwner(ownerId, sub).isPresent());
    }
}
