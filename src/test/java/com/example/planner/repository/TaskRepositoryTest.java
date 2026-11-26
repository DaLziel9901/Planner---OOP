package com.example.planner.repository;

import com.example.planner.model.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class TaskRepositoryTest {
    @Test void batchReadsIncludeDirectAndNestedTasksOnlyForSelectedPlans() {
        var plans=new PlanRepository(jdbc); var subs=new SubPlanRepository(jdbc);
        long a=plans.insert(new com.example.planner.model.Plan("Batch A"));
        long b=plans.insert(new com.example.planner.model.Plan("Batch B"));
        long sub=subs.insert(new com.example.planner.model.SubPlan(null,a,null,"Sub",null,null,null,0,java.time.OffsetDateTime.now()));
        Task direct=new Task("Direct"); direct.setLocation(a,null); long first=repository.insert(direct);
        Task nested=new Task("Nested"); nested.setLocation(null,sub); long second=repository.insert(nested);
        Task other=new Task("Other"); other.setLocation(b,null); long third=repository.insert(other);
        long free=repository.insert(new Task("Free"));
        assertEquals(List.of(first,second),repository.findByPlanIds(List.of(a,a)).stream().map(Task::getId).toList());
        assertEquals(List.of(first,second,third),repository.findByPlanIds(List.of(a,b)).stream().map(Task::getId).toList());
        assertFalse(repository.findByPlanIds(List.of(a,b)).stream().anyMatch(t->t.getId()==free));
        assertTrue(repository.findByPlanIds(List.of()).isEmpty());
        assertTrue(repository.findByPlanIds(List.of(Long.MAX_VALUE)).isEmpty());
    }
    private Connection connection;
    private JdbcTemplate jdbc;
    private TaskRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TEST_DB_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/planner(?:\\?.*)?"))
            throw new IllegalArgumentException("Chỉ dùng database planner chứa dữ liệu thử");
        String password = System.getenv("TEST_DB_PASSWORD");
        if (password == null) throw new IllegalArgumentException("Cần TEST_DB_PASSWORD");
        connection = DriverManager.getConnection(url, System.getenv().getOrDefault("TEST_DB_USERNAME", "postgres"), password);
        connection.setAutoCommit(false);
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        repository = new TaskRepository(jdbc);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) try { connection.rollback(); } finally { connection.close(); }
    }

    @Test
    void crudRoundTripNullsAndCreationTime() {
        Task task = new Task("Task độc lập");
        long id = repository.insert(task);
        assertTrue(id > 0);
        Task saved = repository.findById(id).orElseThrow();
        assertNull(saved.getPlanId()); assertNull(saved.getSubPlanId());
        assertNull(saved.getDescription()); assertNull(saved.getStartDate()); assertNull(saved.getEndDate());
        assertEquals(0, saved.getOrderIndex());
        var created = saved.getCreatedAt().toInstant();
        LocalDate date = LocalDate.of(2026, 11, 16);
        saved.setTitle("Sửa"); saved.setDescription(""); saved.setDates(date, date); saved.setOrderIndex(2);
        assertTrue(repository.lockTask(id));
        assertTrue(repository.update(saved));
        Task updated = repository.findById(id).orElseThrow();
        assertEquals("Sửa", updated.getTitle()); assertEquals("", updated.getDescription());
        assertEquals(date, updated.getStartDate()); assertEquals(date, updated.getEndDate());
        assertEquals(2, updated.getOrderIndex()); assertEquals(created, updated.getCreatedAt().toInstant());
        saved.setDates(null, date); assertTrue(repository.update(saved));
        assertNull(repository.findById(id).orElseThrow().getStartDate());
        saved.setDates(date, null); assertTrue(repository.update(saved));
        assertNull(repository.findById(id).orElseThrow().getEndDate());
        saved.setDates(null, null); saved.setDescription(null); assertTrue(repository.update(saved));
        assertNull(repository.findById(id).orElseThrow().getDescription());
        assertTrue(repository.deleteById(id)); assertTrue(repository.findById(id).isEmpty());
        assertFalse(repository.lockTask(id)); assertFalse(repository.update(saved)); assertFalse(repository.deleteById(id));
    }

    @Test
    void duplicateUnicodeTitlesSqlTextAndOrderArePreserved() {
        String title = "😀".repeat(255);
        long first = repository.insert(new Task(title));
        long second = repository.insert(new Task(title));
        Task later = new Task("'); DROP TABLE task; --"); later.setOrderIndex(3);
        long third = repository.insert(later);
        assertEquals(title, repository.findById(first).orElseThrow().getTitle());
        assertEquals(later.getTitle(), repository.findById(third).orElseThrow().getTitle());
        List<Long> ids = repository.findIndependent().stream().map(Task::getId).toList();
        assertTrue(ids.indexOf(first) < ids.indexOf(second)); assertTrue(ids.indexOf(second) < ids.indexOf(third));
    }

    @Test
    void independentListExcludesTreeTasksAndUpdateNeverChangesLocation() {
        Long plan = jdbc.queryForObject("INSERT INTO plan(title) VALUES (?) RETURNING id", Long.class, "Plan thử");
        Long attached = jdbc.queryForObject("INSERT INTO task(plan_id,title) VALUES (?,?) RETURNING id", Long.class, plan, "Thuộc cây");
        assertEquals(plan, repository.findById(attached).orElseThrow().getPlanId());
        assertTrue(repository.lockTask(attached));
        assertFalse(repository.findIndependent().stream().anyMatch(t -> t.getId().equals(attached)));
        Task forged = new Task("Không sửa"); forged.setId(attached);
        assertTrue(repository.update(forged));
        assertEquals(plan, repository.findById(attached).orElseThrow().getPlanId());
        Task independent = new Task("Tự do"); long id = repository.insert(independent);
        independent.setId(id); independent.setLocation(plan, null);
        assertTrue(repository.update(independent));
        assertNull(repository.findById(id).orElseThrow().getPlanId());
    }

    @Test
    void threeLocationsRoundTripAndDirectQueriesStaySeparate() {
        long plan = new PlanRepository(jdbc).insert(new com.example.planner.model.Plan("Plan"));
        var subPlans = new SubPlanRepository(jdbc);
        long root = subPlans.insert(new com.example.planner.model.SubPlan(null, plan, null, "Root", null, null, null, 0, java.time.OffsetDateTime.now()));
        long child = subPlans.insert(new com.example.planner.model.SubPlan(null, plan, root, "Child", null, null, null, 0, java.time.OffsetDateTime.now()));
        long free = repository.insert(new Task("Free"));
        Task direct = new Task("Direct"); direct.setLocation(plan, null); direct.setOrderIndex(2);
        long directId = repository.insert(direct);
        Task first = new Task("First"); first.setLocation(plan, null); long firstId = repository.insert(first);
        Task underRoot = new Task("Root Task"); underRoot.setLocation(null, root); long rootId = repository.insert(underRoot);
        Task underChild = new Task("Child Task"); underChild.setLocation(null, child); long childId = repository.insert(underChild);
        assertEquals(List.of(firstId, directId), repository.findByPlanId(plan).stream().map(Task::getId).toList());
        assertEquals(List.of(rootId), repository.findBySubPlanId(root).stream().map(Task::getId).toList());
        assertEquals(List.of(childId), repository.findBySubPlanId(child).stream().map(Task::getId).toList());
        assertTrue(repository.findIndependent().stream().anyMatch(t -> t.getId() == free));
        Task saved = repository.findById(rootId).orElseThrow();
        assertNull(saved.getPlanId()); assertEquals(root, saved.getSubPlanId());
        var created = saved.getCreatedAt().toInstant();
        saved.setLocation(plan, null); saved.setTitle("Sửa"); assertTrue(repository.update(saved));
        Task unchanged = repository.findById(rootId).orElseThrow();
        assertNull(unchanged.getPlanId()); assertEquals(root, unchanged.getSubPlanId());
        assertEquals(created, unchanged.getCreatedAt().toInstant());
        assertThrows(IllegalArgumentException.class, () -> new Task( null, plan, root, "Sai", null, null, null, 0, java.time.OffsetDateTime.now()));
        Long leaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id", Long.class, childId, "Lá");
        assertTrue(subPlans.deleteById(child));
        assertTrue(repository.findById(childId).isEmpty());
        assertTrue(repository.findById(rootId).isPresent()); assertTrue(repository.findById(directId).isPresent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id=?", Integer.class, leaf));
        assertTrue(new PlanRepository(jdbc).deleteById(plan));
        assertTrue(repository.findById(rootId).isEmpty()); assertTrue(repository.findById(directId).isEmpty());
        assertTrue(repository.findById(free).isPresent());
    }

    @Test
    void deleteCascadesOnlyItsOwnLeaves() {
        long id = repository.insert(new Task("Xóa")); long other = repository.insert(new Task("Giữ"));
        Long leaf = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id", Long.class, id, "Lá");
        Long kept = jdbc.queryForObject("INSERT INTO sub_task(task_id,title) VALUES (?,?) RETURNING id", Long.class, other, "Giữ lá");
        assertTrue(repository.deleteById(id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id=?", Integer.class, leaf));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id=?", Integer.class, kept));
        assertTrue(repository.findById(other).isPresent());
    }

    @Test
    void servicePersistsAllLocationsAndInvalidEditLeavesDatabaseUnchanged() {
        var plans = new PlanRepository(jdbc); var subPlans = new SubPlanRepository(jdbc);
        var service = new com.example.planner.service.TaskService(repository, plans, subPlans);
        long plan = plans.insert(new com.example.planner.model.Plan("Plan"));
        long sub = subPlans.insert(new com.example.planner.model.SubPlan(null, plan, null, "Sub", null,
                LocalDate.of(2026,11,16), LocalDate.of(2026,11,16), 0, java.time.OffsetDateTime.now()));
        long free = service.create("Free", "", null, null, 0);
        long direct = service.create(plan, null, "Direct", "", null, null, 0);
        long nested = service.create(null, sub, "Nested", "", LocalDate.of(2026,1,1), null, 0);
        assertEquals(List.of(direct), service.findByPlanId(plan).stream().map(Task::getId).toList());
        assertEquals(List.of(nested), service.findBySubPlanId(sub).stream().map(Task::getId).toList());
        var created = service.getById(nested).getCreatedAt().toInstant();
        service.update(nested, "Mới", "", null, LocalDate.of(2027,1,1), 1);
        assertEquals(sub, service.getById(nested).getSubPlanId());
        assertEquals(created, service.getById(nested).getCreatedAt().toInstant());
        assertThrows(IllegalArgumentException.class, () -> service.update(nested, "Không lưu", "", LocalDate.of(2027,1,1), LocalDate.of(2026,1,1), 0));
        assertEquals("Mới", service.getById(nested).getTitle());
        service.delete(direct); service.delete(nested);
        assertTrue(repository.findById(direct).isEmpty()); assertTrue(repository.findById(nested).isEmpty());
        assertTrue(repository.findById(free).isPresent()); assertTrue(plans.findById(plan).isPresent());
        assertTrue(subPlans.findById(sub).isPresent());
    }
}
