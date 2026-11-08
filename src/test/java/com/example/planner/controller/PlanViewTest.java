package com.example.planner.controller;

import com.example.planner.model.Plan;
import com.example.planner.service.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Render Thymeleaf thật với service giả, không tải Boot hoặc kết nối DB.
@SpringJUnitWebConfig(PlanViewTest.Config.class)
class PlanViewTest {
    @Autowired WebApplicationContext context;
    @Autowired PlanService service;
    private MockMvc mvc;
    private Plan plan;

    @TestConfiguration
    @EnableWebMvc
    static class Config {
        @Bean PlanService planService() { return mock(PlanService.class); }
        @Bean PlanController controller(PlanService service) { return new PlanController(service); }
        @Bean ThymeleafViewResolver viewResolver() {
            ClassLoaderTemplateResolver templates = new ClassLoaderTemplateResolver();
            templates.setPrefix("templates/");
            templates.setSuffix(".html");
            templates.setTemplateMode("HTML");
            templates.setCharacterEncoding("UTF-8");
            SpringTemplateEngine engine = new SpringTemplateEngine();
            engine.setTemplateResolver(templates);
            ThymeleafViewResolver resolver = new ThymeleafViewResolver();
            resolver.setTemplateEngine(engine);
            resolver.setCharacterEncoding("UTF-8");
            return resolver;
        }
    }

    @BeforeEach
    void setUp() {
        reset(service);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        plan = new Plan(1L, "TOEIC", null, null, null, OffsetDateTime.parse("2026-11-01T00:00:00Z"));
        when(service.getById(1L)).thenReturn(plan);
        when(service.findAll()).thenReturn(List.of(plan));
    }

    @Test
    void listRendersEmptyStateAndLinksToPlansAndSubPlans() throws Exception {
        mvc.perform(get("/plans")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/plans/1")))
                .andExpect(content().string(containsString("/subplans?planId=1")))
                .andExpect(content().string(containsString("TOEIC")));
        when(service.findAll()).thenReturn(List.of());
        mvc.perform(get("/plans")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa có kế hoạch")));
    }

    @Test
    void createAndEditFormsRenderCurrentDataIncludingNullDates() throws Exception {
        mvc.perform(get("/plans/new")).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/plans\"")))
                .andExpect(content().string(containsString("Thêm Plan")));
        mvc.perform(get("/plans/1/edit")).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/plans/1/edit\"")))
                .andExpect(content().string(containsString("value=\"TOEIC\"")));
        plan.setDates(LocalDate.of(2026, 11, 8), LocalDate.of(2027, 5, 8));
        plan.setGoal("730+");
        mvc.perform(get("/plans/1/edit")).andExpect(status().isOk())
                .andExpect(content().string(containsString("2026-11-08")))
                .andExpect(content().string(containsString("730+")));
    }

    @Test
    void invalidCreateRendersErrorsAndRetainsEscapedInput() throws Exception {
        mvc.perform(post("/plans").param("title", " ")
                        .param("goal", "<script>alert(1)</script>").param("startDate", "abc"))
                .andExpect(status().isOk()).andExpect(view().name("plans/form"))
                .andExpect(content().string(containsString("Tên kế hoạch không được để trống")))
                .andExpect(content().string(containsString("value=\"abc\"")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>"))));
        verify(service, never()).create(any(), any(), any(), any());
    }

    @Test
    void invalidUpdateRetainsInputAndCorrectAction() throws Exception {
        mvc.perform(post("/plans/1/edit").param("title", "Tên mới")
                        .param("startDate", "2026-11-09").param("endDate", "2026-11-08"))
                .andExpect(status().isOk()).andExpect(view().name("plans/form"))
                .andExpect(content().string(containsString("action=\"/plans/1/edit\"")))
                .andExpect(content().string(containsString("Ngày kết thúc không được trước ngày bắt đầu")))
                .andExpect(content().string(containsString("value=\"Tên mới\"")));
        verify(service, never()).update(anyLong(), any(), any(), any(), any());
    }

    @Test
    void detailEscapesTextShowsWarningAndPostDeleteConfirmation() throws Exception {
        plan.setTitle("<script>alert(1)</script>");
        mvc.perform(get("/plans/1")).andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(content().string(containsString("Chưa nạp toàn bộ SubPlan/Task/SubTask")))
                .andExpect(content().string(containsString("method=\"post\"")))
                .andExpect(content().string(containsString("action=\"/plans/1/delete\"")))
                .andExpect(content().string(containsString("confirm(")))
                .andExpect(content().string(containsString("/subplans/new?planId=1")));
    }

    @Test
    void successfulCreateRedirectsAndDetailDisplaysFlashMessage() throws Exception {
        when(service.create("TOEIC", "", null, null)).thenReturn(1L);
        mvc.perform(post("/plans").param("title", "TOEIC"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/plans/1"))
                .andExpect(flash().attribute("message", "Đã tạo kế hoạch"));
        mvc.perform(get("/plans/1").flashAttr("message", "Đã tạo kế hoạch"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Đã tạo kế hoạch")));
    }

    @Test
    void missingIdIs404InsteadOfAttemptingToRenderDetail() throws Exception {
        when(service.getById(99L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/plans/99")).andExpect(status().isNotFound());
    }
}
