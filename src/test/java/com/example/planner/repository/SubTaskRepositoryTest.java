package com.example.planner.repository;

import com.example.planner.model.SubTask;
import com.example.planner.model.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL", matches=".+")
class SubTaskRepositoryTest {
    @Test void batchLeavesStayScopedAndEmptyIdsAreSafe() {
        long other=tasks.insert(new Task("Other"));
        long first=repository.insert(leaf(taskId,"Leaf A")); long second=repository.insert(leaf(other,"Leaf B"));
        assertEquals(List.of(first),repository.findByTaskIds(List.of(taskId,taskId)).stream().map(SubTask::getId).toList());
        assertEquals(List.of(first,second),repository.findByTaskIds(List.of(taskId,other)).stream().map(SubTask::getId).toList());
        assertTrue(repository.findByTaskIds(List.of()).isEmpty());
        assertTrue(repository.findByTaskIds(List.of(Long.MAX_VALUE)).isEmpty());
    }
    private Connection connection;
    private JdbcTemplate jdbc;
    private SubTaskRepository repository;
    private TaskRepository tasks;
    private long taskId;

    @BeforeEach void setUp() throws Exception {
        String url=System.getenv("TEST_DB_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/planner(?:\\?.*)?")) throw new IllegalArgumentException("Chỉ dùng planner thử");
        String password=System.getenv("TEST_DB_PASSWORD");
        if (password == null) throw new IllegalArgumentException("Cần TEST_DB_PASSWORD");
        connection=DriverManager.getConnection(url,System.getenv().getOrDefault("TEST_DB_USERNAME","postgres"),password);
        connection.setAutoCommit(false);
        ScriptUtils.executeSqlScript(connection,new ClassPathResource("schema.sql"));
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        repository=new SubTaskRepository(jdbc); tasks=new TaskRepository(jdbc);
        taskId=tasks.insert(new Task("Task fixture"));
    }
    @AfterEach void tearDown() throws Exception {
        if (connection != null) try { connection.rollback(); } finally { connection.close(); }
    }
    private SubTask leaf(long parent, String title) { SubTask leaf=new SubTask(title); leaf.setTaskId(parent); return leaf; }

    @Test void crudRoundTripNullDateAndDefaultState() {
        long id=repository.insert(leaf(taskId,"Tên")); assertTrue(id>0);
        SubTask saved=repository.findById(id).orElseThrow();
        assertEquals(taskId,saved.getTaskId()); assertNull(saved.getScheduledDate()); assertNull(saved.getDescription());
        assertFalse(saved.isDone()); assertNull(saved.getCompletedAt()); assertEquals(0,saved.getOrderIndex());
        assertTrue(saved.getChildren().isEmpty()); var created=saved.getCreatedAt().toInstant();
        saved.setTitle("Sửa"); saved.setDescription(""); saved.setScheduledDate(LocalDate.of(2026,11,16)); saved.setOrderIndex(2);
        assertTrue(repository.update(saved)); SubTask updated=repository.findById(id).orElseThrow();
        assertEquals("Sửa",updated.getTitle()); assertEquals("",updated.getDescription());
        assertEquals(LocalDate.of(2026,11,16),updated.getScheduledDate()); assertEquals(2,updated.getOrderIndex());
        assertEquals(created,updated.getCreatedAt().toInstant());
        saved.setScheduledDate(null); assertTrue(repository.update(saved)); assertNull(repository.findById(id).orElseThrow().getScheduledDate());
        assertTrue(repository.deleteById(id)); assertTrue(repository.findById(id).isEmpty());
        assertFalse(repository.update(saved)); assertFalse(repository.deleteById(id));
    }

    @Test void metadataCannotMoveParentOrOverwriteCompletionOrCreationTime() {
        long id=repository.insert(leaf(taskId,"Đã làm"));
        OffsetDateTime completed=OffsetDateTime.parse("2026-11-01T00:00:00Z");
        SubTask completion=new SubTask(id,taskId,"Không ghi tên này",null,null,true,completed,5,completed);
        assertTrue(repository.updateCompletion(completion));
        SubTask saved=repository.findById(id).orElseThrow(); var created=saved.getCreatedAt().toInstant();
        assertEquals("Đã làm",saved.getTitle());
        assertEquals(0,saved.getOrderIndex());
        long other=tasks.insert(new Task("Task khác"));
        saved.setTaskId(other); saved.markUndone(); saved.setTitle("Metadata mới");
        assertTrue(repository.update(saved)); SubTask actual=repository.findById(id).orElseThrow();
        assertEquals(taskId,actual.getTaskId()); assertTrue(actual.isDone());
        assertEquals(completed.toInstant(),actual.getCompletedAt().toInstant()); assertEquals(created,actual.getCreatedAt().toInstant());
        completion.markUndone();
        assertTrue(repository.updateCompletion(completion));
        SubTask undone=repository.findById(id).orElseThrow();
        assertFalse(undone.isDone());
        assertNull(undone.getCompletedAt());
        assertEquals("Metadata mới",undone.getTitle());
        assertTrue(repository.deleteById(id));
        assertFalse(repository.updateCompletion(completion));
        SubTask prepared=leaf(taskId,"Tạo chưa hoàn thành"); prepared.markDone();
        SubTask inserted=repository.findById(repository.insert(prepared)).orElseThrow();
        assertFalse(inserted.isDone()); assertNull(inserted.getCompletedAt());
    }

    @Test void duplicateUnicodeNamesSqlTextAndSortingStayScopedToTask() {
        String title="😀".repeat(255);
        long first=repository.insert(leaf(taskId,title)); long second=repository.insert(leaf(taskId,title));
        SubTask later=leaf(taskId,"'); DROP TABLE sub_task; --"); later.setOrderIndex(3); long last=repository.insert(later);
        long other=tasks.insert(new Task("Khác")); repository.insert(leaf(other,"Không trộn"));
        assertEquals(List.of(first,second,last),repository.findByTaskId(taskId).stream().map(SubTask::getId).toList());
        assertEquals(title,repository.findById(first).orElseThrow().getTitle());
        assertEquals(later.getTitle(),repository.findById(last).orElseThrow().getTitle());
        assertThrows(IllegalArgumentException.class,()->repository.insert(new SubTask("Không cha")));
        assertThrows(IllegalArgumentException.class,()->new SubTask("😀".repeat(256)));
        assertThrows(IllegalArgumentException.class,()->new SubTask("\u2003"));
    }

    @Test void foreignKeyRejectsMissingTaskAndCascadeKeepsOtherTaskAndLeaves() throws Exception {
        var savepoint=connection.setSavepoint();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                ()->repository.insert(leaf(Long.MAX_VALUE,"Cha thiếu")));
        connection.rollback(savepoint);
        long first=repository.insert(leaf(taskId,"Xóa riêng")); long sibling=repository.insert(leaf(taskId,"Giữ lá"));
        long other=tasks.insert(new Task("Task giữ")); long kept=repository.insert(leaf(other,"Giữ khác"));
        assertTrue(repository.deleteById(first)); assertTrue(repository.findById(sibling).isPresent());
        assertTrue(tasks.deleteById(taskId)); assertTrue(repository.findById(sibling).isEmpty());
        assertTrue(repository.findById(kept).isPresent()); assertTrue(tasks.findById(other).isPresent());
    }

    @Test void realCrudServicesCoverThreeLocationsDateBoundariesMetadataAndCascade() {
        var plans=new PlanRepository(jdbc); var subPlans=new SubPlanRepository(jdbc);
        var taskService=new com.example.planner.service.TaskService(tasks,plans,subPlans);
        var service=new com.example.planner.service.SubTaskService(repository,taskService);
        LocalDate date=LocalDate.of(2026,11,16);
        var planModel=new com.example.planner.model.Plan("Plan fixture"); planModel.setDates(date,date);
        long plan=plans.insert(planModel);
        long sub=subPlans.insert(new com.example.planner.model.SubPlan(null,plan,null,"Sub",null,date,date,0,OffsetDateTime.now()));
        long direct=taskService.create(plan,null,"Direct","",date.minusDays(9),null,0);
        long nested=taskService.create(null,sub,"Nested","",null,date.plusDays(9),0);
        assertEquals(date.minusDays(9),taskService.getById(direct).getStartDate());
        assertEquals(date.plusDays(9),taskService.getById(nested).getEndDate());
        long kept=taskService.create("Giữ độc lập","",null,null,0); long keptLeaf=service.create(kept,"Giữ lá","",null,0);
        for (long parent:new long[]{taskId,direct,nested}) {
            for (LocalDate[] range:new LocalDate[][]{{null,null},{date,null},{null,date},{date,date}}) {
                taskService.update(parent,"😀".repeat(255),"",range[0],range[1],0);
                Task saved=taskService.getById(parent); assertEquals(range[0],saved.getStartDate()); assertEquals(range[1],saved.getEndDate());
            }
            assertThrows(IllegalArgumentException.class,()->taskService.update(parent,"Ngày sai","",date,date.minusDays(1),0));
            assertThrows(IllegalArgumentException.class,()->taskService.update(parent,"😀".repeat(256),"",null,null,0));
            assertThrows(IllegalArgumentException.class,()->taskService.update(parent," ","",null,null,0));
            assertThrows(IllegalArgumentException.class,()->taskService.update(parent,"Tên","",null,null,-1));
            assertEquals("😀".repeat(255),taskService.getById(parent).getTitle());
            long id=service.create(parent,"😀".repeat(255),"",null,0);
            long sibling=service.create(parent,"😀".repeat(255),null,date.minusDays(8),1);
            assertEquals(List.of(id,sibling),service.findByTaskId(parent).stream().map(SubTask::getId).toList());
            assertEquals(date.minusDays(8),service.getById(sibling).getScheduledDate());
            OffsetDateTime completed=OffsetDateTime.parse("2026-11-01T00:00:00Z");
            jdbc.update("UPDATE sub_task SET done=true, completed_at=? WHERE id=?",completed,id);
            var created=service.getById(id).getCreatedAt().toInstant();
            service.update(id,"😀","",date.plusDays(8),2);
            SubTask updated=service.getById(id); assertEquals(parent,updated.getTaskId());
            assertEquals(date.plusDays(8),updated.getScheduledDate()); assertTrue(updated.isDone());
            assertEquals(completed.toInstant(),updated.getCompletedAt().toInstant()); assertEquals(created,updated.getCreatedAt().toInstant());
            for (String invalid:new String[]{"","\u2003","😀".repeat(256)})
                assertThrows(IllegalArgumentException.class,()->service.update(id,invalid,"",null,0));
            assertThrows(IllegalArgumentException.class,()->service.update(id,"Tên","",null,-1));
            assertEquals("😀",service.getById(id).getTitle());
            service.update(id,"😀",null,null,0); assertNull(service.getById(id).getScheduledDate());
            assertTrue(service.getById(id).isDone()); assertEquals(parent,service.delete(id));
            assertTrue(repository.findById(sibling).isPresent()); assertTrue(tasks.findById(parent).isPresent());
            taskService.delete(parent); assertTrue(repository.findById(sibling).isEmpty());
            assertTrue(repository.findById(keptLeaf).isPresent());
        }
        assertTrue(plans.findById(plan).isPresent()); assertTrue(subPlans.findById(sub).isPresent());
    }
}
