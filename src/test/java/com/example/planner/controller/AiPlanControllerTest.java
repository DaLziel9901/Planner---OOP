package com.example.planner.controller;

import com.example.planner.ai.GeneratedPlanService;
import com.example.planner.ai.PlanDraftService;
import com.example.planner.security.PlannerPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiPlanControllerTest {
    private static final String JSON = """
            {"title":"Original","goal":null,"startDate":null,"endDate":null,"subPlans":[],"tasks":[
              {"title":"Practice","description":null,"startDate":null,"endDate":null,"subTasks":[
                {"title":"Read exercise 1","description":null,"scheduledDate":null}
              ]}
            ]}
            """;
    private final GeneratedPlanService writer = mock(GeneratedPlanService.class);
    private final AiPlanController controller = new AiPlanController(
            new PlanDraftService((instructions, input) -> JSON, new JsonMapper()), writer);
    private final PlannerPrincipal alice = new PlannerPrincipal(10, "alice", "");
    private final PlannerPrincipal bob = new PlannerPrincipal(20, "bob", "");

    @Test
    void previewAndEditNeverWriteAndOnlyOwnerCanConfirmCurrentDraftOnce() {
        var session = new MockHttpSession();
        var preview = new ExtendedModelMap();
        assertEquals("ai/preview", controller.generate(alice, session, preview, "Goal", "Beginner", 7, ""));
        var firstId = (UUID) preview.get("draftId");
        var edited = new ExtendedModelMap();
        assertEquals("ai/preview", controller.edit(alice, session, edited, firstId, JSON.replace("Original", "Edited")));
        var editedId = (UUID) edited.get("draftId");
        assertNotEquals(firstId, editedId);

        assertThrows(ResponseStatusException.class, () -> controller.confirm(bob, session, editedId));
        assertThrows(ResponseStatusException.class, () -> controller.confirm(alice, session, firstId));
        verifyNoInteractions(writer);

        String editedJson = (String) edited.get("draftJson");
        when(writer.confirm(10, editedJson)).thenReturn(36L);
        assertEquals("redirect:/plans/36", controller.confirm(alice, session, editedId));
        assertThrows(ResponseStatusException.class, () -> controller.confirm(alice, session, editedId));
        verify(writer).confirm(10, editedJson);
    }

    @Test
    void failedConfirmationKeepsDraftForRetry() {
        var session = new MockHttpSession();
        var preview = new ExtendedModelMap();
        controller.generate(alice, session, preview, "Goal", "Beginner", 7, "");
        var draftId = (UUID) preview.get("draftId");
        String json = (String) preview.get("draftJson");
        when(writer.confirm(10, json)).thenThrow(new IllegalStateException("Transaction failed")).thenReturn(36L);

        assertThrows(IllegalStateException.class, () -> controller.confirm(alice, session, draftId));
        assertEquals("redirect:/plans/36", controller.confirm(alice, session, draftId));
    }
}
