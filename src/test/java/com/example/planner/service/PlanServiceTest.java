package com.example.planner.service;

import com.example.planner.model.Plan;
import com.example.planner.repository.PlanRepository;
import com.example.planner.repository.SubPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Unit test: repository giả, không kết nối PostgreSQL.
class PlanServiceTest {
    private static final long OWNER_ID = 42L;
    private PlanRepository repository;
    private PlanService service;
    private SubPlanRepository locks;

    @BeforeEach
    void setUp() {
        repository = mock(PlanRepository.class);
        locks = mock(SubPlanRepository.class);
        service = new PlanService(repository, locks);
    }

    @Test
    void listAndReadReturnRepositoryData() {
        Plan plan = new Plan("TOEIC");
        when(repository.findAllForOwner(OWNER_ID)).thenReturn(List.of(plan));
        when(repository.findByIdForOwner(OWNER_ID, 1L)).thenReturn(Optional.of(plan));
        assertEquals(List.of(plan), service.findAll(OWNER_ID));
        assertSame(plan, service.getById(OWNER_ID, 1L));
    }

    @Test
    void createReturnsGeneratedIdAndAllowsOptionalFields() {
        when(repository.insertForOwner(eq(OWNER_ID), any(Plan.class))).thenReturn(12L);
        assertEquals(12L, service.create(OWNER_ID, "TOEIC", "  ", null, null));
        ArgumentCaptor<Plan> captured = ArgumentCaptor.forClass(Plan.class);
        verify(repository).insertForOwner(eq(OWNER_ID), captured.capture());
        assertEquals("TOEIC", captured.getValue().getTitle());
        assertNull(captured.getValue().getGoal());
        assertNull(captured.getValue().getStartDate());
        assertNull(captured.getValue().getEndDate());
        assertNull(captured.getValue().getId());
    }

    @Test
    void createPreservesGoalAndDatesAndAllowsDuplicateNames() {
        when(repository.insertForOwner(eq(OWNER_ID), any(Plan.class))).thenReturn(1L, 2L);
        LocalDate date = LocalDate.of(2026, 11, 1);
        assertEquals(1L, service.create(OWNER_ID, "TOEIC", "730+", date, date));
        assertEquals(2L, service.create(OWNER_ID, "TOEIC", "730+", date, date));
        ArgumentCaptor<Plan> captured = ArgumentCaptor.forClass(Plan.class);
        verify(repository, times(2)).insertForOwner(eq(OWNER_ID), captured.capture());
        assertEquals("730+", captured.getValue().getGoal());
        assertEquals(date, captured.getValue().getStartDate());
        assertEquals(date, captured.getValue().getEndDate());
    }

