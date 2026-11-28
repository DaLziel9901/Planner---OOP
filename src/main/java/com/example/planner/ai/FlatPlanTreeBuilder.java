package com.example.planner.ai;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FlatPlanTreeBuilder {
    private static final Set<String> FIELDS = Set.of("id", "parentId", "type", "title", "details", "date", "endDate");

    private FlatPlanTreeBuilder() {}

    static String toNestedJson(ObjectMapper mapper, String json) {
        if (json.length() > PlanDraftService.MAX_JSON_CHARS) {
            throw invalid();
        }
        JsonNode draft;
        try {
            draft = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
        } catch (RuntimeException ignored) {
            throw invalid();
        }
        var nodes = draft.path("nodes");
        if (!draft.isObject() || draft.size() != 1 || !nodes.isArray() || nodes.isEmpty() || nodes.size() > 200) {
            throw invalid();
        }

        Map<Long, Node> byId = new LinkedHashMap<>();
        Node root = null;
        for (var value : nodes) {
            if (!value.isObject() || !FIELDS.equals(Set.copyOf(value.propertyNames()))
                    || !value.path("type").isTextual()) {
                throw invalid();
            }
            long id = id(value.path("id"));
            var parent = value.path("parentId");
            String type = value.path("type").asString();
            Node node = new Node(id, parent.isNull() ? null : id(parent), type, value, new ArrayList<>());
            if (byId.putIfAbsent(id, node) != null) {
                throw invalid();
            }
            if ("Plan".equals(type)) {
                if (root != null || node.parentId() != null) {
                    throw invalid();
                }
                root = node;
            }
        }
        if (root == null) {
            throw invalid();
        }

        for (Node node : byId.values()) {
            if (node == root) {
                continue;
            }
            Node parent = byId.get(node.parentId());
            if (parent == null || !allows(parent.type(), node.type())) {
                throw invalid();
            }
            parent.children().add(node);
        }
        for (Node node : byId.values()) {
            verifyPath(node, root, byId);
        }
        return mapper.writeValueAsString(nested(mapper, root));
    }

    private static long id(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw invalid();
        }
        return value.asLong();
    }

    private static boolean allows(String parent, String child) {
        return switch (child) {
            case "SubPlan", "Task" -> "Plan".equals(parent) || "SubPlan".equals(parent);
            case "SubTask" -> "Task".equals(parent);
            default -> false;
        };
    }

    private static void verifyPath(Node node, Node root, Map<Long, Node> byId) {
        var seen = new HashSet<Long>();
        int depth = 0;
        for (Node current = node; current != root; current = byId.get(current.parentId())) {
            if (current == null || !seen.add(current.id()) || ("SubPlan".equals(current.type()) && ++depth > 5)) {
                throw invalid();
            }
        }
    }

    private static ObjectNode nested(ObjectMapper mapper, Node node) {
        var out = mapper.createObjectNode();
        var value = node.value();
        out.set("title", value.path("title"));
        out.set("Plan".equals(node.type()) ? "goal" : "description", value.path("details"));
        if ("SubTask".equals(node.type())) {
            if (!value.path("endDate").isNull()) {
                throw invalid();
            }
            out.set("scheduledDate", value.path("date"));
            return out;
        }
        out.set("startDate", value.path("date"));
        out.set("endDate", value.path("endDate"));
        if ("Task".equals(node.type())) {
            var leaves = out.putArray("subTasks");
            node.children().forEach(child -> leaves.add(nested(mapper, child)));
        } else {
            var phases = out.putArray("subPlans");
            var tasks = out.putArray("tasks");
            for (Node child : node.children()) {
                ("SubPlan".equals(child.type()) ? phases : tasks).add(nested(mapper, child));
            }
        }
        return out;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Bản nháp kế hoạch không đúng cấu trúc hoặc vượt giới hạn.");
    }

    private record Node(long id, Long parentId, String type, JsonNode value, List<Node> children) {}
}
