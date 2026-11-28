package com.example.planner.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PlanDraftSchema {
    private static final Map<String, Object> TITLE = Map.of("type", "string");
    private static final Map<String, Object> DETAILS = Map.of("type", List.of("string", "null"));
    private static final Map<String, Object> DATE = Map.of("type", List.of("string", "null"),
            "description", "Calendar date in YYYY-MM-DD format or null.");

    static final Map<String, Object> RESPONSE_FORMAT = Map.of(
            "type", "json_schema",
            "json_schema", Map.of("name", "planner_plan_draft", "strict", true, "schema", schema()));

    private PlanDraftSchema() {}

    private static Map<String, Object> schema() {
        // Use an explicit recursive reference. PlanDraftService enforces depth and aggregate node limits.
        var subPlans = arrayOf("subPlan");
        var tasks = arrayOf("task");
        var root = new LinkedHashMap<>(object(Map.of("title", TITLE, "goal", DETAILS,
                "startDate", DATE, "endDate", DATE, "subPlans", subPlans, "tasks", tasks)));
        root.put("$defs", Map.of(
                "subPlan", object(Map.of("title", TITLE, "description", DETAILS,
                        "startDate", DATE, "endDate", DATE, "subPlans", subPlans, "tasks", tasks)),
                "task", object(Map.of("title", TITLE, "description", DETAILS,
                        "startDate", DATE, "endDate", DATE, "subTasks", arrayOf("subTask"))),
                "subTask", object(Map.of("title", TITLE, "description", DETAILS, "scheduledDate", DATE))));
        return Map.copyOf(root);
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }

    private static Map<String, Object> arrayOf(String definition) {
        return Map.of("type", "array", "items", Map.of("$ref", "#/$defs/" + definition));
    }
}
