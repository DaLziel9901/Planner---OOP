package com.example.planner.service;

import com.example.planner.model.Plan;
import com.example.planner.model.SubPlan;
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

class SubPlanServiceTest {
    private SubPlanRepository repository;
    private PlanRepository plans;
    private SubPlanService service;
    private final OffsetDateTime created = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        repository = mock(SubPlanRepository.class);
        plans = mock(PlanRepository.class);
        service = new SubPlanService(repository, plans);
        when(repository.lockPlan(1L)).thenReturn(true);
        when(repository.findByPlanId(1L)).thenReturn(List.of());
        when(plans.findById(1L)).thenReturn(Optional.of(new Plan(1L, "Plan", null, null, null, created)));
    }

    private SubPlan node(long id, Long parent) {
        return new SubPlan(id, 1L, parent, "Giai đoạn", null, null, null, 0, created);
    }

    @Test
    void rootCreationAllowsDuplicateNamesAndOptionalFields() {
        when(repository.insert(any())).thenReturn(10L, 11L);
        assertEquals(10L, service.create(1L, null, "Giai đoạn", " ", null, null, 0));
        assertEquals(11L, service.create(1L, null, "Giai đoạn", null, null, null, 0));
        ArgumentCaptor<SubPlan> captured = ArgumentCaptor.forClass(SubPlan.class);
        verify(repository, times(2)).insert(captured.capture());
        assertNull(captured.getValue().getDescription());
        assertNull(captured.getValue().getId());
        assertEquals(1L, captured.getValue().getPlanId());
    }

    @Test
    void fifthLevelAllowedButSixthRejected() {
        when(repository.findByPlanId(1L)).thenReturn(List.of(node(1, null), node(2, 1L), node(3, 2L), node(4, 3L)));
        when(repository.insert(any())).thenReturn(5L);
        assertEquals(5L, service.create(1L, 4L, "Tầng 5", null, null, null, 0));
        when(repository.findByPlanId(1L)).thenReturn(List.of(node(1, null), node(2, 1L), node(3, 2L), node(4, 3L), node(5, 4L)));
        assertThrows(SubPlanService.InputException.class,
                () -> service.create(1L, 5L, "Tầng 6", null, null, null, 0));
        verify(repository, times(1)).insert(any());
    }

    @Test
    void parentMustExistInSamePlan() {
        assertThrows(SubPlanService.InputException.class,
                () -> service.create(1L, 99L, "Sai cha", null, null, null, 0));
        verify(repository, never()).insert(any());
    }

    @Test
    void selfParentAndDescendantParentAreRejected() {
        SubPlan root = node(1, null);
        when(repository.findById(1L)).thenReturn(Optional.of(root));
        when(repository.findByPlanId(1L)).thenReturn(List.of(root, node(2, 1L), node(3, 2L)));
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(1L, 1L, "Tên", null, null, null, 0));
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(1L, 3L, "Tên", null, null, null, 0));
        verify(repository, never()).update(any());
    }

    @Test
    void movingBranchChecksDepthOfDescendantsNotOnlyMovedNode() {
        SubPlan moved = node(10, null);
        when(repository.findById(10L)).thenReturn(Optional.of(moved));
        when(repository.findByPlanId(1L)).thenReturn(List.of(
                node(1, null), node(2, 1L), node(3, 2L), node(4, 3L), moved, node(11, 10L)));
        // Nút 10 sẽ ở tầng 5 nhưng nút 11 bị đẩy xuống tầng 6.
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(10L, 4L, "Chuyển", null, null, null, 0));
        verify(repository, never()).update(any());
    }

    @Test
    void validMoveAndReturningToRootPreservePlanAndCreationTime() {
        SubPlan moved = node(2, 1L);
        when(repository.findById(2L)).thenReturn(Optional.of(moved));
        when(repository.findByPlanId(1L)).thenReturn(List.of(node(1, null), moved, node(3, null)));
        when(repository.update(any())).thenReturn(true);
        service.update(2L, 3L, "Mới", "Mô tả", null, null, 2);
        service.update(2L, null, "Mới", null, null, null, 0);
        ArgumentCaptor<SubPlan> captured = ArgumentCaptor.forClass(SubPlan.class);
        verify(repository, times(2)).update(captured.capture());
        assertEquals(3L, captured.getAllValues().get(0).getParentSubPlanId());
        assertNull(captured.getValue().getParentSubPlanId());
        assertEquals(created, captured.getValue().getCreatedAt());
        assertEquals(1L, captured.getValue().getPlanId());
    }

    @Test
    void invalidTitleDatesAndOrderNeverWrite() {
        LocalDate date = LocalDate.of(2026, 11, 2);
        assertThrows(SubPlanService.InputException.class, () -> service.create(1L, null, " ", null, null, null, 0));
        assertThrows(SubPlanService.InputException.class, () -> service.create(1L, null, "a".repeat(256), null, null, null, 0));
        assertThrows(SubPlanService.InputException.class, () -> service.create(1L, null, "Tên", null, date, date.minusDays(1), 0));
        assertThrows(SubPlanService.InputException.class, () -> service.create(1L, null, "Tên", null, null, null, -1));
        assertThrows(SubPlanService.InputException.class, () -> service.create(1L, 0L, "Tên", null, null, null, 0));
        verify(repository, never()).insert(any());
    }

    @Test
    void datesOutsideParentWarnButCanBeSaved() {
        LocalDate date = LocalDate.of(2026, 11, 2);
        Plan plan = new Plan(1L, "Plan", null, date, date.plusDays(2), created);
        when(plans.findById(1L)).thenReturn(Optional.of(plan));
        when(repository.insert(any())).thenReturn(10L);
        assertEquals(10L, service.create(1L, null, "Ngoài khoảng", null, date.minusDays(2), null, 0));
        SubPlan root = node(10, null);
        root.setDates(date.minusDays(2), null);
        assertFalse(service.getDateWarnings(root).isEmpty());
        SubPlan child = node(11, 10L);
        child.setDates(date.minusDays(3), null);
        when(repository.findById(10L)).thenReturn(Optional.of(root));
        when(repository.findByPlanId(1L)).thenReturn(List.of(root, child));
        assertFalse(service.getDateWarnings(child).isEmpty());
        assertEquals(2, service.getDateWarnings(root).size());
    }

    @Test
    void missingIdsAndDeletionRaceAre404() {
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getById(0)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getById(99)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.create(99L, null, "Tên", null, null, null, 0)).getStatusCode().value());
        when(repository.findById(2L)).thenReturn(Optional.of(node(2, null)));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.update(2L, null, "Tên", null, null, null, 0)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.delete(2L)).getStatusCode().value());
    }

    @Test
    void deleteAndListDelegateAndDatabaseErrorsRemainVisible() {
        SubPlan root = node(2, null);
        when(repository.findById(2L)).thenReturn(Optional.of(root));
        when(repository.deleteById(2L)).thenReturn(true);
        assertEquals(1L, service.delete(2L));
        when(repository.findByPlanId(1L)).thenReturn(List.of(root));
        assertEquals(List.of(root), service.findByPlanId(1L));
        when(repository.insert(any())).thenThrow(new DataAccessResourceFailureException("DB unavailable"));
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.create(1L, null, "Tên", null, null, null, 0));
    }
}
