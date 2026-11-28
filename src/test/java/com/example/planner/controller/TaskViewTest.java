package com.example.planner.controller;

import com.example.planner.model.Task;
import com.example.planner.service.TaskService;
import com.example.planner.service.PlanService;
import com.example.planner.service.SubPlanService;
import com.example.planner.service.PlanTreeService;
import com.example.planner.model.SubTask;
import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import java.time.OffsetDateTime;
import java.util.List;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(TaskViewTest.Config.class)
class TaskViewTest extends AuthenticatedControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired TaskService service;
    @Autowired PlanService plans;
    @Autowired SubPlanService subPlans;
    @Autowired PlanTreeService trees;
    private MockMvc mvc;
    private Task task;

    @TestConfiguration @EnableWebMvc
    static class Config {
        @Bean TaskService taskService() { return mock(TaskService.class); }
        @Bean PlanService planService() { return mock(PlanService.class); }
        @Bean SubPlanService subPlanService() { return mock(SubPlanService.class); }
        @Bean PlanTreeService planTreeService() { return mock(PlanTreeService.class); }
        @Bean TaskController controller(TaskService service, PlanService plans, SubPlanService subPlans, PlanTreeService trees) { return new TaskController(service, plans, subPlans, trees); }
        @Bean ThymeleafViewResolver viewResolver() {
            var templates = new ClassLoaderTemplateResolver(); templates.setPrefix("templates/");
            templates.setSuffix(".html"); templates.setTemplateMode("HTML"); templates.setCharacterEncoding("UTF-8");
            var engine = new SpringTemplateEngine(); engine.setTemplateResolver(templates);
            var views = new ThymeleafViewResolver(); views.setTemplateEngine(engine); views.setCharacterEncoding("UTF-8");
            return views;
        }
    }

    @BeforeEach void setUp() {
        reset(service, plans, subPlans, trees); mvc = MockMvcBuilders.webAppContextSetup(context).build();
        when(trees.loadTask(eq(OWNER_ID), anyLong())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Tree unavailable"));
        when(trees.loadTasks(eq(OWNER_ID), any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Tree unavailable"));
        task = new Task(7L, null, null, "Task <script>x</script>", "<b>Nội dung</b>", null, null, 0,
                OffsetDateTime.parse("2026-11-16T00:00:00Z"));
        when(service.getById(OWNER_ID, 7)).thenReturn(task); when(service.findIndependent(OWNER_ID)).thenReturn(List.of(task));
    }

    @Test void createChoosesParentAndInvalidSelectionRemainsVisible() throws Exception {
        Plan plan = new Plan(1L,"Plan cha",null,null,null,OffsetDateTime.now());
        SubPlan sub = new SubPlan(2L,1L,null,"Sub cha",null,null,null,0,OffsetDateTime.now());
        when(plans.findAll(OWNER_ID)).thenReturn(List.of(plan)); when(plans.getById(OWNER_ID, 1)).thenReturn(plan);
        when(subPlans.findByPlanId(OWNER_ID, 1)).thenReturn(List.of(sub)); when(subPlans.getById(OWNER_ID, 2)).thenReturn(sub);
        mvc.perform(get("/tasks/new").param("subPlanId","2")).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"planId\"")))
                .andExpect(content().string(containsString("name=\"subPlanId\"")))
                .andExpect(content().string(containsString("value=\"2\" selected=\"selected\"")));
        mvc.perform(post("/tasks").param("title","Tên").param("planId","<bad>"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("&lt;bad&gt;")))
                .andExpect(content().string(containsString("Giá trị đã gửi")));
    }

    @Test void attachedTaskDetailLinksToActualParentAndEditHasNoLocationControls() throws Exception {
        task.setLocation(1L,null); when(plans.getById(OWNER_ID, 1)).thenReturn(new Plan("Plan cha"));
        mvc.perform(get("/tasks/7")).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/plans/1\"")))
                .andExpect(content().string(containsString("/tasks?planId=1")))
                .andExpect(content().string(not(containsString("0%"))));
        mvc.perform(get("/tasks/7/edit")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"planId\""))))
                .andExpect(content().string(not(containsString("name=\"subPlanId\""))));
    }

    @Test void listShowsLinksEscapesAndEmptyState() throws Exception {
        mvc.perform(get("/tasks")).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/tasks/new\"")))
                .andExpect(content().string(containsString("href=\"/tasks/7\"")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>"))));
        when(service.findIndependent(OWNER_ID)).thenReturn(List.of());
        mvc.perform(get("/tasks")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa có Task độc lập")));
    }

    @Test void formsRenderContentDatesAndOrderWithoutLocationFields() throws Exception {
        mvc.perform(get("/tasks/new")).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/tasks\"")))
                .andExpect(content().string(containsString("name=\"orderIndex\"")));
        mvc.perform(get("/tasks/7/edit")).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/tasks/7/edit\"")))
                .andExpect(content().string(containsString("&lt;b&gt;Nội dung&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("name=\"planId\""))))
                .andExpect(content().string(not(containsString("name=\"subPlanId\""))))
                .andExpect(content().string(not(containsString("name=\"createdAt\""))));
    }

    @Test void invalidInputStaysVisibleAndEscaped() throws Exception {
        mvc.perform(post("/tasks").param("title", "<img src=x>").param("description", "<script>bad</script>")
                        .param("startDate", "not-a-date").param("orderIndex", "oops"))
                .andExpect(status().isOk()).andExpect(view().name("tasks/form"))
                .andExpect(content().string(containsString("value=\"not-a-date\"")))
                .andExpect(content().string(containsString("value=\"oops\"")))
                .andExpect(content().string(containsString("&lt;img src=x&gt;")))
                .andExpect(content().string(containsString("&lt;script&gt;bad&lt;/script&gt;")))
                .andExpect(content().string(containsString("role=\"alert\"")));
        verify(service, never()).create(eq(OWNER_ID), any(),any(),any(),any(),anyInt());
    }

    @Test void detailShowsFlashConfirmationAndUnavailableProgress() throws Exception {
        mvc.perform(get("/tasks/7").flashAttr("message", "Đã tạo Task"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Đã tạo Task")))
                .andExpect(content().string(containsString("&lt;b&gt;Nội dung&lt;/b&gt;")))
                .andExpect(content().string(containsString("action=\"/tasks/7/delete\"")))
                .andExpect(content().string(containsString("method=\"post\"")))
                .andExpect(content().string(containsString("confirm(")))
                .andExpect(content().string(containsString("Chưa có tiến độ khả dụng")))
                .andExpect(content().string(not(containsString("0%"))))
                .andExpect(content().string(containsString("href=\"/subtasks/new?taskId=7\"")));
    }

    @Test void createAndEditFlashRenderAtRedirectDestination() throws Exception {
        when(service.create(OWNER_ID, "Tên", "", null, null, 0)).thenReturn(7L);
        var created = mvc.perform(post("/tasks").param("title", "Tên"))
                .andExpect(redirectedUrl("/tasks/7")).andReturn();
        mvc.perform(get("/tasks/7").flashAttrs(created.getFlashMap())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã tạo Task")));
        var edited = mvc.perform(post("/tasks/7/edit").param("title", "Mới"))
                .andExpect(redirectedUrl("/tasks/7")).andReturn();
        mvc.perform(get("/tasks/7").flashAttrs(edited.getFlashMap())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã cập nhật Task")));
    }

    @Test void taskDetailListsEscapedLeavesWithLoadedProgress() throws Exception {
        var leaf=new SubTask(9L,7L,"Lá <script>x</script>",null,null,false,null,0,OffsetDateTime.now());
        Task loaded=new Task(7L,null,null,task.getTitle(),task.getDescription(),null,null,0,task.getCreatedAt()); loaded.addSubTask(leaf);
        doReturn(new PlanTreeService.TaskSnapshot(loaded,null,null)).when(trees).loadTask(OWNER_ID, 7);
        mvc.perform(get("/tasks/7")).andExpect(status().isOk())
                .andExpect(model().attribute("subTasks",List.of(leaf)))
                .andExpect(content().string(containsString("href=\"/subtasks/9\"")))
                .andExpect(content().string(containsString("href=\"/subtasks?taskId=7\"")))
                .andExpect(content().string(containsString("method=\"post\" action=\"/subtasks/9/completion\"")))
                .andExpect(content().string(containsString("name=\"source\" value=\"task\"")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("0/1 Sub Task — 0%")))
                .andExpect(model().attribute("progressAvailable",true));
    }
}
