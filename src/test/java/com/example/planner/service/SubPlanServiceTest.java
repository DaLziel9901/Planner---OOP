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
    private static final long OWNER_ID = 42L;
    private SubPlanRepository repository;
    private PlanRepository plans;
    private SubPlanService service;
    private final OffsetDateTime created = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        repository = mock(SubPlanRepository.class);
        plans = mock(PlanRepository.class);
        service = new SubPlanService(repository, plans);
        when(repository.lockPlanForOwner(OWNER_ID, 1L)).thenReturn(true);
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(List.of());
        when(plans.findByIdForOwner(OWNER_ID, 1L)).thenReturn(Optional.of(new Plan(1L, "Plan", null, null, null, created)));
    }

    private SubPlan node(long id, Long parent) {
        return new SubPlan(id, 1L, parent, "Giai đoạn", null, null, null, 0, created);
    }

    @Test
    void movingBranchIncludesGrandchildrenAtDepthBoundary() {
        SubPlan moved = node(10, null);
        when(repository.findByIdForOwner(OWNER_ID, 10L)).thenReturn(Optional.of(moved));
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(List.of(
                node(1, null), node(2, 1L), node(3, 2L),
                moved, node(11, 10L), node(12, 11L)));
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(OWNER_ID, 10L, 3L, "Tầng 6 ở cháu", null, null, null, 0));
        verify(repository, never()).updateForOwner(eq(OWNER_ID), any());
        when(repository.updateForOwner(eq(OWNER_ID), any())).thenReturn(true);
        service.update(OWNER_ID, 10L, 2L, "Cháu ở tầng 5", null, null, null, 0);
        verify(repository).updateForOwner(eq(OWNER_ID), argThat(value -> Long.valueOf(2L).equals(value.getParentSubPlanId())));
    }

    @Test
    void directChildrenExcludeGrandchildrenAndOtherRoots() {
        SubPlan root = node(1, null);
        SubPlan child = node(2, 1L);
        var all = List.of(root, child, node(3, 2L), node(4, null));
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(all);
        assertEquals(all, service.findByPlanId(OWNER_ID, 1L));
        assertEquals(List.of(child), service.getDirectChildren(OWNER_ID, root));
    }

    @Test
    void rootCreationAllowsDuplicateNamesAndOptionalFields() {
        when(repository.insertForOwner(eq(OWNER_ID), any())).thenReturn(Optional.of(10L), Optional.of(11L));
        assertEquals(10L, service.create(OWNER_ID, 1L, null, "Giai đoạn", " ", null, null, 0));
        assertEquals(11L, service.create(OWNER_ID, 1L, null, "Giai đoạn", null, null, null, 0));
        ArgumentCaptor<SubPlan> captured = ArgumentCaptor.forClass(SubPlan.class);
        verify(repository, times(2)).insertForOwner(eq(OWNER_ID), captured.capture());
        assertNull(captured.getValue().getDescription());
        assertNull(captured.getValue().getId());
        assertEquals(1L, captured.getValue().getPlanId());
    }

    @Test
    void fifthLevelAllowedButSixthRejected() {
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(List.of(node(1, null), node(2, 1L), node(3, 2L), node(4, 3L)));
        when(repository.insertForOwner(eq(OWNER_ID), any())).thenReturn(Optional.of(5L));
        assertEquals(5L, service.create(OWNER_ID, 1L, 4L, "Tầng 5", null, null, null, 0));
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(List.of(node(1, null), node(2, 1L), node(3, 2L), node(4, 3L), node(5, 4L)));
        assertThrows(SubPlanService.InputException.class,
                () -> service.create(OWNER_ID, 1L, 5L, "Tầng 6", null, null, null, 0));
        verify(repository, times(1)).insertForOwner(eq(OWNER_ID), any());
    }

    @Test
    void parentMustExistInSamePlan() {
        assertThrows(SubPlanService.InputException.class,
                () -> service.create(OWNER_ID, 1L, 99L, "Sai cha", null, null, null, 0));
        verify(repository, never()).insertForOwner(eq(OWNER_ID), any());
    }

    @Test
    void selfParentAndDescendantParentAreRejected() {
        SubPlan root = node(1, null);
        when(repository.findByIdForOwner(OWNER_ID, 1L)).thenReturn(Optional.of(root));
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(List.of(root, node(2, 1L), node(3, 2L)));
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(OWNER_ID, 1L, 1L, "Tên", null, null, null, 0));
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(OWNER_ID, 1L, 3L, "Tên", null, null, null, 0));
        verify(repository, never()).updateForOwner(eq(OWNER_ID), any());
    }

    @Test
    void movingBranchChecksDepthOfDescendantsNotOnlyMovedNode() {
        SubPlan moved = node(10, null);
        when(repository.findByIdForOwner(OWNER_ID, 10L)).thenReturn(Optional.of(moved));
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(List.of(
                node(1, null), node(2, 1L), node(3, 2L), node(4, 3L), moved, node(11, 10L)));
        // Nút 10 sẽ ở tầng 5 nhưng nút 11 bị đẩy xuống tầng 6.
        assertThrows(SubPlanService.InputException.class,
                () -> service.update(OWNER_ID, 10L, 4L, "Chuyển", null, null, null, 0));
        verify(repository, never()).updateForOwner(eq(OWNER_ID), any());
    }

    @Test
    void validMoveAndReturningToRootPreservePlanAndCreationTime() {
        SubPlan moved = node(2, 1L);
        when(repository.findByIdForOwner(OWNER_ID, 2L)).thenReturn(Optional.of(moved));
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(List.of(node(1, null), moved, node(3, null)));
        when(repository.updateForOwner(eq(OWNER_ID), any())).thenReturn(true);
        service.update(OWNER_ID, 2L, 3L, "Mới", "Mô tả", null, null, 2);
        service.update(OWNER_ID, 2L, null, "Mới", null, null, null, 0);
        ArgumentCaptor<SubPlan> captured = ArgumentCaptor.forClass(SubPlan.class);
        verify(repository, times(2)).updateForOwner(eq(OWNER_ID), captured.capture());
        assertEquals(3L, captured.getAllValues().get(0).getParentSubPlanId());
        assertNull(captured.getValue().getParentSubPlanId());
        assertEquals(created, captured.getValue().getCreatedAt());
        assertEquals(1L, captured.getValue().getPlanId());
    }

    @Test
    void invalidTitleDatesAndOrderNeverWrite() {
        LocalDate date = LocalDate.of(2026, 11, 2);
        assertThrows(SubPlanService.InputException.class, () -> service.create(OWNER_ID, 1L, null, " ", null, null, null, 0));
        assertThrows(SubPlanService.InputException.class, () -> service.create(OWNER_ID, 1L, null, "a".repeat(256), null, null, null, 0));
        assertThrows(SubPlanService.InputException.class, () -> service.create(OWNER_ID, 1L, null, "Tên", null, date, date.minusDays(1), 0));
        assertThrows(SubPlanService.InputException.class, () -> service.create(OWNER_ID, 1L, null, "Tên", null, null, null, -1));
        assertThrows(SubPlanService.InputException.class, () -> service.create(OWNER_ID, 1L, 0L, "Tên", null, null, null, 0));
        verify(repository, never()).insertForOwner(eq(OWNER_ID), any());
    }

    @Test
    void datesOutsideParentWarnButCanBeSaved() {
        LocalDate date = LocalDate.of(2026, 11, 2);
        Plan plan = new Plan(1L, "Plan", null, date, date.plusDays(2), created);
        when(plans.findByIdForOwner(OWNER_ID, 1L)).thenReturn(Optional.of(plan));
        when(repository.insertForOwner(eq(OWNER_ID), any())).thenReturn(Optional.of(10L));
        assertEquals(10L, service.create(OWNER_ID, 1L, null, "Ngoài khoảng", null, date.minusDays(2), null, 0));
        SubPlan root = node(10, null);
        root.setDates(date.minusDays(2), null);
        assertFalse(service.getDateWarnings(OWNER_ID, root).isEmpty());
        SubPlan child = node(11, 10L);
        child.setDates(date.minusDays(3), null);
        when(repository.findByIdForOwner(OWNER_ID, 10L)).thenReturn(Optional.of(root));
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(List.of(root, child));
        assertFalse(service.getDateWarnings(OWNER_ID, child).isEmpty());
        assertEquals(2, service.getDateWarnings(OWNER_ID, root).size());
        assertEquals(service.getDateWarnings(OWNER_ID, root),service.getDateWarnings(root,plan,List.of(root,child)));
    }

    @Test
    void missingIdsAndDeletionRaceAre404() {
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getById(OWNER_ID, 0)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getById(OWNER_ID, 99)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.create(OWNER_ID, 99L, null, "Tên", null, null, null, 0)).getStatusCode().value());
        when(repository.findByIdForOwner(OWNER_ID, 2L)).thenReturn(Optional.of(node(2, null)));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.update(OWNER_ID, 2L, null, "Tên", null, null, null, 0)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.delete(OWNER_ID, 2L)).getStatusCode().value());
    }

    @Test
    void deleteAndListDelegateAndDatabaseErrorsRemainVisible() {
        SubPlan root = node(2, null);
        when(repository.findByIdForOwner(OWNER_ID, 2L)).thenReturn(Optional.of(root));
        when(repository.deleteByIdForOwner(OWNER_ID, 2L)).thenReturn(true);
        assertEquals(1L, service.delete(OWNER_ID, 2L));
        when(repository.findByPlanIdForOwner(OWNER_ID, 1L)).thenReturn(List.of(root));
        assertEquals(List.of(root), service.findByPlanId(OWNER_ID, 1L));
        when(repository.insertForOwner(eq(OWNER_ID), any())).thenThrow(new DataAccessResourceFailureException("DB unavailable"));
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.create(OWNER_ID, 1L, null, "Tên", null, null, null, 0));
    }
}
