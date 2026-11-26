package com.example.planner.service;

import com.example.planner.model.*;
import com.example.planner.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanTreeServiceTest {
    PlanRepository plans; SubPlanRepository subs; TaskRepository tasks; SubTaskRepository leaves; PlanTreeService service;
    final OffsetDateTime time=OffsetDateTime.parse("2026-01-01T00:00:00Z");
    Plan plan(long id) { return new Plan(id,"Plan " + id,null,null,null,time); }
    SubPlan sub(long id,long plan,Long parent) { return new SubPlan(id,plan,parent,"Sub",null,null,null,0,time); }
    Task task(long id,Long plan,Long sub) { return new Task(id,plan,sub,"Task",null,null,null,0,time); }
    SubTask leaf(long id,long task,boolean done) { return new SubTask(id,task,"Leaf",null,null,done,done?time:null,0,time); }
    @BeforeEach void setUp() {
        plans=mock(PlanRepository.class); subs=mock(SubPlanRepository.class); tasks=mock(TaskRepository.class); leaves=mock(SubTaskRepository.class);
        service=new PlanTreeService(plans,subs,tasks,leaves);
        when(plans.findById(1)).thenReturn(Optional.of(plan(1)));
    }
    @Test void fiveLevelsAndUnevenBranchesCountLeavesNotBranchAverages() {
        var rows=List.of(sub(1,1,null),sub(2,1,1L),sub(3,1,2L),sub(4,1,3L),sub(5,1,4L));
        when(subs.findByPlanId(1)).thenReturn(rows); when(subs.findById(5)).thenReturn(Optional.of(rows.get(4)));
        when(tasks.findByPlanIds(anyCollection())).thenReturn(List.of(task(10,1L,null),task(11,null,5L),task(12,1L,null)));
        when(leaves.findByTaskIds(anyCollection())).thenReturn(List.of(leaf(1,10,true),leaf(2,11,false),leaf(3,11,false),leaf(4,11,false)));
        var result=service.loadPlan(1); assertEquals(4,result.plan().getTotalCount()); assertEquals(1,result.plan().getCompletedCount());
        assertEquals(25,result.plan().getProgress()); assertEquals(5,result.subPlans().size());
        assertEquals(3,service.loadSubPlan(5).subPlan().getTotalCount());
        assertTrue(rows.get(0).getChildren().isEmpty()); // Không sửa collection metadata từ repository.
    }
    @Test void planListUsesOneBatchForEachNodeTypeAndNoPerLeafReads() {
        when(plans.findAll()).thenReturn(List.of(plan(1),plan(2)));
        when(tasks.findByPlanIds(anyCollection())).thenReturn(List.of(task(10,1L,null)));
        when(leaves.findByTaskIds(anyCollection())).thenReturn(List.of(leaf(1,10,true)));
        var result=service.loadPlans(); assertEquals(2,result.size()); assertEquals(1,result.get(0).getTotalCount()); assertEquals(0,result.get(1).getTotalCount());
        verify(subs).findByPlanIds(List.of(1L,2L)); verify(tasks).findByPlanIds(List.of(1L,2L)); verify(leaves).findByTaskIds(List.of(10L));
        verify(subs,never()).findByPlanId(anyLong()); verify(leaves,never()).findByTaskId(anyLong());
    }
    @Test void taskReloadReflectsAddedAndRemovedLeavesWithoutMutableCache() {
        Task original=task(10,null,null); when(tasks.findById(10)).thenReturn(Optional.of(original));
        when(leaves.findByTaskIds(anyCollection())).thenReturn(List.of(leaf(1,10,true)),List.of(leaf(1,10,true),leaf(2,10,false)),List.of());
        Task first=service.loadTask(10).task(); Task second=service.loadTask(10).task(); Task third=service.loadTask(10).task();
        assertEquals(1,first.getTotalCount()); assertEquals(2,second.getTotalCount()); assertEquals(0,third.getTotalCount());
        assertNotSame(first,second); assertTrue(original.getSubTasks().isEmpty()); assertEquals(1,first.getTotalCount());
    }
    @Test void emptyPlanListDoesNotQueryChildren() {
        assertTrue(service.loadPlans().isEmpty()); verifyNoInteractions(subs,tasks,leaves);
    }
    @Test void invalidHierarchyIsRejectedBeforeAttachingChildren() {
        for (List<SubPlan> invalid:List.of(
                List.of(sub(1,1,99L)),List.of(sub(1,1,2L),sub(2,1,1L)),List.of(sub(1,2,null)),
                List.of(sub(1,1,null),sub(2,1,1L),sub(3,1,2L),sub(4,1,3L),sub(5,1,4L),sub(6,1,5L)))) {
            when(subs.findByPlanId(1)).thenReturn(invalid);
            assertThrows(PlanTreeService.InvalidTreeException.class,()->service.loadPlan(1));
            assertTrue(invalid.get(0).getChildren().isEmpty());
        }
    }
    @Test void orphanTaskOrLeafAndDuplicateIdentityNeverReturnPartialTree() {
        when(tasks.findByPlanIds(anyCollection())).thenReturn(List.of(task(10,null,99L)));
        assertThrows(PlanTreeService.InvalidTreeException.class,()->service.loadPlan(1));
        when(tasks.findByPlanIds(anyCollection())).thenReturn(List.of(task(10,1L,null)));
        when(leaves.findByTaskIds(anyCollection())).thenReturn(List.of(leaf(1,99,false)));
        assertThrows(PlanTreeService.InvalidTreeException.class,()->service.loadPlan(1));
        when(leaves.findByTaskIds(anyCollection())).thenReturn(List.of(leaf(1,10,true),leaf(1,10,true)));
        assertThrows(PlanTreeService.InvalidTreeException.class,()->service.loadPlan(1));
        when(leaves.findByTaskIds(anyCollection())).thenReturn(List.of());
        when(tasks.findByPlanIds(anyCollection())).thenReturn(List.of(task(10,1L,null),task(10,1L,null)));
        assertThrows(PlanTreeService.InvalidTreeException.class,()->service.loadPlan(1));
    }
    @Test void taskListsValidateScopeAndDoNotMixIndependentTasksIntoPlan() {
        when(tasks.findIndependent()).thenReturn(List.of(task(10,null,null)));
        assertEquals(1,service.loadTasks(null,null).tasks().size());
        when(tasks.findByPlanId(1)).thenReturn(List.of(task(10,null,null)));
        assertThrows(PlanTreeService.InvalidTreeException.class,()->service.loadTasks(1L,null));
        when(subs.findById(2)).thenReturn(Optional.of(sub(2,1,null)));
        when(tasks.findBySubPlanId(2)).thenReturn(List.of(task(11,null,2L)));
        assertEquals(1,service.loadTasks(null,2L).tasks().size());
        assertThrows(IllegalArgumentException.class,()->service.loadTasks(1L,2L));
    }
    @Test void missingPrimaryIs404AndSqlFailurePropagatesOutOfLoader() {
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.loadPlan(99)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.loadTask(99)).getStatusCode().value());
        var failure=new DataAccessResourceFailureException("DB failure"); when(leaves.findByTaskIds(anyCollection())).thenThrow(failure);
        when(tasks.findByPlanIds(anyCollection())).thenReturn(List.of(task(10,1L,null)));
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->service.loadPlan(1)));
    }
    @Test void proxyStartsNewReadOnlyRepeatableReadAndRollsBackFailure() {
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status=mock(org.springframework.transaction.TransactionStatus.class); when(manager.getTransaction(any())).thenReturn(status);
        var advice=new org.springframework.transaction.interceptor.TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory=new org.springframework.aop.framework.ProxyFactory(service); factory.addAdvice(advice);
        var proxy=(PlanTreeService)factory.getProxy(); proxy.loadPlan(1);
        verify(manager).getTransaction(argThat(d->d.isReadOnly()
                && d.getIsolationLevel()==org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ
                && d.getPropagationBehavior()==org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(manager).commit(status);
        assertThrows(ResponseStatusException.class,()->proxy.loadPlan(99)); verify(manager).rollback(status);
    }
    @Test void existingTaskOrSubPlanWithMissingAncestorIsInvalidTreeNotMissingPrimary() {
        when(tasks.findById(10)).thenReturn(Optional.of(task(10,99L,null)));
        assertThrows(PlanTreeService.InvalidTreeException.class,()->service.loadTask(10));
        when(subs.findById(2)).thenReturn(Optional.of(sub(2,99,null)));
        assertThrows(PlanTreeService.InvalidTreeException.class,()->service.loadSubPlan(2));
        assertThrows(PlanTreeService.InvalidTreeException.class,()->service.loadTasks(null,2L));
    }
}