    @Test
    void invalidInputNeverReachesInsert() {
        LocalDate date = LocalDate.of(2026, 11, 1);
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER_ID, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER_ID, "  ", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER_ID, "a".repeat(256), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(OWNER_ID, "TOEIC", null, date, date.minusDays(1)));
        verifyNoInteractions(repository);
    }

    @Test
    void updatePreservesCreationTimeAndValidatesBeforeWriting() {
        OffsetDateTime created = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        Plan plan = new Plan(1L, "Cũ", null, null, null, created);
        when(repository.findByIdForOwner(OWNER_ID, 1L)).thenReturn(Optional.of(plan));
        when(repository.updateForOwner(OWNER_ID, plan)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.update(OWNER_ID, 1L, " ", null, null, null));
        verify(repository, never()).updateForOwner(eq(OWNER_ID), any());
        service.update(OWNER_ID, 1L, "Mới", "Goal", null, null);
        verify(repository).updateForOwner(OWNER_ID, plan);
        assertEquals("Mới", plan.getTitle());
        assertEquals("Goal", plan.getGoal());
        assertEquals(created, plan.getCreatedAt());
    }

    @Test
    void missingOrInvalidIdsReturn404() {
        when(repository.findByIdForOwner(OWNER_ID, 99L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getById(OWNER_ID, 99L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getById(OWNER_ID, 0L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.update(OWNER_ID, 99L, "Tên", null, null, null)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.delete(OWNER_ID, 99L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.delete(OWNER_ID, -1L)).getStatusCode().value());
        verify(repository, never()).findByIdForOwner(OWNER_ID, 0L);
        verify(repository, never()).deleteByIdForOwner(OWNER_ID, -1L);
    }

    @Test
    void deletionBetweenReadAndUpdateStillReturns404() {
        Plan plan = new Plan("Tên");
        when(repository.findByIdForOwner(OWNER_ID, 1L)).thenReturn(Optional.of(plan));
        when(repository.updateForOwner(OWNER_ID, plan)).thenReturn(false);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.update(OWNER_ID, 1L, "Mới", null, null, null)).getStatusCode().value());
    }

    @Test
    void deleteUsesRepositoryAndDatabaseErrorsAreNotHidden() {
        when(locks.lockPlanForOwner(OWNER_ID, 1L)).thenReturn(true);
        when(repository.deleteByIdForOwner(OWNER_ID, 1L)).thenReturn(true);
        service.delete(OWNER_ID, 1L);
        verify(repository).deleteByIdForOwner(OWNER_ID, 1L);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("DB unavailable");
        when(repository.insertForOwner(eq(OWNER_ID), any(Plan.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.create(OWNER_ID, "Tên", null, null, null)));
    }

    @Test
    void deleteLocksSamePlanBeforeDelete() {
        when(locks.lockPlanForOwner(OWNER_ID, 7L)).thenReturn(true);
        when(repository.deleteByIdForOwner(OWNER_ID, 7L)).thenReturn(true);
        service.delete(OWNER_ID, 7L);
        var order = inOrder(locks, repository);
        order.verify(locks).lockPlanForOwner(OWNER_ID, 7L);
        order.verify(repository).deleteByIdForOwner(OWNER_ID, 7L);
        order.verifyNoMoreInteractions();
    }

    @Test
    void missingPlanAtLockNeverDeletesAndInvalidIdNeverLocks() {
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.delete(OWNER_ID, 7L)).getStatusCode().value());
        verify(repository, never()).deleteByIdForOwner(eq(OWNER_ID), anyLong());
        assertThrows(ResponseStatusException.class, () -> service.delete(OWNER_ID, 0L));
        verify(locks, never()).lockPlanForOwner(OWNER_ID, 0L);
    }

    @Test
    void missingPlanAfterLockStillReturns404() {
        when(locks.lockPlanForOwner(OWNER_ID, 7L)).thenReturn(true);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.delete(OWNER_ID, 7L)).getStatusCode().value());
        var order = inOrder(locks, repository);
        order.verify(locks).lockPlanForOwner(OWNER_ID, 7L);
        order.verify(repository).deleteByIdForOwner(OWNER_ID, 7L);
    }

    @Test
    void lockFailureIsPropagatedWithoutDeleting() {
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(locks.lockPlanForOwner(OWNER_ID, 7L)).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.delete(OWNER_ID, 7L)));
        verifyNoInteractions(repository);
    }

    @Test
    void transactionalProxyBeginsBeforeLockAndCommitsOrRollsBack() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(interceptor);
        PlanService proxy = (PlanService) factory.getProxy();
        when(locks.lockPlanForOwner(OWNER_ID, 7L)).thenReturn(true);
        when(repository.deleteByIdForOwner(OWNER_ID, 7L)).thenReturn(true);
        proxy.delete(OWNER_ID, 7L);
        var order = inOrder(manager, locks, repository);
        order.verify(manager).getTransaction(any());
        order.verify(locks).lockPlanForOwner(OWNER_ID, 7L);
        order.verify(repository).deleteByIdForOwner(OWNER_ID, 7L);
        order.verify(manager).commit(status);
        assertThrows(ResponseStatusException.class, () -> proxy.delete(OWNER_ID, 8L));
        verify(manager).rollback(status);
    }
}
