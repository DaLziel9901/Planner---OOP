package com.example.planner.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class PlanDraftServiceTest {
    private static final String LEAF = """
            {"title":"Read exercise 1","description":null,"scheduledDate":"2026-11-27"}
            """;
    private static final String TASK = """
            {"title":"Practice grammar","description":null,"startDate":null,"endDate":null,"subTasks":[%s]}
            """.formatted(LEAF);
    private static final String PLAN = """
            {"title":"Learn English","goal":null,"startDate":null,"endDate":null,"subPlans":%s,"tasks":%s}
            """;

    @Test
    void acceptsDirectTasksAndSendsOnlyExplicitPlanningInputs() {
        var mapper = new JsonMapper();
        var service = new PlanDraftService((instructions, input) -> {
            var fields = mapper.readTree(input);
            assertEquals(4, fields.size());
            assertEquals("English", fields.path("goal").asString());
            assertEquals(30, fields.path("durationDays").asInt());
            assertTrue(instructions.contains("five levels"));
            return PLAN.formatted("[]", "[" + TASK + "]");
        }, mapper);

        var draft = service.generate("English", "Beginner", 30, "15 min daily");
        assertEquals("Learn English", draft.title());
        assertEquals(LocalDate.of(2026, 11, 27), draft.tasks().get(0).subTasks().get(0).scheduledDate());
        assertNull(draft.startDate());
    }

    @Test
    void acceptsFiveSubPlanLevelsAndRejectsSix() {
        assertDoesNotThrow(() -> offline().parse(PLAN.formatted("[" + nested(5) + "]", "[]")));
        assertThrows(IllegalArgumentException.class,
                () -> offline().parse(PLAN.formatted("[" + nested(6) + "]", "[]")));
    }

    @Test
    void rejectsEmptyTasksAndInvalidDatesOrExtraData() {
        var service = offline();
        String valid = PLAN.formatted("[]", "[" + TASK + "]");
        assertThrows(IllegalArgumentException.class, () -> service.parse(PLAN.formatted("[]", "[]")));
        assertThrows(IllegalArgumentException.class, () -> service.parse(valid.replace(LEAF, "")));
        assertThrows(IllegalArgumentException.class, () -> service.parse(valid.replace("2026-11-27", "2026-02-30")));
        assertThrows(IllegalArgumentException.class, () -> service.parse(valid.replace("2026-11-27", "26-11-27")));
        assertThrows(IllegalArgumentException.class, () -> service.parse(valid + "{}"));
        assertThrows(IllegalArgumentException.class, () -> service.parse(valid.replace("\"goal\":null", "\"ownerId\":1")));
        assertThrows(IllegalArgumentException.class, () -> service.parse(valid.replace("\"goal\":null", "\"goal\":null,\"goal\":null")));
    }

    @Test
    void rejectsInvalidInputBeforeProvider() {
        assertThrows(IllegalArgumentException.class, () -> offline().generate("goal", "level", 0, ""));
    }

    private static String nested(int depth) {
        return """
                {"title":"Phase","description":null,"startDate":null,"endDate":null,"subPlans":%s,"tasks":%s}
                """.formatted(depth == 1 ? "[]" : "[" + nested(depth - 1) + "]",
                depth == 1 ? "[" + TASK + "]" : "[]");
    }

    private static PlanDraftService offline() {
        return new PlanDraftService((system, input) -> { throw new AssertionError("Unexpected provider call"); },
                new JsonMapper());
    }
}
