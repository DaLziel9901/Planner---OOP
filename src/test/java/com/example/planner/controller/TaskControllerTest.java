package com.example.planner.controller;

import com.example.planner.model.Task;
import com.example.planner.service.TaskService;
import com.example.planner.service.PlanService;
import com.example.planner.service.SubPlanService;
import com.example.planner.service.SubTaskService;
import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessResourceFailureException;
import jakarta.servlet.ServletException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskControllerTest {
    private TaskService service;
    private MockMvc mvc;
    private com.example.planner.service.PlanTreeService trees;
    private PlanService plans;
    private SubPlanService subPlans;
    @BeforeEach void setUp() {
        service = mock(TaskService.class);
        trees=mock(com.example.planner.service.PlanTreeService.class);
        when(trees.loadTask(anyLong())).thenThrow(new DataAccessResourceFailureException("Tree unavailable"));
        when(trees.loadTasks(any(),any())).thenThrow(new DataAccessResourceFailureException("Tree unavailable"));
        plans = mock(PlanService.class); subPlans = mock(SubPlanService.class);
        var views = new InternalResourceViewResolver("/test-views/", ".html");
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(service, plans, subPlans, trees)).setViewResolvers(views).build();
    }

    @Test void listNewDetailAndEditUseCorrectViewsAndData() throws Exception {
        Task task = new Task("Tên"); task.setDescription("Mô tả"); task.setDates(LocalDate.of(2026,11,16), null);
        when(service.findIndependent()).thenReturn(List.of(task)); when(service.getById(7)).thenReturn(task);
        mvc.perform(get("/tasks")).andExpect(status().isOk()).andExpect(view().name("tasks/list"))
                .andExpect(model().attribute("tasks", List.of(task))).andExpect(model().attribute("progressAvailable", false));
        mvc.perform(get("/tasks/new")).andExpect(status().isOk()).andExpect(view().name("tasks/form"))
                .andExpect(model().attribute("editing", false)).andExpect(model().attribute("formAction", "/tasks"));
        mvc.perform(get("/tasks/7")).andExpect(status().isOk()).andExpect(view().name("tasks/detail"))
                .andExpect(model().attribute("task", task)).andExpect(model().attribute("progressAvailable", false));
        mvc.perform(get("/tasks/7/edit")).andExpect(status().isOk()).andExpect(view().name("tasks/form"))
                .andExpect(model().attribute("editing", true)).andExpect(model().attribute("formAction", "/tasks/7/edit"))
                .andExpect(model().attribute("form", Map.of("title","Tên","description","Mô tả","startDate","2026-11-16","endDate","","orderIndex","0")));
        verify(service, never()).delete(anyLong()); verify(service, never()).create(any(),any(),any(),any(),anyInt());
    }

    @Test void createUsesWhitelistAndRedirectsWithFlash() throws Exception {
        when(service.create("Tên", "", null, null, 0)).thenReturn(7L);
        mvc.perform(post("/tasks").param("title","Tên")
                        .param("id","999").param("createdAt","fake").param("done","true"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/tasks/7"))
                .andExpect(flash().attributeExists("message"));
        verify(service).create("Tên", "", null, null, 0);
    }

    @Test void editUsesUrlIdAndOnlyContentFields() throws Exception {
        when(service.getById(7)).thenReturn(new Task("Cũ"));
        mvc.perform(post("/tasks/7/edit").param("title","Mới").param("description","Nội dung")
                        .param("orderIndex","2").param("startDate","2026-11-16").param("id","99")
                        .param("planId","3").param("createdAt","fake"))
                .andExpect(redirectedUrl("/tasks/7")).andExpect(flash().attributeExists("message"));
        verify(service).update(7, "Mới", "Nội dung", LocalDate.of(2026,11,16), null, 2);
    }

    @Test void invalidInputsPreserveRawFormAndNeverWrite() throws Exception {
        mvc.perform(post("/tasks").param("title","\u2003").param("startDate","bad").param("orderIndex","abc"))
                .andExpect(status().isOk()).andExpect(view().name("tasks/form"))
                .andExpect(model().attribute("form", Map.of("title","\u2003","description","","startDate","bad","endDate","","orderIndex","abc")))
                .andExpect(model().attributeExists("errors"));
        for (String value : new String[]{"-1", "2147483648"})
            mvc.perform(post("/tasks").param("title","Tên").param("orderIndex",value)).andExpect(view().name("tasks/form"));
        mvc.perform(post("/tasks").param("title","😀".repeat(256))).andExpect(view().name("tasks/form"));
        mvc.perform(post("/tasks").param("title","Tên").param("startDate","2026-11-17").param("endDate","2026-11-16"))
                .andExpect(view().name("tasks/form"));
        when(service.getById(7)).thenReturn(new Task("Tên"));
        mvc.perform(post("/tasks/7/edit").param("title","Tên").param("endDate","bad"))
                .andExpect(view().name("tasks/form")).andExpect(model().attribute("editing",true));
        verify(service, never()).create(any(),any(),any(),any(),anyInt());
        verify(service, never()).update(anyLong(),any(),any(),any(),any(),anyInt());
    }

    @Test void missingOrMalformedIdsAndGetDeleteNeverWrite() throws Exception {
        when(service.getById(99)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(service).delete(99);
        mvc.perform(get("/tasks/99")).andExpect(status().isNotFound());
        mvc.perform(get("/tasks/99/edit")).andExpect(status().isNotFound());
        mvc.perform(post("/tasks/99/edit")).andExpect(status().isNotFound());
        mvc.perform(post("/tasks/99/delete")).andExpect(status().isNotFound());
        mvc.perform(get("/tasks/bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/tasks/7/delete")).andExpect(status().isMethodNotAllowed());
        verify(service, never()).delete(7);
    }

    @Test void deleteRedirectsToIndependentListWithFlash() throws Exception {
        when(service.getById(7)).thenReturn(new Task("Tên"));
        mvc.perform(post("/tasks/7/delete")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks")).andExpect(flash().attributeExists("message"));
        verify(service).delete(7);
    }

    @Test void malformedOrDualFiltersNeverBecomeIndependentList() throws Exception {
        mvc.perform(get("/tasks").param("planId","1").param("subPlanId","2")).andExpect(status().isBadRequest());
        mvc.perform(get("/tasks/new").param("subPlanId","bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/tasks").param("planId","0")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private void parents() {
        Plan plan = new Plan(1L, "Plan", null, null, null, java.time.OffsetDateTime.now());
        SubPlan sub = new SubPlan(2L, 1L, null, "Sub", null, null, null, 0, java.time.OffsetDateTime.now());
        when(plans.getById(1)).thenReturn(plan); when(plans.findAll()).thenReturn(List.of(plan));
        when(subPlans.getById(2)).thenReturn(sub); when(subPlans.findByPlanId(1)).thenReturn(List.of(sub));
    }

    @Test void listsUseExactlyOneLocationAndNewFormPreservesSelection() throws Exception {
        parents(); Task task = new Task("Scoped"); when(service.findByPlanId(1)).thenReturn(List.of(task));
        mvc.perform(get("/tasks").param("planId","1")).andExpect(status().isOk())
                .andExpect(model().attribute("tasks",List.of(task))).andExpect(model().attribute("taskListUrl","/tasks?planId=1"));
        mvc.perform(get("/tasks").param("subPlanId","2")).andExpect(status().isOk())
                .andExpect(model().attribute("taskListUrl","/tasks?subPlanId=2"));
        verify(service, never()).findIndependent(); verify(service).findByPlanId(1); verify(service).findBySubPlanId(2);
        mvc.perform(get("/tasks/new").param("subPlanId","2")).andExpect(status().isOk())
                .andExpect(model().attribute("locationForm",Map.of("planId","","subPlanId","2")));
        when(plans.getById(99)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/tasks").param("planId","99")).andExpect(status().isNotFound());
    }

    @Test void createUnderEitherParentRejectsDualOrMalformedSelectionAndPreservesInput() throws Exception {
        parents(); when(service.create(1L,null,"Tên","",null,null,0)).thenReturn(8L);
        when(service.create(null,2L,"Tên","",null,null,0)).thenReturn(9L);
        mvc.perform(post("/tasks").param("title","Tên").param("planId","1"))
                .andExpect(redirectedUrl("/tasks/8")).andExpect(flash().attributeExists("message"));
        mvc.perform(post("/tasks").param("title","Tên").param("subPlanId","2"))
                .andExpect(redirectedUrl("/tasks/9"));
        mvc.perform(post("/tasks").param("title","Giữ").param("planId","1").param("subPlanId","2"))
                .andExpect(view().name("tasks/form")).andExpect(model().attribute("locationForm",Map.of("planId","1","subPlanId","2")));
        mvc.perform(post("/tasks").param("title","Giữ").param("planId","bad"))
                .andExpect(view().name("tasks/form")).andExpect(model().attribute("locationForm",Map.of("planId","bad","subPlanId","")));
        verify(service, never()).create(any(),any(),eq("Giữ"),any(),any(),any(),anyInt());
    }

    @Test void deleteReturnsToStoredParentEvenWithForgedRequestLocation() throws Exception {
        parents(); Task task = new Task("Task"); task.setLocation(null,2L);
        when(service.getById(7)).thenReturn(task);
        mvc.perform(post("/tasks/7/delete").param("planId","99"))
                .andExpect(redirectedUrl("/tasks?subPlanId=2"));
        verify(service).delete(7);
    }

    @Test void databaseErrorsDoNotBecomeSuccessOrFormValidation() throws Exception {
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(service.findIndependent()).thenThrow(failure);
        mvc.perform(get("/tasks")).andExpect(status().isInternalServerError());
        when(service.create("Tên","",null,null,0)).thenThrow(failure);
        assertSame(failure, assertThrows(ServletException.class, () -> mvc.perform(post("/tasks").param("title","Tên"))).getCause());
    }
}
