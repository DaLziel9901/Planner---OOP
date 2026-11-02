package com.example.planner.controller;

import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import com.example.planner.service.PlanService;
import com.example.planner.service.SubPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
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

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Không chạy Boot/DB. Render template thật bằng Thymeleaf để bắt lỗi HTML/expression.
@SpringJUnitWebConfig(SubPlanControllerTest.Config.class)
class SubPlanControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired SubPlanService service;
    @Autowired PlanService plans;
    private MockMvc mvc;
    private SubPlan current;

    @TestConfiguration
    @EnableWebMvc
    static class Config {
        @Bean SubPlanService subPlanService() { return mock(SubPlanService.class); }
        @Bean PlanService planService() { return mock(PlanService.class); }
        @Bean SubPlanController controller(SubPlanService service, PlanService plans) {
            return new SubPlanController(service, plans);
        }
        @Bean ThymeleafViewResolver viewResolver() {
            ClassLoaderTemplateResolver templates = new ClassLoaderTemplateResolver();
            templates.setPrefix("templates/");
            templates.setSuffix(".html");
            templates.setTemplateMode("HTML");
            templates.setCharacterEncoding("UTF-8");
            SpringTemplateEngine engine = new SpringTemplateEngine();
            engine.setTemplateResolver(templates);
            ThymeleafViewResolver views = new ThymeleafViewResolver();
            views.setTemplateEngine(engine);
            views.setCharacterEncoding("UTF-8");
            return views;
        }
    }

    @BeforeEach
    void setUp() {
        reset(service, plans);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Plan plan = new Plan(1L, "TOEIC", null, null, null, OffsetDateTime.now());
        current = new SubPlan(2L, 1L, null, "Giai đoạn", null, null, null, 0, OffsetDateTime.now());
        when(plans.findAll()).thenReturn(List.of(plan));
        when(service.getPlan(1L)).thenReturn(plan);
        when(service.findByPlanId(1L)).thenReturn(List.of(current));
        when(service.getById(2L)).thenReturn(current);
        when(service.getDirectChildren(current)).thenReturn(List.of());
        when(service.getDateWarnings(current)).thenReturn(List.of("Cảnh báo ngày"));
    }

    @Test
    void rendersListWithAndWithoutSelectedPlan() throws Exception {
        mvc.perform(get("/subplans")).andExpect(status().isOk()).andExpect(content().string(containsString("TOEIC")));
        mvc.perform(get("/subplans").param("planId", "1"))
                .andExpect(status().isOk()).andExpect(view().name("subplans/list"))
                .andExpect(content().string(containsString("Giai đoạn")));
    }

    @Test
    void rendersCreateEditDetailAndEscapesUserText() throws Exception {
        mvc.perform(get("/subplans/new").param("planId", "1").param("parentSubPlanId", "2"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Thêm SubPlan")));
        mvc.perform(get("/subplans/2/edit")).andExpect(status().isOk())
                .andExpect(model().attribute("editing", true));
        current.setDescription("<script>alert(1)</script>");
        mvc.perform(get("/subplans/2")).andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("Cảnh báo ngày")))
                .andExpect(content().string(containsString("confirm(")))
                .andExpect(model().attribute("progressAvailable", false));
    }

    @Test
    void createsAndRedirects() throws Exception {
        when(service.create(1L, null, "Tên", "", null, null, 0)).thenReturn(3L);
        mvc.perform(post("/subplans").param("planId", "1").param("title", "Tên"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/subplans/3"));
    }

    @Test
    void syntaxErrorsKeepInputWithoutSaving() throws Exception {
        mvc.perform(post("/subplans").param("planId", "1").param("title", "Tên")
                        .param("startDate", "abc").param("parentSubPlanId", "bad").param("orderIndex", "bad"))
                .andExpect(status().isOk()).andExpect(view().name("subplans/form"))
                .andExpect(content().string(containsString("value=\"abc\"")))
                .andExpect(content().string(containsString("Giá trị đã gửi: bad")));
        verify(service, never()).create(anyLong(), any(), anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    void businessErrorsAppearOnFormAndCanBeCorrected() throws Exception {
        when(service.create(1L, 2L, "Tên", "", null, null, 0))
                .thenThrow(new SubPlanService.InputException("parentSubPlanId", "Vượt 5 tầng"));
        mvc.perform(post("/subplans").param("planId", "1").param("parentSubPlanId", "2").param("title", "Tên"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Vượt 5 tầng")));
    }

    @Test
    void updateKeepsOriginalPlanDespiteForgedFormPlanId() throws Exception {
        mvc.perform(post("/subplans/2/edit").param("planId", "999").param("title", "Mới"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/subplans/2"));
        verify(service).update(2L, null, "Mới", "", null, null, 0);
    }

    @Test
    void missingIdsReturn404AndGetCannotDelete() throws Exception {
        when(service.getById(99L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/subplans/99")).andExpect(status().isNotFound());
        mvc.perform(post("/subplans/99/edit")).andExpect(status().isNotFound());
        mvc.perform(get("/subplans/2/delete")).andExpect(status().isMethodNotAllowed());
        verify(service, never()).delete(anyLong());
        when(service.delete(2L)).thenReturn(1L);
        mvc.perform(post("/subplans/2/delete")).andExpect(redirectedUrl("/subplans?planId=1"));
    }
}
