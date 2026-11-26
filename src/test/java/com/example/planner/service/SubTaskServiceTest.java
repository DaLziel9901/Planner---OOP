package com.example.planner.service;

import com.example.planner.model.SubTask;
import com.example.planner.repository.SubTaskRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubTaskServiceTest {
    private SubTaskRepository repository;
    private TaskService tasks;
    private SubTaskService service;
    private final LocalDate date=LocalDate.of(2026,11,16);
    @BeforeEach void setUp() { repository=mock(SubTaskRepository.class); tasks=mock(TaskService.class); service=new SubTaskService(repository,tasks); }
    private SubTask saved() { return new SubTask(9L,7L,"Cũ","",null,true,OffsetDateTime.parse("2026-01-02T00:00:00Z"),0,OffsetDateTime.parse("2026-01-01T00:00:00Z")); }
    private void present() { when(repository.findById(9)).thenReturn(Optional.of(saved())); }
    private void assert404(org.junit.jupiter.api.function.Executable call) { assertEquals(404,assertThrows(ResponseStatusException.class,call).getStatusCode().value()); }

    @Test void listChecksTaskAndReadReturnsLeaf() {
        SubTask leaf=saved(); when(repository.findByTaskId(7)).thenReturn(List.of(leaf)); when(repository.findById(9)).thenReturn(Optional.of(leaf));
        assertEquals(List.of(leaf),service.findByTaskId(7)); verify(tasks).getById(7); assertSame(leaf,service.getById(9));
    }
    @Test void createAllowsOptionalDatesUnicodeDuplicatesAndDoesNotComplete() {
        when(repository.insert(any())).thenReturn(1L,2L,3L);
        assertEquals(1,service.create(7,"😀".repeat(255),"",null,0));
        assertEquals(2,service.create(7,"😀".repeat(255),null,date.minusDays(9),0));
        assertEquals(3,service.create(7,"😀","",date.plusDays(9),2));
        var captured=ArgumentCaptor.forClass(SubTask.class); verify(repository,times(3)).insert(captured.capture());
        assertEquals(7L,captured.getValue().getTaskId()); assertFalse(captured.getValue().isDone()); assertNull(captured.getValue().getCompletedAt());
        assertEquals(date.plusDays(9),captured.getValue().getScheduledDate()); verify(tasks,times(3)).lockForWrite(7);
    }
    @Test void invalidInputNeverLocksOrInserts() {
        for (String title:new String[]{null,"","\u2003","😀".repeat(256)})
            assertThrows(IllegalArgumentException.class,()->service.create(7,title,"",null,0));
        assertThrows(IllegalArgumentException.class,()->service.create(7,"Tên","",null,-1));
        assert404(()->service.create(0,"Tên","",null,0)); verifyNoInteractions(repository,tasks);
    }
    @Test void updateLocksTaskRereadsAndPreservesParentStateAndCreationTime() {
        SubTask old=saved(); when(repository.findById(9)).thenReturn(Optional.of(old)); when(repository.update(any())).thenReturn(true);
        service.update(9,"Mới","",date,2);
        var captured=ArgumentCaptor.forClass(SubTask.class); var order=inOrder(repository,tasks);
        order.verify(repository).findById(9); order.verify(tasks).lockForWrite(7); order.verify(repository).findById(9); order.verify(repository).update(captured.capture());
        SubTask updated=captured.getValue(); assertEquals(7L,updated.getTaskId()); assertEquals(old.getCreatedAt(),updated.getCreatedAt());
        assertTrue(updated.isDone()); assertEquals(old.getCompletedAt(),updated.getCompletedAt()); assertEquals("Mới",updated.getTitle());
        assertEquals("Cũ",old.getTitle());
    }
    @Test void deleteRereadsUnderTaskLockAndReturnsStoredTaskId() {
        present(); when(repository.deleteById(9)).thenReturn(true); assertEquals(7,service.delete(9));
        var order=inOrder(repository,tasks); order.verify(repository).findById(9); order.verify(tasks).lockForWrite(7);
        order.verify(repository).findById(9); order.verify(repository).deleteById(9);
    }
    @Test void missingIdsParentOrLeafAfterLockNeverWrite() {
        assert404(()->service.getById(0)); assert404(()->service.getById(99)); assert404(()->service.delete(99));
        present(); doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(tasks).lockForWrite(7);
        assert404(()->service.create(7,"Tên","",null,0)); assert404(()->service.update(9,"Tên","",null,0));
        reset(tasks); when(repository.findById(9)).thenReturn(Optional.of(saved()),Optional.empty());
        assert404(()->service.delete(9)); verify(repository,never()).insert(any()); verify(repository,never()).update(any()); verify(repository,never()).deleteById(anyLong());
    }
    @Test void changedTaskAfterLockIsRejectedAndInvalidUpdateLeavesObjectUntouched() {
        SubTask moved=saved(); moved.setTaskId(8L); when(repository.findById(9)).thenReturn(Optional.of(saved()),Optional.of(moved));
        assert404(()->service.update(9,"Tên","",null,0));
        SubTask old=saved(); when(repository.findById(9)).thenReturn(Optional.of(old));
        assertThrows(IllegalArgumentException.class,()->service.update(9,"Tên","",null,-1));
        assertEquals("Cũ",old.getTitle()); verify(repository,never()).update(any());
    }
    @Test void zeroRowsAndSqlErrorsAreNotSuccess() {
        present(); assert404(()->service.update(9,"Tên","",null,0)); assert404(()->service.delete(9));
        var failure=new DataAccessResourceFailureException("DB unavailable"); doThrow(failure).when(tasks).lockForWrite(7);
        assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->service.delete(9)));
    }
    @Test void springProxyStartsTransactionBeforeTaskGateAndRollsBackFailure() {
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status=mock(org.springframework.transaction.TransactionStatus.class); when(manager.getTransaction(any())).thenReturn(status);
        var advice=new org.springframework.transaction.interceptor.TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory=new org.springframework.aop.framework.ProxyFactory(service); factory.addAdvice(advice);
        SubTaskService proxy=(SubTaskService)factory.getProxy(); when(repository.insert(any())).thenReturn(10L);
        assertEquals(10,proxy.create(7,"Tên","",null,0));
        var order=inOrder(manager,tasks,repository); order.verify(manager).getTransaction(any()); order.verify(tasks).lockForWrite(7);
        order.verify(repository).insert(any()); order.verify(manager).commit(status);
        assert404(()->proxy.delete(99)); verify(manager).rollback(status);
        present();
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(repository.updateCompletion(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> proxy.setCompletion(9, false)));
        verify(manager, times(2)).rollback(status);
        verify(manager).commit(status);
    }

    @Test void completionUsesStateRereadUnderLockAndPreservesFirstTimestamp() {
        SubTask before = new SubTask(9L, 7L, "Lá", null, null, false, null, 0, OffsetDateTime.now());
        SubTask current = saved();
        OffsetDateTime first = current.getCompletedAt();
        when(repository.findById(9)).thenReturn(Optional.of(before), Optional.of(current));
        when(repository.updateCompletion(any())).thenReturn(true);

        assertEquals(7L, service.setCompletion(9, true));
        var order = inOrder(repository, tasks);
        order.verify(repository).findById(9);
        order.verify(tasks).lockForWrite(7);
        order.verify(repository).findById(9);
        order.verify(repository).updateCompletion(current);
        assertEquals(first, current.getCompletedAt());

        service.setCompletion(9, false);
        assertFalse(current.isDone());
        assertNull(current.getCompletedAt());
        service.setCompletion(9, true);
        assertTrue(current.isDone());
        assertNotNull(current.getCompletedAt());
    }

    @Test void completionReturns404WhenLeafDisappearsOrUpdateMatchesNoRow() {
        when(repository.findById(9)).thenReturn(Optional.of(saved()), Optional.empty());
        assert404(() -> service.setCompletion(9, true));
        verify(repository, never()).updateCompletion(any());
        present();
        assert404(() -> service.setCompletion(9, false));
    }
}
