package com.example.planner.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FlatPlanTreeBuilderTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private final PlanDraftService validator = new PlanDraftService((system, user) -> {
        throw new AssertionError("Unexpected provider call");
    }, MAPPER);

    @Test
    void buildsOutOfOrderNodesAndPreservesSiblingOrderAndDates() {
        var result = parse(draft(List.of(node(5, "3", "SubTask", "First leaf"),
                node(1, "null", "Plan", "Root"), node(2, "1", "SubPlan", "Phase"),
                node(3, "2", "Task", "Work"), node(4, "3", "SubTask", "Second leaf"))));
        var leaves = result.subPlans().get(0).tasks().get(0).subTasks();
        assertEquals(List.of("First leaf", "Second leaf"), leaves.stream().map(PlanDraft.SubTaskDraft::title).toList());
        assertEquals("2026-11-28", leaves.get(0).scheduledDate().toString());
        assertEquals("Details", result.goal());
    }

    @Test
    void rejectsInvalidIdsRootsRelationshipsAndCycles() {
        var root = node(1, "null", "Plan", "Root");
        for (var invalid : List.of(
                List.of(root, node(1, "1", "Task", "Duplicate")),
                List.of(root, node(2, "99", "Task", "Missing parent")),
                List.of(root, node(2, "3", "SubPlan", "Cycle"), node(3, "2", "SubPlan", "Cycle")),
                List.of(root, node(2, "null", "SubPlan", "Disconnected")),
                List.of(root, node(2, "1", "SubTask", "Wrong type")),
                List.of(root, node(2, "null", "Plan", "Second root")),
                List.of(node(2, "1", "Plan", "Parent on root")),
                List.of(node(2, "null", "Task", "Missing root")),
                List.of(root, node(0, "1", "Task", "Bad ID")))) {
            assertThrows(IllegalArgumentException.class, () -> parse(draft(invalid)));
        }
    }

    @Test
    void enforcesDepthAndNodeLimits() {
        assertDoesNotThrow(() -> parse(chain(5)));
        assertThrows(IllegalArgumentException.class, () -> parse(chain(6)));
        assertDoesNotThrow(() -> parse(wideTree(198)));
        assertThrows(IllegalArgumentException.class, () -> parse(wideTree(199)));
    }

    @Test
    void retainsBackendValidationAndRejectsExtraOrDuplicateFields() {
        var root = node(1, "null", "Plan", "Root");
        var task = node(2, "1", "Task", "Work");
        var leaf = node(3, "2", "SubTask", "Leaf");
        assertThrows(IllegalArgumentException.class, () -> parse(draft(List.of(root, task))));
        assertThrows(IllegalArgumentException.class,
                () -> parse(draft(List.of(root, task, leaf.replace("2026-11-28", "2026-02-30")))));
        assertThrows(IllegalArgumentException.class,
                () -> parse(draft(List.of(root, task, leaf.replace("\"endDate\":null", "\"endDate\":\"2026-12-01\"")))));
        assertThrows(IllegalArgumentException.class,
                () -> parse(draft(List.of(root, task, leaf.replace("\"id\":3", "\"id\":3,\"id\":4")))));
        assertThrows(IllegalArgumentException.class,
                () -> parse(draft(List.of(root, task, leaf.replace("\"id\":3", "\"id\":3,\"ownerId\":4")))));
    }

    private PlanDraft parse(String json) {
        return validator.parse(FlatPlanTreeBuilder.toNestedJson(MAPPER, json));
    }

    private static String chain(int depth) {
        var nodes = new ArrayList<String>();
        nodes.add(node(1, "null", "Plan", "Root"));
        for (int i = 1; i <= depth; i++) {
            nodes.add(node(i + 1, Integer.toString(i), "SubPlan", "Phase"));
        }
        nodes.add(node(20, Integer.toString(depth + 1), "Task", "Work"));
        nodes.add(node(21, "20", "SubTask", "Leaf"));
        return draft(nodes);
    }

    private static String wideTree(int leaves) {
        var nodes = new ArrayList<>(List.of(node(1, "null", "Plan", "Root"), node(2, "1", "Task", "Work")));
        IntStream.range(3, 3 + leaves).forEach(id -> nodes.add(node(id, "2", "SubTask", "Leaf")));
        return draft(nodes);
    }

    static String node(int id, String parent, String type, String title) {
        return """
                {"id":%d,"parentId":%s,"type":"%s","title":"%s","details":"Details","date":"2026-11-28","endDate":null}
                """.formatted(id, parent, type, title);
    }

    static String draft(List<String> nodes) {
        return "{\"nodes\":[" + String.join(",", nodes) + "]}";
    }
}
