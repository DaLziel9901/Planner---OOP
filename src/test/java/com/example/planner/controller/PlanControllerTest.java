package com.example.planner.controller;

import com.example.planner.model.Plan;
import com.example.planner.service.PlanService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Mô phỏng HTTP; service giả. Không render Thymeleaf hoặc chạy PostgreSQL.
class PlanControllerTest {
    private PlanService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PlanService.class);
        // Chỉ chuyển tiếp tới đường dẫn giả để kiểm tra tên view/model.
        InternalResourceViewResolver views = new InternalResourceViewResolver();
        views.setPrefix("/test-views/");
        views.setSuffix(".html");
        mvc = MockMvcBuilders.standaloneSetup(new PlanController(service))
                .setViewResolvers(views).build();
    }

    @Test
    void listAndDetailExposeDataWithoutClaimingProgressIsLoaded() throws Exception {
        Plan plan = new Plan("TOEIC");
        when(service.findAll()).thenReturn(List.of(plan));
        when(service.getById(1L)).thenReturn(plan);
        mvc.perform(get("/plans")).andExpect(status().isOk())
                .andExpect(view().name("plans/list"))
                .andExpect(model().attribute("plans", List.of(plan)))
                .andExpect(model().attribute("progressAvailable", false));
        mvc.perform(get("/plans/1")).andExpect(status().isOk())
                .andExpect(view().name("plans/detail"))
                .andExpect(model().attribute("plan", plan))
                .andExpect(model().attribute("progressAvailable", false));
    }

    @Test
    void newAndEditFormsHaveCorrectValuesAndActions() throws Exception {
        mvc.perform(get("/plans/new")).andExpect(status().isOk())
                .andExpect(view().name("plans/form"))
                .andExpect(model().attribute("formAction", "/plans"))
                .andExpect(model().attribute("editing", false))
                .andExpect(model().attribute("form", Map.of("title", "", "goal", "", "startDate", "", "endDate", "")));
        Plan plan = new Plan("TOEIC");
        plan.setDates(LocalDate.of(2026, 11, 1), null);
        when(service.getById(1L)).thenReturn(plan);
        mvc.perform(get("/plans/1/edit")).andExpect(status().isOk())
                .andExpect(view().name("plans/form"))
                .andExpect(model().attribute("editing", true))
                .andExpect(model().attribute("formAction", "/plans/1/edit"))
                .andExpect(model().attribute("form", Map.of("title", "TOEIC", "goal", "", "startDate", "2026-11-01", "endDate", "")));
    }

    @Test
    void createRedirectsToGeneratedIdAndIgnoresForgedFields() throws Exception {
        when(service.create("TOEIC", "", null, null)).thenReturn(12L);
        mvc.perform(post("/plans").param("title", "TOEIC")
                        .param("id", "999").param("createdAt", "fake"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/plans/12"))
                .andExpect(flash().attribute("message", "Đã tạo kế hoạch"));
        verify(service).create("TOEIC", "", null, null);
    }

    @Test
    void invalidTitleAndDatesKeepInputAndNeverCallCreate() throws Exception {
        var result = mvc.perform(post("/plans").param("title", " ")
                        .param("goal", "Giữ lại").param("startDate", "abc"))
                .andExpect(status().isOk()).andExpect(view().name("plans/form"))
                .andReturn();
        Map<?, ?> errors = (Map<?, ?>) result.getModelAndView().getModel().get("errors");
        Map<?, ?> form = (Map<?, ?>) result.getModelAndView().getModel().get("form");
        assertTrue(errors.containsKey("title"));
        assertTrue(errors.containsKey("startDate"));
        assertEquals("abc", form.get("startDate"));
        assertEquals("Giữ lại", form.get("goal"));
        mvc.perform(post("/plans").param("title", "a".repeat(256)))
                .andExpect(view().name("plans/form"));
        var range = mvc.perform(post("/plans").param("title", "TOEIC")
                        .param("startDate", "2026-11-02").param("endDate", "2026-11-01"))
                .andExpect(view().name("plans/form")).andReturn();
        assertTrue(((Map<?, ?>) range.getModelAndView().getModel().get("errors")).containsKey("endDate"));
        verifyNoInteractions(service);
    }

    @Test
    void updateUsesUrlIdAndParsesDates() throws Exception {
        when(service.getById(1L)).thenReturn(new Plan("Cũ"));
        mvc.perform(post("/plans/1/edit").param("id", "999")
                        .param("title", "Mới").param("goal", "730+")
                        .param("startDate", "2026-11-01").param("endDate", "2026-11-02"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/plans/1"));
        verify(service).update(1L, "Mới", "730+", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
    }

    @Test
    void invalidUpdateKeepsEditFormAndDoesNotWrite() throws Exception {
        when(service.getById(1L)).thenReturn(new Plan("Cũ"));
        mvc.perform(post("/plans/1/edit").param("title", " "))
                .andExpect(view().name("plans/form"))
                .andExpect(model().attribute("editing", true))
                .andExpect(model().attribute("formAction", "/plans/1/edit"));
        verify(service, never()).update(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void missingIdsAre404ForDetailEditUpdateAndDelete() throws Exception {
        when(service.getById(99L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(service).delete(99L);
        mvc.perform(get("/plans/99")).andExpect(status().isNotFound());
        mvc.perform(get("/plans/99/edit")).andExpect(status().isNotFound());
        mvc.perform(post("/plans/99/edit").param("title", " ")).andExpect(status().isNotFound());
        mvc.perform(post("/plans/99/delete")).andExpect(status().isNotFound());
    }

    @Test
    void deleteRequiresPostAndRedirectsToList() throws Exception {
        mvc.perform(get("/plans/1/delete")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
        mvc.perform(post("/plans/1/delete"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/plans"));
        verify(service).delete(1L);
    }

    @Test
    void databaseFailureIsNotDisguisedAsFormValidation() {
        when(service.create("TOEIC", "", null, null))
                .thenThrow(new DataAccessResourceFailureException("DB unavailable"));
        ServletException exception = assertThrows(ServletException.class,
                () -> mvc.perform(post("/plans").param("title", "TOEIC")));
        assertInstanceOf(DataAccessResourceFailureException.class, exception.getCause());
    }
}
