package com.example.planner.repository;

import com.example.planner.model.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.example.planner.support.AuthTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class TaskRepositoryTest {
    @Test void batchReadsIncludeDirectAndNestedTasksOnlyForSelectedPlans() {
        var plans=new PlanRepository(jdbc); var subs=new SubPlanRepository(jdbc);
        long a=plans.insertForOwner(ownerId, new com.example.planner.model.Plan("Batch A"));
        long b=plans.insertForOwner(ownerId, new com.example.planner.model.Plan("Batch B"));
        long sub=subs.insertForOwner(ownerId, new com.example.planner.model.SubPlan(null,a,null,"Sub",null,null,null,0,java.time.OffsetDateTime.now())).orElseThrow();
        Task direct=new Task("Direct"); direct.setLocation(a,null); long first=repository.insertForOwner(ownerId, direct).orElseThrow();
        Task nested=new Task("Nested"); nested.setLocation(null,sub); long second=repository.insertForOwner(ownerId, nested).orElseThrow();
        Task other=new Task("Other"); other.setLocation(b,null); long third=repository.insertForOwner(ownerId, other).orElseThrow();
        long free=repository.insertForOwner(ownerId, new Task("Free")).orElseThrow();
        assertEquals(List.of(first,second),repository.findByPlanIdsForOwner(ownerId, List.of(a,a)).stream().map(Task::getId).toList());
        assertEquals(List.of(first,second,third),repository.findByPlanIdsForOwner(ownerId, List.of(a,b)).stream().map(Task::getId).toList());
        assertFalse(repository.findByPlanIdsForOwner(ownerId, List.of(a,b)).stream().anyMatch(t->t.getId()==free));
        assertTrue(repository.findByPlanIdsForOwner(ownerId, List.of()).isEmpty());
        assertTrue(repository.findByPlanIdsForOwner(ownerId, List.of(Long.MAX_VALUE)).isEmpty());
    }
    private long ownerId;
    private Connection connection;
    private JdbcTemplate jdbc;
    private TaskRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connection = AuthTestDatabase.openConnection();
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        ownerId = AuthTestDatabase.createFixtureAccount(jdbc);
        repository = new TaskRepository(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) try { connection.rollback(); } finally { connection.close(); }
    }

    @Test
    void crudRoundTripNullsAndCreationTime() {
        Task task = new Task("Task độc lập");
        long id = repository.insertForOwner(ownerId, task).orElseThrow();
        assertTrue(id > 0);
        Task saved = repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertNull(saved.getPlanId()); assertNull(saved.getSubPlanId());
        assertNull(saved.getDescription()); assertNull(saved.getStartDate()); assertNull(saved.getEndDate());
        assertEquals(0, saved.getOrderIndex());
        var created = saved.getCreatedAt().toInstant();
        LocalDate date = LocalDate.of(2026, 11, 16);
        saved.setTitle("Sửa"); saved.setDescription(""); saved.setDates(date, date); saved.setOrderIndex(2);
        assertTrue(repository.lockTaskForOwner(ownerId, id));
        assertTrue(repository.updateForOwner(ownerId, saved));
        Task updated = repository.findByIdForOwner(ownerId, id).orElseThrow();
        assertEquals("Sửa", updated.getTitle()); assertEquals("", updated.getDescription());
        assertEquals(date, updated.getStartDate()); assertEquals(date, updated.getEndDate());
        assertEquals(2, updated.getOrderIndex()); assertEquals(created, updated.getCreatedAt().toInstant());
        saved.setDates(null, date); assertTrue(repository.updateForOwner(ownerId, saved));
        assertNull(repository.findByIdForOwner(ownerId, id).orElseThrow().getStartDate());
        saved.setDates(date, null); assertTrue(repository.updateForOwner(ownerId, saved));
        assertNull(repository.findByIdForOwner(ownerId, id).orElseThrow().getEndDate());
        saved.setDates(null, null); saved.setDescription(null); assertTrue(repository.updateForOwner(ownerId, saved));
        assertNull(repository.findByIdForOwner(ownerId, id).orElseThrow().getDescription());
        assertTrue(repository.deleteByIdForOwner(ownerId, id)); assertTrue(repository.findByIdForOwner(ownerId, id).isEmpty());
        assertFalse(repository.lockTaskForOwner(ownerId, id)); assertFalse(repository.updateForOwner(ownerId, saved)); assertFalse(repository.deleteByIdForOwner(ownerId, id));
    }

    @Test
    void duplicateUnicodeTitlesSqlTextAndOrderArePreserved() {
        String title = "😀".repeat(255);
        long first = repository.insertForOwner(ownerId, new Task(title)).orElseThrow();
        long second = repository.insertForOwner(ownerId, new Task(title)).orElseThrow();
        Task later = new Task("'); DROP TABLE task; --"); later.setOrderIndex(3);
        long third = repository.insertForOwner(ownerId, later).orElseThrow();
        assertEquals(title, repository.findByIdForOwner(ownerId, first).orElseThrow().getTitle());
        assertEquals(later.getTitle(), repository.findByIdForOwner(ownerId, third).orElseThrow().getTitle());
        List<Long> ids = repository.findIndependentForOwner(ownerId).stream().map(Task::getId).toList();
        assertTrue(ids.indexOf(first) < ids.indexOf(second)); assertTrue(ids.indexOf(second) < ids.indexOf(third));
    }

    @Test
    void independentListExcludesTreeTasksAndUpdateNeverChangesLocation() {
        Long plan = jdbc.queryForObject("INSERT INTO plan(owner_user_id,title) VALUES (?,?) RETURNING id", Long.class, ownerId, "Plan thử");
        Long attached = jdbc.queryForObject("INSERT INTO task(plan_id,title) VALUES (?,?) RETURNING id", Long.class, plan, "Thuộc cây");
        assertEquals(plan, repository.findByIdForOwner(ownerId, attached).orElseThrow().getPlanId());
        assertTrue(repository.lockTaskForOwner(ownerId, attached));
        assertFalse(repository.findIndependentForOwner(ownerId).stream().anyMatch(t -> t.getId().equals(attached)));
        Task forged = new Task("Không sửa"); forged.setId(attached);
        assertTrue(repository.updateForOwner(ownerId, forged));
        assertEquals(plan, repository.findByIdForOwner(ownerId, attached).orElseThrow().getPlanId());
        Task independent = new Task("Tự do"); long id = repository.insertForOwner(ownerId, independent).orElseThrow();
        independent.setId(id); independent.setLocation(plan, null);
        assertTrue(repository.updateForOwner(ownerId, independent));
        assertNull(repository.findByIdForOwner(ownerId, id).orElseThrow().getPlanId());
    }

    @Test
    void threeLocationsRoundTripAndDirectQueriesStaySeparate() {
        long plan = new PlanRepository(jdbc).insertForOwner(ownerId, new com.example.planner.model.Plan("Plan"));
        var subPlans = new SubPlanRepository(jdbc);
        long root = subPlans.insertForOwner(ownerId, new com.example.planner.model.SubPlan(null, plan, null, "Root", null, null, null, 0, java.time.OffsetDateTime.now())).orElseThrow();
        long child = subPlans.insertForOwner(ownerId, new com.example.planner.model.SubPlan(null, plan, root, "Child", null, null, null, 0, java.time.OffsetDateTime.now())).orElseThrow();
        long free = repository.insertForOwner(ownerId, new Task("Free")).orElseThrow();
        Task direct = new Task("Direct"); direct.setLocation(plan, null); direct.setOrderIndex(2);
        long directId = repository.insertForOwner(ownerId, direct).orElseThrow();
        Task first = new Task("First"); first.setLocation(plan, null); long firstId = repository.insertForOwner(ownerId, first).orElseThrow();
        Task underRoot = new Task("Root Task"); underRoot.setLocation(null, root); long rootId = repository.insertForOwner(ownerId, underRoot).orElseThrow();
        Task underChild = new Task("Child Task"); underChild.setLocation(null, child); long childId = repository.insertForOwner(ownerId, underChild).orElseThrow();
        assertEquals(List.of(firstId, directId), repository.findByPlanIdForOwner(ownerId, plan).stream().map(Task::getId).toList());
        assertEquals(List.of(rootId), repository.findBySubPlanIdForOwner(ownerId, root).stream().map(Task::getId).toList());
        assertEquals(List.of(childId), repository.findBySubPlanIdForOwner(ownerId, child).stream().map(Task::getId).toList());
        assertTrue(repository.findIndependentForOwner(ownerId).stream().anyMatch(t -> t.getId() == free));
        Task saved = repository.findByIdForOwner(ownerId, rootId).orElseThrow();
        assertNull(saved.getPlanId()); assertEquals(root, saved.getSubPlanId());
        var created = saved.getCreatedAt().toInstant();
        saved.setLocation(plan, null); saved.setTitle("Sửa"); assertTrue(repository.updateForOwner(ownerId, saved));
        Task unchanged = repository.findByIdForOwner(ownerId, rootId).orElseThrow();
        assertNull(unchanged.getPlanId()); assertEquals(root, unchanged.getSubPlanId());
        assertEquals(created, unchanged.getCreatedAt().toInstant());
        assertThrows(IllegalArgumentException.class, () -> new Task( null, plan, root, "Sai", null, null, null, 0, java.time.OffsetDateTime.now()));
        Long leaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id", Long.class, childId, "Lá");
        assertTrue(subPlans.deleteByIdForOwner(ownerId, child));
        assertTrue(repository.findByIdForOwner(ownerId, childId).isEmpty());
        assertTrue(repository.findByIdForOwner(ownerId, rootId).isPresent()); assertTrue(repository.findByIdForOwner(ownerId, directId).isPresent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id=?", Integer.class, leaf));
        assertTrue(new PlanRepository(jdbc).deleteByIdForOwner(ownerId, plan));
        assertTrue(repository.findByIdForOwner(ownerId, rootId).isEmpty()); assertTrue(repository.findByIdForOwner(ownerId, directId).isEmpty());
        assertTrue(repository.findByIdForOwner(ownerId, free).isPresent());
    }

    @Test
    void deleteCascadesOnlyItsOwnLeaves() {
        long id = repository.insertForOwner(ownerId, new Task("Xóa")).orElseThrow(); long other = repository.insertForOwner(ownerId, new Task("Giữ")).orElseThrow();
        Long leaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id", Long.class, id, "Lá");
        Long kept = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id", Long.class, other, "Giữ lá");
        assertTrue(repository.deleteByIdForOwner(ownerId, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id=?", Integer.class, leaf));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id=?", Integer.class, kept));
        assertTrue(repository.findByIdForOwner(ownerId, other).isPresent());
    }

    @Test
    void servicePersistsAllLocationsAndInvalidEditLeavesDatabaseUnchanged() {
        var plans = new PlanRepository(jdbc); var subPlans = new SubPlanRepository(jdbc);
        var service = new com.example.planner.service.TaskService(repository, plans, subPlans);
        long plan = plans.insertForOwner(ownerId, new com.example.planner.model.Plan("Plan"));
        long sub = subPlans.insertForOwner(ownerId, new com.example.planner.model.SubPlan(null, plan, null, "Sub", null,
                LocalDate.of(2026,11,16), LocalDate.of(2026,11,16), 0, java.time.OffsetDateTime.now())).orElseThrow();
        long free = service.create(ownerId, "Free", "", null, null, 0);
        long direct = service.create(ownerId, plan, null, "Direct", "", null, null, 0);
        long nested = service.create(ownerId, null, sub, "Nested", "", LocalDate.of(2026,1,1), null, 0);
        assertEquals(List.of(direct), service.findByPlanId(ownerId, plan).stream().map(Task::getId).toList());
        assertEquals(List.of(nested), service.findBySubPlanId(ownerId, sub).stream().map(Task::getId).toList());
        var created = service.getById(ownerId, nested).getCreatedAt().toInstant();
        service.update(ownerId, nested, "Mới", "", null, LocalDate.of(2027,1,1), 1);
        assertEquals(sub, service.getById(ownerId, nested).getSubPlanId());
        assertEquals(created, service.getById(ownerId, nested).getCreatedAt().toInstant());
        assertThrows(IllegalArgumentException.class, () -> service.update(ownerId, nested, "Không lưu", "", LocalDate.of(2027,1,1), LocalDate.of(2026,1,1), 0));
        assertEquals("Mới", service.getById(ownerId, nested).getTitle());
        service.delete(ownerId, direct); service.delete(ownerId, nested);
        assertTrue(repository.findByIdForOwner(ownerId, direct).isEmpty()); assertTrue(repository.findByIdForOwner(ownerId, nested).isEmpty());
        assertTrue(repository.findByIdForOwner(ownerId, free).isPresent()); assertTrue(plans.findByIdForOwner(ownerId, plan).isPresent());
        assertTrue(subPlans.findByIdForOwner(ownerId, sub).isPresent());
    }
}
