package com.example.planner.service;

import com.example.planner.model.Plan;
import com.example.planner.repository.PlanRepository;
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
    private PlanRepository repository;
    private PlanService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlanRepository.class);
        service = new PlanService(repository);
    }

    @Test
    void listAndReadReturnRepositoryData() {
        Plan plan = new Plan("TOEIC");
        when(repository.findAll()).thenReturn(List.of(plan));
        when(repository.findById(1L)).thenReturn(Optional.of(plan));
        assertEquals(List.of(plan), service.findAll());
        assertSame(plan, service.getById(1L));
    }

    @Test
    void createReturnsGeneratedIdAndAllowsOptionalFields() {
        when(repository.insert(any(Plan.class))).thenReturn(12L);
        assertEquals(12L, service.create("TOEIC", "  ", null, null));
        ArgumentCaptor<Plan> captured = ArgumentCaptor.forClass(Plan.class);
        verify(repository).insert(captured.capture());
        assertEquals("TOEIC", captured.getValue().getTitle());
        assertNull(captured.getValue().getGoal());
        assertNull(captured.getValue().getStartDate());
        assertNull(captured.getValue().getEndDate());
        assertNull(captured.getValue().getId());
    }

    @Test
    void createPreservesGoalAndDatesAndAllowsDuplicateNames() {
        when(repository.insert(any(Plan.class))).thenReturn(1L, 2L);
        LocalDate date = LocalDate.of(2026, 11, 1);
        assertEquals(1L, service.create("TOEIC", "730+", date, date));
        assertEquals(2L, service.create("TOEIC", "730+", date, date));
        ArgumentCaptor<Plan> captured = ArgumentCaptor.forClass(Plan.class);
        verify(repository, times(2)).insert(captured.capture());
        assertEquals("730+", captured.getValue().getGoal());
        assertEquals(date, captured.getValue().getStartDate());
        assertEquals(date, captured.getValue().getEndDate());
    }

    @Test
    void invalidInputNeverReachesInsert() {
        LocalDate date = LocalDate.of(2026, 11, 1);
        assertThrows(IllegalArgumentException.class, () -> service.create(null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create("  ", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create("a".repeat(256), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create("TOEIC", null, date, date.minusDays(1)));
        verifyNoInteractions(repository);
    }

    @Test
    void updatePreservesCreationTimeAndValidatesBeforeWriting() {
        OffsetDateTime created = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        Plan plan = new Plan(1L, "Cũ", null, null, null, created);
        when(repository.findById(1L)).thenReturn(Optional.of(plan));
        when(repository.update(plan)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.update(1L, " ", null, null, null));
        verify(repository, never()).update(any());
        service.update(1L, "Mới", "Goal", null, null);
        verify(repository).update(plan);
        assertEquals("Mới", plan.getTitle());
        assertEquals("Goal", plan.getGoal());
        assertEquals(created, plan.getCreatedAt());
    }

    @Test
    void missingOrInvalidIdsReturn404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getById(99L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getById(0L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.update(99L, "Tên", null, null, null)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.delete(99L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.delete(-1L)).getStatusCode().value());
        verify(repository, never()).findById(0L);
        verify(repository, never()).deleteById(-1L);
    }

    @Test
    void deletionBetweenReadAndUpdateStillReturns404() {
        Plan plan = new Plan("Tên");
        when(repository.findById(1L)).thenReturn(Optional.of(plan));
        when(repository.update(plan)).thenReturn(false);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.update(1L, "Mới", null, null, null)).getStatusCode().value());
    }

    @Test
    void deleteUsesRepositoryAndDatabaseErrorsAreNotHidden() {
        when(repository.deleteById(1L)).thenReturn(true);
        service.delete(1L);
        verify(repository).deleteById(1L);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("DB unavailable");
        when(repository.insert(any(Plan.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.create("Tên", null, null, null)));
    }
}
