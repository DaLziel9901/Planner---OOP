package com.example.planner.repository;

import com.example.planner.model.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Repository
public class TaskRepository {
    // All callers of this predicate alias the candidate task as t.
    static final String EFFECTIVE_OWNER = """
            CASE WHEN t.plan_id IS NULL AND t.sub_plan_id IS NULL THEN t.owner_user_id
                 ELSE (SELECT p.owner_user_id FROM plan p
                       WHERE p.id = COALESCE(t.plan_id, (SELECT s.plan_id FROM sub_plan s WHERE s.id = t.sub_plan_id)))
            END
            """;

    public List<Task> findByPlanIdsForOwner(long ownerId, java.util.Collection<Long> planIds) {
        if (planIds.isEmpty()) return List.of();
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
                SELECT t.* FROM task t LEFT JOIN sub_plan s ON s.id = t.sub_plan_id
                WHERE COALESCE(t.plan_id, s.plan_id) IN (:planIds) AND
                """ + EFFECTIVE_OWNER + " = :ownerId ORDER BY t.order_index, t.id",
                java.util.Map.of("ownerId", ownerId, "planIds", planIds), this::mapRow);
    }

    public List<Task> findIndependentForOwner(long ownerId) {
        return jdbc.query("""
                SELECT t.* FROM task t WHERE plan_id IS NULL AND sub_plan_id IS NULL
                AND owner_user_id = ? ORDER BY order_index, id
                """, this::mapRow, ownerId);
    }

    public List<Task> findByPlanIdForOwner(long ownerId, long planId) {
        return jdbc.query("SELECT t.* FROM task t WHERE t.plan_id = ? AND " + EFFECTIVE_OWNER
                + " = ? ORDER BY t.order_index, t.id", this::mapRow, planId, ownerId);
    }

    public List<Task> findBySubPlanIdForOwner(long ownerId, long subPlanId) {
        return jdbc.query("SELECT t.* FROM task t WHERE t.sub_plan_id = ? AND " + EFFECTIVE_OWNER
                + " = ? ORDER BY t.order_index, t.id", this::mapRow, subPlanId, ownerId);
    }

    public Optional<Task> findByIdForOwner(long ownerId, long id) {
        return jdbc.query("SELECT t.* FROM task t WHERE t.id = ? AND " + EFFECTIVE_OWNER + " = ?",
                this::mapRow, id, ownerId).stream().findFirst();
    }

    public boolean lockTaskForOwner(long ownerId, long id) {
        return !jdbc.queryForList("SELECT t.id FROM task t WHERE t.id = ? AND " + EFFECTIVE_OWNER
                + " = ? FOR UPDATE OF t", Long.class, id, ownerId).isEmpty();
    }

    public boolean updateForOwner(long ownerId, Task task) {
        return jdbc.update("""
                UPDATE task t SET title = ?, description = ?, start_date = ?, end_date = ?, order_index = ?
                WHERE t.id = ? AND
                """ + EFFECTIVE_OWNER + " = ?", task.getTitle(), task.getDescription(),
                sqlDate(task.getStartDate()), sqlDate(task.getEndDate()), task.getOrderIndex(), task.getId(), ownerId) == 1;
    }

    public boolean deleteByIdForOwner(long ownerId, long id) {
        return jdbc.update("DELETE FROM task t WHERE t.id = ? AND " + EFFECTIVE_OWNER + " = ?", id, ownerId) == 1;
    }

    private final JdbcTemplate jdbc;
    public TaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    // Chỉ đọc thuộc tính Task, chưa nạp SubTask/tiến độ.

    public Optional<Long> insertForOwner(long ownerId, Task task) {
        String sql = """
                INSERT INTO task (plan_id, sub_plan_id, owner_user_id, title, description, start_date, end_date, order_index)
                SELECT input.plan_id, input.sub_plan_id,
                       CASE WHEN input.plan_id IS NULL AND input.sub_plan_id IS NULL THEN input.owner_id END,
                       ?, ?, ?, ?, ?
                FROM (VALUES (CAST(? AS bigint), CAST(? AS bigint), CAST(? AS bigint)))
                    input(plan_id, sub_plan_id, owner_id)
                WHERE (input.plan_id IS NULL AND input.sub_plan_id IS NULL)
                   OR EXISTS (
                       SELECT 1 FROM plan p
                       WHERE p.owner_user_id = input.owner_id
                         AND (p.id = input.plan_id OR p.id = (
                             SELECT s.plan_id FROM sub_plan s WHERE s.id = input.sub_plan_id))
                   )
                RETURNING id
                """;
        return jdbc.query(sql, (row, index) -> row.getLong(1), task.getTitle(), task.getDescription(),
                sqlDate(task.getStartDate()), sqlDate(task.getEndDate()), task.getOrderIndex(),
                task.getPlanId(), task.getSubPlanId(), ownerId).stream().findFirst();
    }

    private Date sqlDate(LocalDate date) { return date == null ? null : Date.valueOf(date); }

    private Task mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Task(rs.getLong("id"), rs.getObject("plan_id", Long.class), rs.getObject("sub_plan_id", Long.class), rs.getString("title"), rs.getString("description"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getInt("order_index"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
