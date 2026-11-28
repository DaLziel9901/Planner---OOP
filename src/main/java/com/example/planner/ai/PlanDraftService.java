package com.example.planner.ai;

import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class PlanDraftService {
    public static final int MAX_JSON_CHARS = 256_000;
    private static final String INSTRUCTIONS = """
            Generate one practical Planner plan. Return one JSON object and no Markdown.
            Plan fields: title, goal, startDate, endDate, subPlans, tasks.
            SubPlan fields: title, description, startDate, endDate, subPlans, tasks.
            Task fields: title, description, startDate, endDate, subTasks.
            SubTask fields: title, description, scheduledDate.
            All listed fields are required. Use empty arrays where permitted, and null for unknown dates.
            Dates are YYYY-MM-DD or null. An end date must not precede its start date.
            Nest SubPlans at most five levels below the Plan. At least one Task is required anywhere.
            Every Task needs at least one specific, actionable SubTask. Keep the tree below 200 nodes.
            Use short titles of 1 to 255 characters and descriptions of at most 2000 characters.
            The input JSON contains the user's planning preferences only.
            """;

    private final PlanGenerationProvider provider;
    private final ObjectMapper mapper;

    public PlanDraftService(PlanGenerationProvider provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    public PlanDraft generate(String goal, String level, int durationDays, String notes) {
        return generateEditable(goal, level, durationDays, notes).tree();
    }

    public EditableDraft generateEditable(String goal, String level, int durationDays, String notes) {
        if (goal == null || goal.isBlank() || goal.length() > 1000 || level == null || level.isBlank()
                || level.length() > 120 || durationDays < 1 || durationDays > 366
                || (notes != null && notes.length() > 1500)) {
            throw new IllegalArgumentException("Mục tiêu, trình độ, thời hạn hoặc ghi chú không hợp lệ.");
        }
        String input = mapper.writeValueAsString(Map.of("goal", goal, "level", level,
                "durationDays", durationDays, "notes", notes == null ? "" : notes));
        return editable(provider.generate(INSTRUCTIONS, input));
    }

    public EditableDraft editable(String json) {
        PlanDraft draft = parse(json);
        String formatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(json));
        return new EditableDraft(draft, formatted.length() <= MAX_JSON_CHARS ? formatted : json);
    }

    public record EditableDraft(PlanDraft tree, String json) {}

    public PlanDraft parse(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) {
            throw validationFailure();
        }
        try {
            JsonNode root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
            var parser = new DraftParser();
            Fields plan = parser.fields(root, "goal", "subPlans", "tasks");
            var subPlans = list(root, "subPlans", child -> parser.subPlan(child, 1));
            var tasks = list(root, "tasks", parser::task);
            if (parser.taskCount == 0) {
                throw validationFailure();
            }
            return new PlanDraft(plan.title(), plan.details(), plan.start(), plan.end(), subPlans, tasks);
        } catch (RuntimeException failure) {
            throw validationFailure();
        }
    }

    private static final class DraftParser {
        private int count;
        private int taskCount;

        PlanDraft.SubPlanDraft subPlan(JsonNode node, int depth) {
            if (depth > 5) {
                throw validationFailure();
            }
            var fields = fields(node, "description", "subPlans", "tasks");
            return new PlanDraft.SubPlanDraft(fields.title(), fields.details(), fields.start(), fields.end(),
                    list(node, "subPlans", child -> subPlan(child, depth + 1)), list(node, "tasks", this::task));
        }

        PlanDraft.TaskDraft task(JsonNode node) {
            var fields = fields(node, "description", "subTasks");
            var subTasks = list(node, "subTasks", this::subTask);
            if (subTasks.isEmpty()) {
                throw validationFailure();
            }
            taskCount++;
            return new PlanDraft.TaskDraft(fields.title(), fields.details(), fields.start(), fields.end(), subTasks);
        }

        PlanDraft.SubTaskDraft subTask(JsonNode node) {
            shape(node, "title", "description", "scheduledDate");
            return new PlanDraft.SubTaskDraft(text(node, "title", 255, false),
                    text(node, "description", 2000, true), date(node, "scheduledDate"));
        }

        Fields fields(JsonNode node, String details, String... children) {
            var fields = new ArrayList<>(List.of("title", details, "startDate", "endDate"));
            fields.addAll(List.of(children));
            shape(node, fields.toArray(String[]::new));
            LocalDate start = date(node, "startDate");
            LocalDate end = date(node, "endDate");
            if (start != null && end != null && start.isAfter(end)) {
                throw validationFailure();
            }
            return new Fields(text(node, "title", 255, false), text(node, details, 2000, true), start, end);
        }

        private void shape(JsonNode node, String... fields) {
            if (node == null || !node.isObject() || ++count > 200 || node.size() != fields.length) {
                throw validationFailure();
            }
            for (String field : fields) {
                if (!node.has(field)) {
                    throw validationFailure();
                }
            }
        }
    }

    private record Fields(String title, String details, LocalDate start, LocalDate end) {
    }

    private static <T> List<T> list(JsonNode node, String field, Function<JsonNode, T> parse) {
        JsonNode array = node.path(field);
        if (!array.isArray() || array.size() > 200) {
            throw validationFailure();
        }
        List<T> children = new ArrayList<>();
        for (JsonNode child : array) {
            children.add(parse.apply(child));
        }
        return List.copyOf(children);
    }

    private static String text(JsonNode node, String field, int limit, boolean nullable) {
        var value = node.path(field);
        if (nullable && value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validationFailure();
        }
        String text = value.asString();
        if (text.codePointCount(0, text.length()) > limit || text.indexOf(0) >= 0
                || (!nullable && text.isBlank())) {
            throw validationFailure();
        }
        return text;
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field, 10, true);
        if (value == null) {
            return null;
        }
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000")) {
            throw validationFailure();
        }
        return LocalDate.parse(value);
    }

    private static IllegalArgumentException validationFailure() {
        return new IllegalArgumentException("Bản nháp kế hoạch không đúng cấu trúc hoặc vượt giới hạn.");
    }
}
