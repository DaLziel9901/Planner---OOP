package com.example.planner.service;

import com.example.planner.model.Task;
import com.example.planner.repository.TaskRepository;
import com.example.planner.repository.PlanRepository;
import com.example.planner.repository.SubPlanRepository;
import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskServiceTest {
    private static final long OWNER_ID = 42L;
    private TaskRepository repository;
    private TaskService service;
    private PlanRepository plans;
    private SubPlanRepository subPlans;
    private final LocalDate date = LocalDate.of(2026, 11, 16);
    @BeforeEach void setUp() {
        repository = mock(TaskRepository.class); plans = mock(PlanRepository.class); subPlans = mock(SubPlanRepository.class);
        service = new TaskService(repository, plans, subPlans);
    }

    private Task saved() {
        return new Task(7L, null, null, "Cũ", null, null, null, 0, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }

    @Test void completionSourceMustBeAnActualAncestor() {
        Task task = saved();
        when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(task));
        assertFalse(service.isAncestor(OWNER_ID, 7, 1L, null));
        task.setLocation(1L, null);
        assertTrue(service.isAncestor(OWNER_ID, 7, 1L, null));
        assertFalse(service.isAncestor(OWNER_ID, 7, null, 1L));
        task.setLocation(null, 3L);
        when(subPlans.findByIdForOwner(OWNER_ID, 3L)).thenReturn(Optional.of(new SubPlan(3L, 1L, 2L, "Con", null,
                null, null, 0, task.getCreatedAt())));
        when(subPlans.findByIdForOwner(OWNER_ID, 2L)).thenReturn(Optional.of(new SubPlan(2L, 1L, null, "Cha", null,
                null, null, 0, task.getCreatedAt())));
        assertTrue(service.isAncestor(OWNER_ID, 7, 1L, null));
        assertTrue(service.isAncestor(OWNER_ID, 7, null, 2L));
        assertTrue(service.isAncestor(OWNER_ID, 7, null, 3L));
        assertFalse(service.isAncestor(OWNER_ID, 7, null, 4L));
        assertFalse(service.isAncestor(OWNER_ID, 7, 4L, null));
    }

    @Test void listAndReadDelegateWithoutLoadingProgress() {
        Task task = saved(); when(repository.findIndependentForOwner(OWNER_ID)).thenReturn(List.of(task));
        when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(task));
        assertEquals(List.of(task), service.findIndependent(OWNER_ID)); assertSame(task, service.getById(OWNER_ID, 7));
    }

    @Test void createAllowsDuplicateUnicodeTitlesAndAllOptionalDateCombinations() {
        when(repository.insertForOwner(eq(OWNER_ID), any())).thenReturn(Optional.of(1L), Optional.of(2L), Optional.of(3L), Optional.of(4L));
        String title = "😀".repeat(255);
        assertEquals(1, service.create(OWNER_ID, title, "", null, null, 0));
        assertEquals(2, service.create(OWNER_ID, title, null, date, null, 0));
        assertEquals(3, service.create(OWNER_ID, "😀", "Nội dung", null, date, 1));
        assertEquals(4, service.create(OWNER_ID, "Tên", "", date, date, 2));
        var captured = ArgumentCaptor.forClass(Task.class); verify(repository, times(4)).insertForOwner(eq(OWNER_ID), captured.capture());
        assertNull(captured.getValue().getPlanId()); assertNull(captured.getValue().getSubPlanId());
        assertEquals("", captured.getValue().getDescription()); assertEquals(2, captured.getValue().getOrderIndex());
    }

    @Test void invalidCreateNeverWrites() {
        for (String title : new String[]{null, "", "\u2003", "😀".repeat(256)})
            assertThrows(IllegalArgumentException.class, () -> service.create(OWNER_ID, title, null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER_ID, "Tên", null, date, date.minusDays(1), 0));
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER_ID, "Tên", null, null, null, -1));
        verifyNoInteractions(repository);
    }

    @Test void updateLocksThenReadsAndPreservesLocationAndCreationTime() {
        Task old = saved(); when(repository.lockTaskForOwner(OWNER_ID, 7)).thenReturn(true);
        when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(old)); when(repository.updateForOwner(eq(OWNER_ID), any())).thenReturn(true);
        service.update(OWNER_ID, 7, "Mới", "", date, date, 3);
        var captured = ArgumentCaptor.forClass(Task.class); var order = inOrder(repository);
        order.verify(repository).findByIdForOwner(OWNER_ID, 7); order.verify(repository).lockTaskForOwner(OWNER_ID, 7); order.verify(repository).findByIdForOwner(OWNER_ID, 7); order.verify(repository).updateForOwner(eq(OWNER_ID), captured.capture());
        Task updated = captured.getValue(); assertEquals("Mới", updated.getTitle());
        assertEquals(old.getCreatedAt(), updated.getCreatedAt()); assertEquals(old.getId(), updated.getId());
        assertNull(updated.getPlanId()); assertNull(updated.getSubPlanId());
    }

    @Test void invalidUpdateDoesNotWriteOrMutateLoadedObject() {
        Task old = saved(); when(repository.lockTaskForOwner(OWNER_ID, 7)).thenReturn(true); when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(old));
        assertThrows(IllegalArgumentException.class, () -> service.update(OWNER_ID, 7, "Mới", "", date, date.minusDays(1), 0));
        assertThrows(IllegalArgumentException.class, () -> service.update(OWNER_ID, 7, "Mới", "", null, null, -1));
        assertEquals("Cũ", old.getTitle()); verify(repository, never()).updateForOwner(eq(OWNER_ID), any());
    }

    @Test void deleteLocksThenRereadsBeforeDeleting() {
        when(repository.lockTaskForOwner(OWNER_ID, 7)).thenReturn(true); when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(saved()));
        when(repository.deleteByIdForOwner(OWNER_ID, 7)).thenReturn(true); service.delete(OWNER_ID, 7);
        var order = inOrder(repository); order.verify(repository).findByIdForOwner(OWNER_ID, 7); order.verify(repository).lockTaskForOwner(OWNER_ID, 7); order.verify(repository).findByIdForOwner(OWNER_ID, 7);
        order.verify(repository).deleteByIdForOwner(OWNER_ID, 7);
        verifyNoInteractions(plans, subPlans);
    }

    @Test void invalidIdsAndMissingLockOrRereadReturn404WithoutWrites() {
        assert404(() -> service.getById(OWNER_ID, 0)); assert404(() -> service.delete(OWNER_ID, -1));
        assert404(() -> service.update(OWNER_ID, 0, "Tên", "", null, null, 0)); verifyNoInteractions(repository);
        assert404(() -> service.getById(OWNER_ID, 99)); assert404(() -> service.delete(OWNER_ID, 99));
        when(repository.lockTaskForOwner(OWNER_ID, 7)).thenReturn(true);
        assert404(() -> service.delete(OWNER_ID, 7)); assert404(() -> service.update(OWNER_ID, 7, "Tên", "", null, null, 0));
        verify(repository, never()).deleteByIdForOwner(eq(OWNER_ID), anyLong()); verify(repository, never()).updateForOwner(eq(OWNER_ID), any());
    }

    @Test void zeroAffectedRowsAndDatabaseFailureAreNotSuccess() {
        when(repository.lockTaskForOwner(OWNER_ID, 7)).thenReturn(true); when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(saved()));
        assert404(() -> service.update(OWNER_ID, 7, "Tên", "", null, null, 0)); assert404(() -> service.delete(OWNER_ID, 7));
        var failure = new DataAccessResourceFailureException("DB unavailable"); when(repository.lockTaskForOwner(OWNER_ID, 8)).thenThrow(failure);
        Task eighth = saved(); eighth.setId(8L); when(repository.findByIdForOwner(OWNER_ID, 8)).thenReturn(Optional.of(eighth));
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.delete(OWNER_ID, 8)));
    }

    @Test void springProxyWrapsLockReadWriteInTransactionAndRollsBackFailure() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory = new org.springframework.aop.framework.ProxyFactory(service); factory.addAdvice(interceptor);
        TaskService proxy = (TaskService) factory.getProxy();
        when(repository.lockTaskForOwner(OWNER_ID, 7)).thenReturn(true); when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(saved()));
        when(repository.updateForOwner(eq(OWNER_ID), any())).thenReturn(true);
        proxy.update(OWNER_ID, 7, "Mới", "", null, null, 0);
        var order = inOrder(manager, repository); order.verify(manager).getTransaction(any());
        order.verify(repository).findByIdForOwner(OWNER_ID, 7); order.verify(repository).lockTaskForOwner(OWNER_ID, 7); order.verify(repository).findByIdForOwner(OWNER_ID, 7); order.verify(repository).updateForOwner(eq(OWNER_ID), any());
        order.verify(manager).commit(status);
        assert404(() -> proxy.delete(OWNER_ID, 8)); verify(manager).rollback(status);
    }

    private void assert404(org.junit.jupiter.api.function.Executable call) {
        assertEquals(404, assertThrows(ResponseStatusException.class, call).getStatusCode().value());
    }

    private SubPlan parent() {
        return new SubPlan(2L, 1L, null, "Cha", null, date, date, 0, saved().getCreatedAt());
    }

    @Test void createAtEitherParentLocksPlanAndAllowsDatesOutsideParent() {
        when(subPlans.lockPlanForOwner(OWNER_ID, 1)).thenReturn(true); when(subPlans.findByIdForOwner(OWNER_ID, 2)).thenReturn(Optional.of(parent()));
        when(repository.insertForOwner(eq(OWNER_ID), any())).thenReturn(Optional.of(10L), Optional.of(11L));
        assertEquals(10, service.create(OWNER_ID, 1L, null, "Plan Task", "", date.minusDays(9), null, 0));
        assertEquals(11, service.create(OWNER_ID, null, 2L, "SubPlan Task", "", null, date.plusDays(9), 0));
        var captured = ArgumentCaptor.forClass(Task.class); verify(repository, times(2)).insertForOwner(eq(OWNER_ID), captured.capture());
        assertEquals(1L, captured.getAllValues().get(0).getPlanId()); assertNull(captured.getAllValues().get(0).getSubPlanId());
        assertNull(captured.getValue().getPlanId()); assertEquals(2L, captured.getValue().getSubPlanId());
        assertEquals(date.plusDays(9), captured.getValue().getEndDate());
        verify(subPlans, times(2)).lockPlanForOwner(OWNER_ID, 1); verify(repository, never()).lockTaskForOwner(eq(OWNER_ID), anyLong());
    }

    @Test void invalidOrDisappearingParentsNeverInsert() {
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER_ID, 1L, 2L, "Tên", "", null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER_ID, 0L, null, "Tên", "", null, null, 0));
        verifyNoInteractions(repository, subPlans, plans);
        assert404(() -> service.create(OWNER_ID, 99L, null, "Tên", "", null, null, 0));
        assert404(() -> service.create(OWNER_ID, null, 99L, "Tên", "", null, null, 0));
        when(subPlans.findByIdForOwner(OWNER_ID, 2)).thenReturn(Optional.of(parent()), Optional.empty());
        when(subPlans.lockPlanForOwner(OWNER_ID, 1)).thenReturn(true);
        assert404(() -> service.create(OWNER_ID, null, 2L, "Tên", "", null, null, 0));
        verify(repository, never()).insertForOwner(eq(OWNER_ID), any());
    }

    @Test void treeUpdateLocksPlanBeforeTaskAndRereadsBothSubPlanAndTask() {
        Task task = saved(); task.setLocation(null, 2L);
        when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(task)); when(repository.lockTaskForOwner(OWNER_ID, 7)).thenReturn(true);
        when(subPlans.findByIdForOwner(OWNER_ID, 2)).thenReturn(Optional.of(parent())); when(subPlans.lockPlanForOwner(OWNER_ID, 1)).thenReturn(true);
        when(repository.updateForOwner(eq(OWNER_ID), any())).thenReturn(true);
        service.update(OWNER_ID, 7, "Mới", "", date.minusDays(3), null, 0);
        var order = inOrder(repository, subPlans);
        order.verify(repository).findByIdForOwner(OWNER_ID, 7); order.verify(subPlans).findByIdForOwner(OWNER_ID, 2); order.verify(subPlans).lockPlanForOwner(OWNER_ID, 1);
        order.verify(subPlans).findByIdForOwner(OWNER_ID, 2); order.verify(repository).lockTaskForOwner(OWNER_ID, 7); order.verify(repository).findByIdForOwner(OWNER_ID, 7);
        order.verify(repository).updateForOwner(eq(OWNER_ID), argThat(value -> value.getPlanId() == null && Long.valueOf(2).equals(value.getSubPlanId())));
    }

    @Test void planTaskDeleteLocksPlanThenTaskAndKeepsMissingRowsAs404() {
        Task task = saved(); task.setLocation(1L, null);
        when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(task)); when(subPlans.lockPlanForOwner(OWNER_ID, 1)).thenReturn(true);
        when(repository.lockTaskForOwner(OWNER_ID, 7)).thenReturn(true); when(repository.deleteByIdForOwner(OWNER_ID, 7)).thenReturn(true);
        service.delete(OWNER_ID, 7);
        var order = inOrder(repository, subPlans);
        order.verify(repository).findByIdForOwner(OWNER_ID, 7); order.verify(subPlans).lockPlanForOwner(OWNER_ID, 1);
        order.verify(repository).lockTaskForOwner(OWNER_ID, 7); order.verify(repository).findByIdForOwner(OWNER_ID, 7); order.verify(repository).deleteByIdForOwner(OWNER_ID, 7);
        clearInvocations(repository, subPlans);
        when(subPlans.lockPlanForOwner(OWNER_ID, 1)).thenReturn(false);
        assert404(() -> service.delete(OWNER_ID, 7)); verify(repository, never()).lockTaskForOwner(eq(OWNER_ID), anyLong()); verify(repository, never()).deleteByIdForOwner(eq(OWNER_ID), anyLong());
    }

    @Test void changedLocationOrMissingRereadAfterTaskLockNeverWrites() {
        Task before = saved(); before.setLocation(1L, null);
        when(subPlans.lockPlanForOwner(OWNER_ID, 1)).thenReturn(true); when(repository.lockTaskForOwner(OWNER_ID, 7)).thenReturn(true);
        when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(before), Optional.of(saved()));
        assert404(() -> service.update(OWNER_ID, 7, "Tên", "", null, null, 0));
        when(repository.findByIdForOwner(OWNER_ID, 7)).thenReturn(Optional.of(before), Optional.empty());
        assert404(() -> service.delete(OWNER_ID, 7));
        verify(repository, never()).updateForOwner(eq(OWNER_ID), any()); verify(repository, never()).deleteByIdForOwner(eq(OWNER_ID), anyLong());
    }

    @Test void scopedListsValidateParentsAndOnlyUseMatchingQuery() {
        when(plans.findByIdForOwner(OWNER_ID, 1)).thenReturn(Optional.of(new Plan("Plan")));
        when(subPlans.findByIdForOwner(OWNER_ID, 2)).thenReturn(Optional.of(parent()));
        Task task = saved(); when(repository.findByPlanIdForOwner(OWNER_ID, 1)).thenReturn(List.of(task));
        assertEquals(List.of(task), service.findByPlanId(OWNER_ID, 1)); assertEquals(List.of(), service.findBySubPlanId(OWNER_ID, 2));
        assert404(() -> service.findByPlanId(OWNER_ID, 99)); assert404(() -> service.findBySubPlanId(OWNER_ID, 99));
        verify(repository, never()).findByPlanIdForOwner(OWNER_ID, 99); verify(repository, never()).findBySubPlanIdForOwner(OWNER_ID, 99);
    }
}
