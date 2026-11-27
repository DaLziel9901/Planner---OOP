package com.example.planner.controller;

import com.example.planner.service.TaskService;
import com.example.planner.service.PlanService;
import com.example.planner.service.SubPlanService;
import com.example.planner.service.SubTaskService;
import com.example.planner.support.AuthTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

// HTTP thật qua cổng ngẫu nhiên + Spring service/proxy + PostgreSQL. Không chạy JavaScript.
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskWebSmokeTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        AuthTestDatabase.registerApplicationProperties(properties);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskService service;
    @Autowired PlanService plans;
    @Autowired SubPlanService subPlans;
    @Autowired SubTaskService subTasks;
    @Autowired PlatformTransactionManager transactions;

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private HttpResponse<String> request(HttpClient client, String path, Map<String,String> form) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        if (form == null) builder.GET();
        else {
            String body = form.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).reduce((a,b) -> a + "&" + b).orElse("");
            builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void independentCrudWorksThroughRealHttpAndDatabase() throws Exception {
        assertEquals("planner_auth_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(System.getenv("TEST_DB_URL"), System.getenv("DB_URL"));
        assertTrue(AopUtils.isAopProxy(service));
        int plansBefore = jdbc.queryForObject("SELECT count(*) FROM plan", Integer.class);
        int subPlansBefore = jdbc.queryForObject("SELECT count(*) FROM sub_plan", Integer.class);
        String marker = "Task HTTP " + UUID.randomUUID();
        var client = client();
        Long id = null;
        try {
            assertEquals(200, request(client, "/tasks", null).statusCode());
            assertEquals(200, request(client, "/tasks/new", null).statusCode());
            var created = request(client, "/tasks", Map.of("title",marker,"description","<script>hello</script>"));
            // Tra lại ID theo marker duy nhất ngay sau POST để cleanup cả khi assertion phía sau lỗi.
            id = jdbc.queryForObject("SELECT id FROM task WHERE title=? AND plan_id IS NULL AND sub_plan_id IS NULL", Long.class, marker);
            assertEquals(302, created.statusCode());
            String detailPath = "/tasks/" + id;
            assertEquals(detailPath, redirectPath(created));
            var detail = request(client, detailPath, null);
            assertEquals(200, detail.statusCode()); assertTrue(detail.body().contains("Đã tạo Task"));
            assertTrue(detail.body().contains("&lt;script&gt;hello&lt;/script&gt;"));
            assertTrue(detail.body().contains("Chưa có công việc")); assertFalse(detail.body().contains("0%"));
            assertTrue(detail.body().contains("href=\"/subtasks/new?taskId=" + id + "\""));
            OffsetDateTime createdAt = jdbc.queryForObject("SELECT created_at FROM task WHERE id=?", OffsetDateTime.class, id);
            assertEquals(200, request(client, detailPath + "/edit", null).statusCode());
            var invalid = request(client, detailPath + "/edit", Map.of("title",marker,"startDate","bad","orderIndex","-1"));
            assertEquals(200, invalid.statusCode()); assertTrue(invalid.body().contains("value=\"bad\""));
            assertEquals(marker, jdbc.queryForObject("SELECT title FROM task WHERE id=?", String.class, id));
            var edited = request(client, detailPath + "/edit", Map.of("title",marker + " sửa","startDate","2026-11-16",
                    "endDate","2026-11-16","orderIndex","3","id","999","subPlanId","999","createdAt","fake"));
            assertEquals(302, edited.statusCode());
            var updated = request(client, detailPath, null);
            assertEquals(200, updated.statusCode()); assertTrue(updated.body().contains(marker + " sửa"));
            assertTrue(updated.body().contains("Đã cập nhật Task"));
            var row = jdbc.queryForMap("SELECT plan_id, sub_plan_id, order_index, created_at FROM task WHERE id=?", id);
            assertNull(row.get("plan_id")); assertNull(row.get("sub_plan_id")); assertEquals(3, row.get("order_index"));
            assertEquals(createdAt.toInstant(), jdbc.queryForObject("SELECT created_at FROM task WHERE id=?", OffsetDateTime.class, id).toInstant());
            assertEquals(405, request(client, detailPath + "/delete", null).statusCode());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM task WHERE id=?", Integer.class, id));
            var deleted = request(client, detailPath + "/delete", Map.of());
            assertEquals(302, deleted.statusCode());
            assertEquals("/tasks", redirectPath(deleted));
            var list = request(client, "/tasks", null); assertEquals(200, list.statusCode());
            assertTrue(list.body().contains("Đã xóa Task")); assertFalse(list.body().contains(marker));
            assertEquals(404, request(client, detailPath, null).statusCode());
            assertEquals(plansBefore, jdbc.queryForObject("SELECT count(*) FROM plan", Integer.class));
            assertEquals(subPlansBefore, jdbc.queryForObject("SELECT count(*) FROM sub_plan", Integer.class));
        } finally {
            if (id != null) jdbc.update("DELETE FROM task WHERE id=? AND plan_id IS NULL AND sub_plan_id IS NULL", id);
        }
    }

    private String redirectPath(HttpResponse<String> response) {
        // Tomcat có thể thêm URL session tracking trước khi client gửi lại cookie đầu tiên.
        URI target = URI.create(response.headers().firstValue("location").orElseThrow());
        return target.getPath().replaceFirst(";jsessionid=[^/]*", "")
                + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
    }

    @Test
    void parentLinksScopedListsAndImmutableLocationWorkThroughRealHttp() throws Exception {
        String marker = "Task parents " + UUID.randomUUID();
        long plan = plans.create(marker, "", null, null);
        Long free = null;
        try {
            long sub = subPlans.create(plan, null, "Sub " + marker, "", null, null, 0);
            free = service.create(marker + " Free", "", null, null, 0);
            var client = client();
            String planList = "/tasks?planId=" + plan;
            String subList = "/tasks?subPlanId=" + sub;
            var planPage = request(client, "/plans/" + plan, null);
            assertEquals(200, planPage.statusCode());
            assertTrue(planPage.body().contains("href=\"" + planList + "\""));
            assertTrue(planPage.body().contains("href=\"/tasks/new?planId=" + plan + "\""));
            var subPage = request(client, "/subplans/" + sub, null);
            assertEquals(200, subPage.statusCode()); assertTrue(subPage.body().contains("href=\"" + subList + "\""));
            assertTrue(subPage.body().contains("href=\"/tasks/new?subPlanId=" + sub + "\""));
            assertEquals(200, request(client, "/tasks/new?planId=" + plan, null).statusCode());
            assertEquals(200, request(client, "/tasks/new?subPlanId=" + sub, null).statusCode());
            var invalid = request(client, "/tasks", Map.of("title",marker + " Invalid","planId",Long.toString(plan),"subPlanId",Long.toString(sub)));
            assertEquals(200, invalid.statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task WHERE title=?", Integer.class, marker + " Invalid"));
            var created = request(client, "/tasks", Map.of("title",marker + " Direct","planId",Long.toString(plan)));
            assertEquals(302, created.statusCode());
            String directPath = redirectPath(created);
            long direct = Long.parseLong(directPath.substring("/tasks/".length()));
            var nestedCreated = request(client, "/tasks", Map.of("title",marker + " Nested","subPlanId",Long.toString(sub)));
            assertEquals(302, nestedCreated.statusCode());
            String nestedPath = redirectPath(nestedCreated);
            long nested = Long.parseLong(nestedPath.substring("/tasks/".length()));
            String planHtml = request(client, planList, null).body();
            String subHtml = request(client, subList, null).body();
            String freeHtml = request(client, "/tasks", null).body();
            assertTrue(planHtml.contains(marker + " Direct")); assertFalse(planHtml.contains(marker + " Nested"));
            assertTrue(subHtml.contains(marker + " Nested")); assertFalse(subHtml.contains(marker + " Direct"));
            assertTrue(freeHtml.contains(marker + " Free")); assertFalse(freeHtml.contains(marker + " Direct"));
            assertFalse(freeHtml.contains(marker + " Nested"));
            var time = service.getById(nested).getCreatedAt().toInstant();
            var editPage = request(client, nestedPath + "/edit", null);
            assertEquals(200, editPage.statusCode()); assertFalse(editPage.body().contains("name=\"planId\""));
            assertFalse(editPage.body().contains("name=\"subPlanId\""));
            assertEquals(302, request(client, nestedPath + "/edit", Map.of("title",marker + " Edited",
                    "planId",Long.toString(plan),"subPlanId","999999999","createdAt","fake")).statusCode());
            var saved = service.getById(nested); assertNull(saved.getPlanId()); assertEquals(sub,saved.getSubPlanId());
            assertEquals(time,saved.getCreatedAt().toInstant());
            assertEquals(subList, redirectPath(request(client, nestedPath + "/delete", Map.of("planId",Long.toString(plan)))));
            assertEquals(planList, redirectPath(request(client, directPath + "/delete", Map.of())));
            assertEquals(404, request(client, nestedPath, null).statusCode());
            assertEquals(404, request(client, directPath, null).statusCode());
            long cascade = service.create(null, sub, marker + " Cascade", "", null, null, 0);
            long directCascade = service.create(plan, null, marker + " Direct cascade", "", null, null, 0);
            assertEquals(302, request(client, "/plans/" + plan + "/delete", Map.of()).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task WHERE id IN (?,?)", Integer.class, cascade, directCascade));
            assertEquals(free, service.getById(free).getId());
        } finally {
            if (free != null) jdbc.update("DELETE FROM task WHERE id=?", free);
            jdbc.update("DELETE FROM plan WHERE id=?", plan); // Chỉ fixture do chính test tạo.
        }
    }

    @Test
    void realServiceTransactionRollsBackWithoutPartialData() {
        String marker = "Task rollback " + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            long id = service.create(marker, "", null, null, 0);
            service.update(id, marker, "Nội dung", null, null, 1);
            throw new IllegalStateException("Controlled rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task WHERE title=?", Integer.class, marker));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            long plan = plans.create(marker, "", null, null);
            long sub = subPlans.create(plan, null, "Sub", "", null, null, 0);
            long id = service.create(null, sub, marker, "", null, null, 0);
            service.update(id, marker, "Không commit", null, null, 1);
            service.create(plan, null, marker, "", null, null, 0);
            throw new IllegalStateException("Controlled rollback for tree Tasks");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM task WHERE title=?", Integer.class, marker));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM plan WHERE title=?", Integer.class, marker));
    }

    @Test
    void subTaskCrudAndCascadeWorkOverHttpForAllTaskLocations() throws Exception {
        String marker="SubTask HTTP " + UUID.randomUUID();
        long plan=plans.create(marker,"",null,null);
        List<Long> freeFixtures=new ArrayList<>();
        try {
            long sub=subPlans.create(plan,null,"Sub","",null,null,0);
            long free=service.create("Free","",null,null,0); freeFixtures.add(free);
            long direct=service.create(plan,null,"Direct","",null,null,0);
            long nested=service.create(null,sub,"Nested","",null,null,0);
            var client=client();
            assertEquals(200,request(client,"/subtasks/new",null).statusCode());
            var missing=request(client,"/subtasks",Map.of("title",marker));
            assertEquals(200,missing.statusCode()); assertTrue(missing.body().contains("Chọn một Task"));
            var absent=request(client,"/subtasks",Map.of("title",marker + " missing","taskId",Long.toString(Long.MAX_VALUE)));
            assertEquals(200,absent.statusCode()); assertTrue(absent.body().contains(marker + " missing"));
            assertTrue(absent.body().contains(Long.toString(Long.MAX_VALUE)));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE title=?",Integer.class,marker + " missing"));
            for (long parent:new long[]{free,direct,nested}) {
                String title=marker + " " + parent;
                var page=request(client,"/tasks/" + parent,null);
                assertEquals(200,page.statusCode()); assertTrue(page.body().contains("href=\"/subtasks/new?taskId=" + parent + "\""));
                assertEquals(200,request(client,"/subtasks/new?taskId=" + parent,null).statusCode());
                var created=request(client,"/subtasks",Map.of("taskId",Long.toString(parent),"title",title,
                        "description","<script>leaf</script>","done","true","completedAt","fake","createdAt","fake"));
                assertEquals(302,created.statusCode()); String path=redirectPath(created);
                long id=Long.parseLong(path.substring("/subtasks/".length()));
                var saved=subTasks.getById(id); assertEquals(parent,saved.getTaskId()); assertNull(saved.getScheduledDate());
                assertFalse(saved.isDone()); assertNull(saved.getCompletedAt()); var createdAt=saved.getCreatedAt().toInstant();
                var detail=request(client,path,null); assertEquals(200,detail.statusCode());
                assertTrue(detail.body().contains("Đã tạo SubTask")); assertTrue(detail.body().contains("&lt;script&gt;leaf&lt;/script&gt;"));
                assertTrue(detail.body().contains("href=\"/tasks/" + parent + "\""));
                assertTrue(detail.body().contains(path + "/completion"));
                assertTrue(request(client,"/tasks/" + parent,null).body().contains(title));
                assertTrue(request(client,"/subtasks?taskId=" + parent,null).body().contains(title));
                var editForm=request(client,path + "/edit",null); assertEquals(200,editForm.statusCode());
                assertFalse(editForm.body().contains("name=\"taskId\""));
                assertFalse(editForm.body().contains("/completion"));
                var invalid=request(client,path + "/edit",Map.of("title",title,"scheduledDate","abc","orderIndex","-1"));
                assertEquals(200,invalid.statusCode()); assertTrue(invalid.body().contains("value=\"abc\""));
                assertEquals(title,subTasks.getById(id).getTitle());
                OffsetDateTime completed=OffsetDateTime.parse("2026-11-01T00:00:00Z");
                jdbc.update("UPDATE sub_task SET done=true, completed_at=? WHERE id=?",completed,id);
                var edited=request(client,path + "/edit",Map.of("title",title + " edit","taskId","999999999",
                        "scheduledDate","2027-01-01","orderIndex","2","done","false","completedAt","fake","createdAt","fake"));
                assertEquals(302,edited.statusCode()); assertEquals(path,redirectPath(edited));
                var after=request(client,path,null); assertEquals(200,after.statusCode()); assertTrue(after.body().contains("Đã cập nhật SubTask"));
                saved=subTasks.getById(id); assertEquals(parent,saved.getTaskId()); assertTrue(saved.isDone());
                assertEquals(completed.toInstant(),saved.getCompletedAt().toInstant()); assertEquals(createdAt,saved.getCreatedAt().toInstant());
                assertEquals(java.time.LocalDate.of(2027,1,1),saved.getScheduledDate());
                var taskBefore=service.getById(parent);
                assertEquals(302,request(client,"/tasks/" + parent + "/edit",Map.of("title","Task metadata edit",
                        "planId","999999999","subPlanId","999999999","createdAt","fake")).statusCode());
                var taskAfter=service.getById(parent);
                assertEquals(taskBefore.getPlanId(),taskAfter.getPlanId()); assertEquals(taskBefore.getSubPlanId(),taskAfter.getSubPlanId());
                assertEquals(taskBefore.getCreatedAt().toInstant(),taskAfter.getCreatedAt().toInstant());
                assertEquals(parent,subTasks.getById(id).getTaskId()); assertTrue(subTasks.getById(id).isDone());
                assertEquals(302,request(client,path + "/edit",Map.of("title",title,"scheduledDate","")).statusCode());
                assertNull(subTasks.getById(id).getScheduledDate()); assertTrue(subTasks.getById(id).isDone());
                long sibling=subTasks.create(parent,"Sibling","",null,0);
                assertEquals(405,request(client,path + "/delete",null).statusCode());
                assertEquals("/tasks/" + parent,redirectPath(request(client,path + "/delete",Map.of("taskId","999999999"))));
                assertEquals(404,request(client,path,null).statusCode()); assertNotNull(subTasks.getById(sibling));
                assertEquals(302,request(client,"/tasks/" + parent + "/delete",Map.of()).statusCode());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id=?",Integer.class,sibling));
            }
            long keeper=service.create("Keep","",null,null,0); freeFixtures.add(keeper);
            long keptLeaf=subTasks.create(keeper,"Keep leaf","",null,0);
            long subTaskParent=service.create(null,sub,"Sub cascade","",null,null,0);
            long subLeaf=subTasks.create(subTaskParent,"Sub leaf","",null,0);
            assertEquals(302,request(client,"/subplans/" + sub + "/delete",Map.of()).statusCode());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id=?",Integer.class,subLeaf));
            long planParent=service.create(plan,null,"Plan cascade","",null,null,0);
            long planLeaf=subTasks.create(planParent,"Plan leaf","",null,0);
            assertEquals(302,request(client,"/plans/" + plan + "/delete",Map.of()).statusCode());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE id=?",Integer.class,planLeaf));
            assertNotNull(subTasks.getById(keptLeaf));
        } finally {
            for (long id:freeFixtures) jdbc.update("DELETE FROM task WHERE id=?",id);
            jdbc.update("DELETE FROM plan WHERE id=?",plan);
        }
    }

    @Test
    void subTaskWritesUseRealMandatoryTaskGateAndRollbackChangesAndDeletes() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->service.lockForWrite(Long.MAX_VALUE));
        String marker="SubTask rollback " + UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            long plan=plans.create(marker,"",null,null);
            long sub=subPlans.create(plan,null,"Sub","",null,null,0);
            long free=service.create(marker,"",null,null,0);
            long direct=service.create(plan,null,marker,"",null,null,0);
            long nested=service.create(null,sub,marker,"",null,null,0);
            for (long parent:new long[]{free,direct,nested}) {
                long id=subTasks.create(parent,marker,"",null,0);
                subTasks.update(id,marker,"Không commit",null,1);
            }
            throw new IllegalStateException("Controlled rollback for all locations");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM plan WHERE title=?",Integer.class,marker));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task WHERE title=?",Integer.class,marker));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sub_task WHERE title=?",Integer.class,marker));
        long parent=service.create(marker,"",null,null,0);
        try {
            long id=subTasks.create(parent,marker,"",null,0);
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
                subTasks.update(id,"Không lưu","",null,0); subTasks.delete(id);
                subTasks.create(parent,"Thêm chưa commit","",null,0);
                throw new IllegalStateException("Controlled rollback for update/delete");
            }));
            assertEquals(marker,subTasks.getById(id).getTitle()); assertEquals(1,subTasks.findByTaskId(parent).size());
        } finally { jdbc.update("DELETE FROM task WHERE id=?",parent); }
    }

    @Test
    void independentLeafCompletionPersistsAcrossHttpRedirectsAndMetadataEdit() throws Exception {
        long parent = service.create("Completion smoke " + UUID.randomUUID(), "", null, null, 0);
        try {
            long leaf = subTasks.create(parent, "Lá kiểm thử", "", null, 0);
            String taskPage = "/tasks/" + parent;
            String listPage = "/subtasks?taskId=" + parent;
            String detail = "/subtasks/" + leaf;
            String endpoint = detail + "/completion";
            var http = client();
            assertTrue(request(http, taskPage, null).body().contains("0/1 SubTask"));

            var tick = request(http, endpoint, Map.of("done", "true", "source", "task",
                    "sourceId", Long.toString(parent), "completedAt", "2001-01-01T00:00:00Z"));
            assertEquals(302, tick.statusCode());
            assertEquals(taskPage, redirectPath(tick));
            String afterTick = request(http, redirectPath(tick), null).body();
            assertTrue(afterTick.contains("Đã đánh dấu hoàn thành SubTask"));
            assertTrue(afterTick.contains("1/1 SubTask"));
            assertTrue(afterTick.contains("Bỏ đánh dấu hoàn thành"));
            var saved = subTasks.getById(leaf);
            assertTrue(saved.isDone());
            OffsetDateTime first = saved.getCompletedAt();
            assertNotNull(first);
            assertNotEquals(OffsetDateTime.parse("2001-01-01T00:00:00Z"), first);

            var repeated = request(http, endpoint, Map.of("done", "true"));
            assertEquals(302, repeated.statusCode());
            assertEquals(detail, redirectPath(repeated));
            assertEquals(first, subTasks.getById(leaf).getCompletedAt());
            assertTrue(request(http, redirectPath(repeated), null).body().contains("Bỏ đánh dấu hoàn thành"));
            var edit = request(http, detail + "/edit", Map.of("title", "Metadata mới", "done", "false"));
            assertEquals(302, edit.statusCode());
            var edited = subTasks.getById(leaf);
            assertEquals("Metadata mới", edited.getTitle());
            assertTrue(edited.isDone());
            assertEquals(first, edited.getCompletedAt());

            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                subTasks.setCompletion(leaf, false);
                assertNull(subTasks.getById(leaf).getCompletedAt());
                throw new IllegalStateException("Controlled completion rollback");
            }));
            assertEquals(first, subTasks.getById(leaf).getCompletedAt());

            var undo = request(http, endpoint, Map.of("done", "false", "source", "subtasks",
                    "sourceId", Long.toString(parent)));
            assertEquals(302, undo.statusCode());
            assertEquals(listPage, redirectPath(undo));
            String afterUndo = request(http, redirectPath(undo), null).body();
            assertTrue(afterUndo.contains("Đã bỏ đánh dấu hoàn thành SubTask"));
            assertTrue(afterUndo.contains("Chưa hoàn thành"));
            assertTrue(afterUndo.contains("0/1 SubTask"));
            var undone = subTasks.getById(leaf);
            assertFalse(undone.isDone());
            assertNull(undone.getCompletedAt());
        } finally {
            jdbc.update("DELETE FROM task WHERE id=? AND plan_id IS NULL AND sub_plan_id IS NULL", parent);
        }
    }

    @Test
    void loadedProgressAcrossPagesUsesLeafCountsAndRefreshesAfterCrud() throws Exception {
        String marker="Progress HTTP " + UUID.randomUUID();
        long plan=plans.create(marker,"",null,null); Long free=null;
        try {
            long first=subPlans.create(plan,null,"Depth 1","",null,null,0), deep=first;
            for(int depth=2;depth<=5;depth++) deep=subPlans.create(plan,deep,"Depth " + depth,"",null,null,0);
            long direct=service.create(plan,null,"Direct","",null,null,0);
            long nested=service.create(null,deep,"Nested","",null,null,0);
            free=service.create("Independent excluded","",null,null,0);
            long done=subTasks.create(direct,"Done","",null,0);
            long freeDone=subTasks.create(free,"Independent done","",null,0);
            jdbc.update("UPDATE sub_task SET done=true, completed_at=CURRENT_TIMESTAMP WHERE id IN (?,?)",done,freeDone);
            List<Long> leafIds=new ArrayList<>(); for(int i=0;i<3;i++) leafIds.add(subTasks.create(nested,"Leaf " + i,"",null,0));
            var client=client();
            assertTrue(request(client,"/plans/" + plan,null).body().contains("1/4 SubTask — 25%"));
            assertTrue(request(client,"/plans",null).body().contains("1/4 SubTask — 25%"));
            assertTrue(request(client,"/subplans/" + first,null).body().contains("0/3 SubTask — 0%"));
            assertTrue(request(client,"/subplans?planId=" + plan,null).body().contains("0/3 SubTask — 0%"));
            assertTrue(request(client,"/tasks?planId=" + plan,null).body().contains("1/1 SubTask — 100%"));
            assertTrue(request(client,"/tasks?subPlanId=" + deep,null).body().contains("0/3 SubTask — 0%"));
            String firstLeaf = "/subtasks/" + leafIds.get(0);
            var completed = request(client, firstLeaf + "/completion", Map.of("done", "true"));
            assertEquals(302, completed.statusCode());
            assertEquals(firstLeaf, redirectPath(completed));
            String detail = request(client, redirectPath(completed), null).body();
            assertTrue(detail.contains("Bỏ đánh dấu hoàn thành"));
            assertTrue(detail.contains("1/3 SubTask — 33,33%"));
            assertTrue(request(client,"/tasks/" + nested,null).body().contains("1/3 SubTask — 33,33%"));
            assertTrue(request(client,"/subtasks?taskId=" + nested,null).body().contains("1/3 SubTask — 33,33%"));
            var second = request(client, "/subtasks/" + leafIds.get(1) + "/completion",
                    Map.of("done", "true", "source", "subplan", "sourceId", Long.toString(first)));
            assertEquals(302, second.statusCode());
            assertEquals("/subplans/" + first, redirectPath(second));
            assertTrue(request(client, redirectPath(second), null).body().contains("2/3 SubTask — 66,67%"));
            assertTrue(request(client,"/tasks/" + nested,null).body().contains("2/3 SubTask — 66,67%"));
            assertTrue(request(client,"/plans/" + plan,null).body().contains("3/4 SubTask — 75%"));
            for(long id:leafIds) subTasks.delete(id);
            String emptyTask=request(client,"/tasks/" + nested,null).body();
            assertTrue(emptyTask.contains("Chưa có công việc")); assertFalse(emptyTask.contains("0%"));
            subTasks.delete(done);
            String emptyPlan=request(client,"/plans/" + plan,null).body();
            assertTrue(emptyPlan.contains("Chưa có công việc")); assertFalse(emptyPlan.contains("0%"));
            var created=request(client,"/subtasks",Map.of("taskId",Long.toString(nested),"title","New leaf after empty"));
            assertEquals(302,created.statusCode());
            assertTrue(request(client,redirectPath(created),null).body().contains("0/1 SubTask — 0%"));
            assertTrue(request(client,"/plans/" + plan,null).body().contains("0/1 SubTask — 0%"));
        } finally {
            if(free!=null) jdbc.update("DELETE FROM task WHERE id=?",free);
            jdbc.update("DELETE FROM plan WHERE id=?",plan);
        }
    }
}
