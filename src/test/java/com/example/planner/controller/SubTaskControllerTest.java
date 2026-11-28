package com.example.planner.controller;

import com.example.planner.model.SubTask;
import com.example.planner.model.Task;
import com.example.planner.service.SubTaskService;
import com.example.planner.service.TaskService;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataAccessResourceFailureException;
import jakarta.servlet.ServletException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubTaskControllerTest extends AuthenticatedControllerTest {
    private SubTaskService service;
    private TaskService tasks;
    private MockMvc mvc;
    private SubTask leaf;
    private com.example.planner.service.PlanTreeService trees;
    @BeforeEach void setUp() {
        service=mock(SubTaskService.class); tasks=mock(TaskService.class);
        trees=mock(com.example.planner.service.PlanTreeService.class);
        mvc=MockMvcBuilders.standaloneSetup(new SubTaskController(service,tasks,trees))
                .setViewResolvers(new InternalResourceViewResolver("/test-views/",".html")).build();
        leaf=new SubTask(9L,7L,"Lá",null,null,false,null,0,OffsetDateTime.now());
        when(service.getById(OWNER_ID, 9)).thenReturn(leaf); when(tasks.getById(OWNER_ID, 7)).thenReturn(new Task("Cha"));
        when(service.findByTaskId(OWNER_ID, 7)).thenReturn(List.of(leaf));
        Task loaded=new Task("Cha"); loaded.setId(7L); loaded.addSubTask(leaf);
        when(trees.loadTask(OWNER_ID, 7)).thenReturn(new com.example.planner.service.PlanTreeService.TaskSnapshot(loaded,null,null));
    }
    @Test void listNewDetailAndEditExposeCorrectViews() throws Exception {
        mvc.perform(get("/subtasks").param("taskId","7")).andExpect(status().isOk())
                .andExpect(view().name("subtasks/list")).andExpect(model().attribute("subTasks",List.of(leaf)));
        mvc.perform(get("/subtasks/new").param("taskId","7")).andExpect(status().isOk())
                .andExpect(view().name("subtasks/form")).andExpect(model().attribute("taskIdInput","7"));
        mvc.perform(get("/subtasks/9")).andExpect(status().isOk()).andExpect(view().name("subtasks/detail"))
                .andExpect(model().attribute("subTask",leaf));
        mvc.perform(get("/subtasks/9/edit")).andExpect(status().isOk()).andExpect(view().name("subtasks/form"))
                .andExpect(model().attribute("editing",true)).andExpect(model().attribute("formAction","/subtasks/9/edit"));
        verify(service,never()).delete(eq(OWNER_ID), anyLong());
    }
    @Test void missingTaskShowsSelectionErrorAndCannotCreateUnattachedLeaf() throws Exception {
        mvc.perform(get("/subtasks")).andExpect(status().isOk()).andExpect(model().attributeExists("errors"));
        mvc.perform(get("/subtasks/new")).andExpect(status().isOk()).andExpect(model().attribute("taskIdInput",""));
        mvc.perform(get("/subtasks/new").param("subTaskId","9")).andExpect(status().isOk())
                .andExpect(model().attributeExists("errors"));
        mvc.perform(post("/subtasks").param("title","Tên")).andExpect(status().isOk())
                .andExpect(view().name("subtasks/form")).andExpect(model().attributeExists("errors"));
        verify(service,never()).create(eq(OWNER_ID), anyLong(),any(),any(),any(),anyInt()); verifyNoInteractions(tasks);
    }
    @Test void createUsesWhitelistOptionalDateAndFlashToDetail() throws Exception {
        when(service.create(OWNER_ID, 7,"Tên","",null,0)).thenReturn(9L);
        mvc.perform(post("/subtasks").param("taskId","7").param("title","Tên").param("done","true")
                        .param("completedAt","fake").param("createdAt","fake").param("id","99"))
                .andExpect(redirectedUrl("/subtasks/9")).andExpect(flash().attributeExists("message"));
        verify(service).create(OWNER_ID, 7,"Tên","",null,0);
    }
    @Test void editUsesStoredTaskAndIgnoresForgedStateAndIdentity() throws Exception {
        mvc.perform(post("/subtasks/9/edit").param("title","Mới").param("description","Nội dung")
                        .param("scheduledDate","2027-01-01").param("orderIndex","2").param("taskId","999")
                        .param("done","true").param("completedAt","fake").param("createdAt","fake").param("id","99"))
                .andExpect(redirectedUrl("/subtasks/9")).andExpect(flash().attributeExists("message"));
        verify(service).update(OWNER_ID, 9,"Mới","Nội dung",LocalDate.of(2027,1,1),2); verify(tasks,never()).getById(OWNER_ID, 999);
    }
    @Test void invalidRawInputIsPreservedAndNeverWritten() throws Exception {
        mvc.perform(post("/subtasks").param("taskId","bad").param("title"," ").param("scheduledDate","abc").param("orderIndex","oops"))
                .andExpect(status().isOk()).andExpect(view().name("subtasks/form"))
                .andExpect(model().attribute("taskIdInput","bad"))
                .andExpect(model().attribute("form",Map.of("title"," ","description","","scheduledDate","abc","orderIndex","oops")));
        for (String value:new String[]{"-1","2147483648"}) mvc.perform(post("/subtasks").param("taskId","7").param("title","Tên").param("orderIndex",value))
                .andExpect(view().name("subtasks/form"));
        mvc.perform(post("/subtasks").param("taskId","7").param("title","😀".repeat(256))).andExpect(view().name("subtasks/form"));
        mvc.perform(post("/subtasks/9/edit").param("title","Tên").param("scheduledDate","abc"))
                .andExpect(view().name("subtasks/form")).andExpect(model().attribute("editing",true));
        verify(service,never()).create(eq(OWNER_ID), anyLong(),any(),any(),any(),anyInt()); verify(service,never()).update(eq(OWNER_ID), anyLong(),any(),any(),any(),anyInt());
    }
    @Test void missingIdsMalformedParametersAndGetDeleteNeverSucceed() throws Exception {
        when(service.getById(OWNER_ID, 99)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(tasks.getById(OWNER_ID, 99)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/subtasks/99")).andExpect(status().isNotFound());
        mvc.perform(post("/subtasks/99/edit")).andExpect(status().isNotFound());
        mvc.perform(get("/subtasks/new").param("taskId","99")).andExpect(status().isNotFound());
        mvc.perform(get("/subtasks").param("taskId","bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/subtasks/9/delete")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/subtasks/9/completion")).andExpect(status().isBadRequest());
        verify(service,never()).delete(eq(OWNER_ID), anyLong());
    }

    @Test void completionUsesExplicitStateAndReturnsToVerifiedSource() throws Exception {
        when(service.setCompletion(OWNER_ID, 9, true)).thenReturn(7L);
        when(service.setCompletion(OWNER_ID, 9, false)).thenReturn(7L);
        when(tasks.isAncestor(OWNER_ID, 7, 11L, null)).thenReturn(true);
        when(tasks.isAncestor(OWNER_ID, 7, null, 12L)).thenReturn(true);
        mvc.perform(post("/subtasks/9/completion").param("done","true").param("source","task")
                        .param("sourceId","7").param("completedAt","forged"))
                .andExpect(redirectedUrl("/tasks/7")).andExpect(flash().attributeExists("message"));
        mvc.perform(post("/subtasks/9/completion").param("done","false").param("source","subtasks")
                        .param("sourceId","7"))
                .andExpect(redirectedUrl("/subtasks?taskId=7")).andExpect(flash().attributeExists("message"));
        verify(service).setCompletion(OWNER_ID, 9, true);
        verify(service).setCompletion(OWNER_ID, 9, false);
        mvc.perform(post("/subtasks/9/completion").param("done","true").param("source","plan").param("sourceId","11"))
                .andExpect(redirectedUrl("/plans/11"));
        mvc.perform(post("/subtasks/9/completion").param("done","true").param("source","subplan").param("sourceId","12"))
                .andExpect(redirectedUrl("/subplans/12"));
    }

    @Test void completionFallsBackForForgedOrMalformedSources() throws Exception {
        when(service.setCompletion(OWNER_ID, 9, true)).thenReturn(7L);
        for (var source : Map.of("task","99", "subtasks","7?extra=true", "plan","99",
                "subplan","99", "//external.invalid","7").entrySet()) {
            mvc.perform(post("/subtasks/9/completion").param("done","true")
                            .param("source",source.getKey()).param("sourceId",source.getValue()))
                    .andExpect(redirectedUrl("/subtasks/9"));
        }
        mvc.perform(post("/subtasks/9/completion").param("done","true")
                        .header("Referer","https://external.invalid/"))
                .andExpect(redirectedUrl("/subtasks/9"));
    }

    @Test void invalidCompletionAndGetNeverWriteAndMissingLeafReturns404() throws Exception {
        mvc.perform(get("/subtasks/9/completion").param("done","true")).andExpect(status().isMethodNotAllowed());
        for (String value : List.of("", "on", "1", "yes", "true,false")) {
            mvc.perform(post("/subtasks/9/completion").param("done",value)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/subtasks/9/completion").param("done", "true", "false")).andExpect(status().isBadRequest());
        verify(service, never()).setCompletion(eq(OWNER_ID), anyLong(), anyBoolean());
        when(service.setCompletion(OWNER_ID, 99, true)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(post("/subtasks/99/completion").param("done","true")).andExpect(status().isNotFound());
    }
    @Test void deleteReturnsToStoredTaskAndSqlFailureIsNotFormError() throws Exception {
        when(service.delete(OWNER_ID, 9)).thenReturn(7L);
        mvc.perform(post("/subtasks/9/delete").param("taskId","999")).andExpect(redirectedUrl("/tasks/7"))
                .andExpect(flash().attributeExists("message"));
        var failure=new DataAccessResourceFailureException("DB unavailable"); when(service.findByTaskId(OWNER_ID, 7)).thenThrow(failure);
        mvc.perform(get("/subtasks").param("taskId","7")).andExpect(status().isInternalServerError());
        when(service.create(OWNER_ID, 7,"Tên","",null,0)).thenThrow(failure);
        assertSame(failure,assertThrows(ServletException.class,()->mvc.perform(post("/subtasks").param("taskId","7").param("title","Tên"))).getCause());
    }

    @Test void missingParentOrDisappearanceDuringCreateKeepsRawInput() throws Exception {
        when(tasks.getById(OWNER_ID, 99)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(post("/subtasks").param("taskId","99").param("title","Giữ tên").param("scheduledDate","2026-11-16"))
                .andExpect(status().isOk()).andExpect(view().name("subtasks/form"))
                .andExpect(model().attribute("taskIdInput","99"))
                .andExpect(model().attribute("form",Map.of("title","Giữ tên","description","","scheduledDate","2026-11-16","orderIndex","0")));
        when(service.create(OWNER_ID, 7,"Race","",null,0)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(post("/subtasks").param("taskId","7").param("title","Race"))
                .andExpect(status().isOk()).andExpect(view().name("subtasks/form"))
                .andExpect(model().attribute("taskIdInput","7"));
        verify(service,never()).create(eq(OWNER_ID), eq(99L),any(),any(),any(),anyInt());
    }
}
