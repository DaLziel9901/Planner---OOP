package com.example.planner.controller;

import com.example.planner.model.*;
import com.example.planner.service.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProgressReadWebTest {
    TaskService tasks; SubTaskService leaves; PlanService plans; SubPlanService subs; PlanTreeService trees; MockMvc mvc;
    Task main; SubTask leaf;
    Task task(long id,String title) { return new Task(id,null,null,title,null,null,null,0,OffsetDateTime.now()); }
    @BeforeEach void setUp() {
        tasks=mock(TaskService.class); leaves=mock(SubTaskService.class); plans=mock(PlanService.class); subs=mock(SubPlanService.class); trees=mock(PlanTreeService.class);
        mvc=MockMvcBuilders.standaloneSetup(new TaskController(tasks,plans,subs,trees),new SubTaskController(leaves,tasks,trees),
                new PlanController(plans,trees),new SubPlanController(subs,plans,trees))
                .setViewResolvers(new InternalResourceViewResolver("/test-views/",".html")).build();
        main=task(7,"Metadata cũ"); leaf=new SubTask(9L,7L,"Lá",null,null,false,null,0,OffsetDateTime.now());
        when(tasks.getById(7)).thenReturn(main); when(tasks.findIndependent()).thenReturn(List.of(main));
        when(leaves.getById(9)).thenReturn(leaf); when(leaves.findByTaskId(7)).thenReturn(List.of(leaf));
        Plan root=new Plan(1L,"Plan metadata",null,null,null,OffsetDateTime.now());
        SubPlan sub=new SubPlan(2L,1L,null,"Sub metadata",null,null,null,0,OffsetDateTime.now());
        when(plans.getById(1)).thenReturn(root); when(plans.findAll()).thenReturn(List.of(root));
        when(subs.getById(2)).thenReturn(sub); when(subs.getPlan(1)).thenReturn(root); when(subs.findByPlanId(1)).thenReturn(List.of(sub));
    }
    @Test void taskSnapshotReplacesMetadataAndUsesLeafCounts() throws Exception {
        Task loaded=task(7,"Metadata snapshot"); loaded.addSubTask(leaf);
        when(trees.loadTask(7)).thenReturn(new PlanTreeService.TaskSnapshot(loaded,null,null));
        mvc.perform(get("/tasks/7")).andExpect(status().isOk()).andExpect(model().attribute("task",loaded))
                .andExpect(model().attribute("progress",ProgressPresentation.from(loaded)))
                .andExpect(model().attribute("subTasks",List.of(leaf))).andExpect(model().attribute("progressAvailable",true));
    }
    @Test void taskProgressFailureReturns200WithoutPartialLeavesOrNumbers() throws Exception {
        when(trees.loadTask(7)).thenThrow(new DataAccessResourceFailureException("DB failure"));
        mvc.perform(get("/tasks/7")).andExpect(status().isOk()).andExpect(model().attribute("task",main))
                .andExpect(model().attribute("progress",ProgressPresentation.unavailable()))
                .andExpect(model().attribute("subTasks",List.of())).andExpect(model().attribute("progressAvailable",false));
    }
    @Test void taskPrimaryMissing404AndSqlFailure500AreDistinct() throws Exception {
        when(tasks.getById(99)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(tasks.getById(98)).thenThrow(new DataAccessResourceFailureException("private SQL details"));
        mvc.perform(get("/tasks/99")).andExpect(status().isNotFound());
        var response=mvc.perform(get("/tasks/98")).andExpect(status().isInternalServerError()).andReturn();
        assertFalse(response.getResponse().getErrorMessage().contains("private SQL")); verifyNoInteractions(trees);
    }
    @Test void taskListUsesOneBatchAndLoadedEmptyIsNotUnavailable() throws Exception {
        when(trees.loadTasks(null,null)).thenReturn(new PlanTreeService.TaskListSnapshot(List.of(main),null,null));
        mvc.perform(get("/tasks")).andExpect(status().isOk())
                .andExpect(model().attribute("progressById",Map.of(7L,ProgressPresentation.from(main))));
        verify(trees).loadTasks(null,null); verify(trees,never()).loadTask(anyLong());
    }
    @Test void subTaskSnapshotUsesCurrentLeafAndParentMetadata() throws Exception {
        Task loaded=task(7,"Task snapshot"); SubTask changed=new SubTask(9L,7L,"Lá snapshot",null,null,true,OffsetDateTime.now(),0,OffsetDateTime.now());
        loaded.addSubTask(changed); when(trees.loadTask(7)).thenReturn(new PlanTreeService.TaskSnapshot(loaded,null,null));
        mvc.perform(get("/subtasks/9")).andExpect(status().isOk()).andExpect(model().attribute("subTask",changed))
                .andExpect(model().attribute("task",loaded)).andExpect(model().attribute("progress",ProgressPresentation.from(loaded)));
    }
    @Test void subTaskProgressFailureDoesNotMasqueradeAsEmptyList() throws Exception {
        when(trees.loadTask(7)).thenThrow(new PlanTreeService.InvalidTreeException("Orphan"));
        mvc.perform(get("/subtasks").param("taskId","7")).andExpect(status().isOk())
                .andExpect(model().attribute("progress",ProgressPresentation.unavailable()))
                .andExpect(model().attribute("subTasks",List.of())).andExpect(model().attribute("treeAvailable",false));
    }
    @Test void subTaskPrimary404AndSql500DoNotTurnIntoUnavailable200() throws Exception {
        when(leaves.getById(99)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(leaves.findByTaskId(7)).thenThrow(new DataAccessResourceFailureException("SQL failure"));
        mvc.perform(get("/subtasks/99")).andExpect(status().isNotFound());
        mvc.perform(get("/subtasks").param("taskId","7")).andExpect(status().isInternalServerError()); verifyNoInteractions(trees);
    }
    @Test void programmingErrorsAreNotSwallowedAsUnavailable() {
        when(trees.loadTask(7)).thenThrow(new IllegalStateException("Bug"));
        assertInstanceOf(IllegalStateException.class,assertThrows(jakarta.servlet.ServletException.class,()->mvc.perform(get("/tasks/7"))).getCause());
    }
    @Test void planListUsesOneSnapshotAndKeepsEmptySeparateFromUnavailable() throws Exception {
        Plan root=new Plan(1L,"Snapshot Plan",null,null,null,OffsetDateTime.now());
        when(trees.loadPlans()).thenReturn(List.of(root));
        mvc.perform(get("/plans")).andExpect(status().isOk()).andExpect(model().attribute("plans",List.of(root)))
                .andExpect(model().attribute("progressById",Map.of(1L,ProgressPresentation.from(root))));
        verify(trees).loadPlans(); verify(trees,never()).loadPlan(anyLong());
    }
    @Test void planMissingSqlFailureAndTreeFailureHaveDifferentOutcomes() throws Exception {
        when(plans.getById(99)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(plans.getById(98)).thenThrow(new DataAccessResourceFailureException("private SQL"));
        mvc.perform(get("/plans/99")).andExpect(status().isNotFound()); mvc.perform(get("/plans/98")).andExpect(status().isInternalServerError());
        when(trees.loadPlan(1)).thenThrow(new DataAccessResourceFailureException("Tree"));
        mvc.perform(get("/plans/1")).andExpect(status().isOk()).andExpect(model().attribute("progress",ProgressPresentation.unavailable()));
    }
    @Test void subPlanListUsesSamePlanSnapshotForMetadataAndEveryRow() throws Exception {
        Plan root=new Plan(1L,"Snapshot Plan",null,null,null,OffsetDateTime.now());
        SubPlan sub=new SubPlan(2L,1L,null,"Snapshot Sub",null,null,null,0,OffsetDateTime.now()); root.addSubPlan(sub);
        when(trees.loadPlan(1)).thenReturn(new PlanTreeService.PlanSnapshot(root,List.of(sub)));
        mvc.perform(get("/subplans").param("planId","1")).andExpect(status().isOk())
                .andExpect(model().attribute("plan",root)).andExpect(model().attribute("subPlans",List.of(sub)))
                .andExpect(model().attribute("progressById",Map.of(2L,ProgressPresentation.from(sub))));
        verify(trees).loadPlan(1);
    }
    @Test void subPlanDetailDoesNotDisplayPartialChildrenOnTreeFailure() throws Exception {
        when(trees.loadSubPlan(2)).thenThrow(new PlanTreeService.InvalidTreeException("Cycle"));
        mvc.perform(get("/subplans/2")).andExpect(status().isOk()).andExpect(model().attribute("children",List.of()))
                .andExpect(model().attribute("progress",ProgressPresentation.unavailable())).andExpect(model().attribute("treeAvailable",false));
        when(subs.getById(99)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(subs.getById(98)).thenThrow(new DataAccessResourceFailureException("SQL"));
        mvc.perform(get("/subplans/99")).andExpect(status().isNotFound()); mvc.perform(get("/subplans/98")).andExpect(status().isInternalServerError());
    }
}
