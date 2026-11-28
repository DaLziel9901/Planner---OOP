package com.example.planner.controller;

import com.example.planner.model.SubTask;
import com.example.planner.model.Task;
import com.example.planner.service.SubTaskService;
import com.example.planner.service.TaskService;
import com.example.planner.service.PlanTreeService;
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

@SpringJUnitWebConfig(SubTaskViewTest.Config.class)
class SubTaskViewTest extends AuthenticatedControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired SubTaskService service;
    @Autowired TaskService tasks;
    @Autowired PlanTreeService trees;
    private MockMvc mvc;
    @TestConfiguration @EnableWebMvc
    static class Config {
        @Bean SubTaskService subTaskService() { return mock(SubTaskService.class); }
        @Bean TaskService taskService() { return mock(TaskService.class); }
        @Bean PlanTreeService planTreeService() { return mock(PlanTreeService.class); }
        @Bean SubTaskController controller(SubTaskService service,TaskService tasks,PlanTreeService trees) { return new SubTaskController(service,tasks,trees); }
        @Bean ThymeleafViewResolver viewResolver() {
            var templates=new ClassLoaderTemplateResolver(); templates.setPrefix("templates/"); templates.setSuffix(".html");
            templates.setTemplateMode("HTML"); templates.setCharacterEncoding("UTF-8");
            var engine=new SpringTemplateEngine(); engine.setTemplateResolver(templates);
            var views=new ThymeleafViewResolver(); views.setTemplateEngine(engine); views.setCharacterEncoding("UTF-8"); return views;
        }
    }
    @BeforeEach void setUp() {
        reset(service,tasks,trees); mvc=MockMvcBuilders.webAppContextSetup(context).build();
        Task task=new Task("Cha"); task.setId(7L); when(tasks.getById(OWNER_ID, 7)).thenReturn(task);
        SubTask leaf=new SubTask(9L,7L,"Lá <script>x</script>","<b>Mô tả</b>",null,false,null,0,OffsetDateTime.now());
        when(service.getById(OWNER_ID, 9)).thenReturn(leaf); when(service.findByTaskId(OWNER_ID, 7)).thenReturn(List.of(leaf));
        task.addSubTask(leaf); when(trees.loadTask(OWNER_ID, 7)).thenReturn(new PlanTreeService.TaskSnapshot(task,null,null));
    }
    @Test void listEscapesAndShowsScopedLinksEmptyStateAndMissingTask() throws Exception {
        mvc.perform(get("/subtasks").param("taskId","7")).andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("href=\"/subtasks/9\"")))
                .andExpect(content().string(containsString("method=\"post\" action=\"/subtasks/9/completion\"")))
                .andExpect(content().string(containsString("name=\"source\" value=\"subtasks\"")))
                .andExpect(content().string(containsString("href=\"/subtasks/new?taskId=7\"")));
        when(service.findByTaskId(OWNER_ID, 7)).thenReturn(List.of());
        Task empty=new Task("Cha"); empty.setId(7L); when(trees.loadTask(OWNER_ID, 7)).thenReturn(new PlanTreeService.TaskSnapshot(empty,null,null));
        mvc.perform(get("/subtasks").param("taskId","7")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa có Sub Task")));
        mvc.perform(get("/subtasks")).andExpect(status().isOk()).andExpect(content().string(containsString("Chọn một Task")));
    }
    @Test void formsShowOptionalDateAndOnlyCreationHasTaskControl() throws Exception {
        mvc.perform(get("/subtasks/new").param("taskId","7")).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/subtasks\"")))
                .andExpect(content().string(containsString("name=\"taskId\"")))
                .andExpect(content().string(containsString("name=\"scheduledDate\"")));
        mvc.perform(get("/subtasks/9/edit")).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/subtasks/9/edit\"")))
                .andExpect(content().string(containsString("&lt;b&gt;Mô tả&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("name=\"taskId\""))))
                .andExpect(content().string(not(containsString("name=\"done\""))))
                .andExpect(content().string(not(containsString("name=\"completedAt\""))));
        mvc.perform(get("/subtasks/new")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chọn một Task")));
    }
    @Test void errorsKeepAndEscapeEveryRawInput() throws Exception {
        mvc.perform(post("/subtasks").param("taskId","bad").param("title","<img>")
                        .param("description","<script>x</script>").param("scheduledDate","abc").param("orderIndex","oops"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("value=\"bad\"")))
                .andExpect(content().string(containsString("value=\"abc\"")))
                .andExpect(content().string(containsString("value=\"oops\"")))
                .andExpect(content().string(containsString("&lt;img&gt;")))
                .andExpect(content().string(containsString("&lt;script&gt;x&lt;/script&gt;")));
        verify(service,never()).create(eq(OWNER_ID), anyLong(),any(),any(),any(),anyInt());
    }
    @Test void detailHasTaskLinkStatusAndOnlySingleLeafDeleteConfirmation() throws Exception {
        mvc.perform(get("/subtasks/9").flashAttr("message","Đã lưu"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Đã lưu")))
                .andExpect(content().string(containsString("href=\"/tasks/7\"")))
                .andExpect(content().string(containsString("action=\"/subtasks/9/delete\"")))
                .andExpect(content().string(containsString("confirm(")))
                .andExpect(content().string(containsString("Chưa hoàn thành")))
                .andExpect(content().string(not(containsString("href=\"/subtasks/new"))))
                .andExpect(content().string(containsString("method=\"post\" action=\"/subtasks/9/completion\"")))
                .andExpect(content().string(containsString("name=\"done\" value=\"true\"")));
    }

    @Test void completedLeafOffersUndoEvenWhenProgressIsUnavailable() throws Exception {
        SubTask leaf = service.getById(OWNER_ID, 9);
        leaf.markDone();
        when(trees.loadTask(OWNER_ID, 7)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB"));
        mvc.perform(get("/subtasks/9")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa có tiến độ khả dụng")))
                .andExpect(content().string(containsString("name=\"done\" value=\"false\"")))
                .andExpect(content().string(containsString("Bỏ đánh dấu hoàn thành")));
    }
    @Test void createEditFlashRenderAfterPrg() throws Exception {
        when(service.create(OWNER_ID, 7,"Tên","",null,0)).thenReturn(9L);
        var created=mvc.perform(post("/subtasks").param("taskId","7").param("title","Tên"))
                .andExpect(redirectedUrl("/subtasks/9")).andReturn();
        mvc.perform(get("/subtasks/9").flashAttrs(created.getFlashMap())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã tạo Sub Task")));
        var edited=mvc.perform(post("/subtasks/9/edit").param("title","Mới"))
                .andExpect(redirectedUrl("/subtasks/9")).andReturn();
        mvc.perform(get("/subtasks/9").flashAttrs(edited.getFlashMap())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã cập nhật Sub Task")));
    }
}
