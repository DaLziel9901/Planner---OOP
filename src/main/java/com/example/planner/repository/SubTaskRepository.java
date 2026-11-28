package com.example.planner.repository;

import com.example.planner.model.SubTask;
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
public class SubTaskRepository {
    private static final String OWNER_SCOPE = """
            EXISTS (SELECT 1 FROM task t WHERE t.id = leaf.task_id AND
            """ + TaskRepository.EFFECTIVE_OWNER;

    public List<SubTask> findByTaskIdsForOwner(long ownerId, java.util.Collection<Long> taskIds) {
        if (taskIds.isEmpty()) return List.of();
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
                "SELECT leaf.* FROM sub_task leaf WHERE leaf.task_id IN (:taskIds) AND "
                        + OWNER_SCOPE + " = :ownerId) ORDER BY leaf.order_index, leaf.id",
                java.util.Map.of("ownerId", ownerId, "taskIds", taskIds), this::mapRow);
    }

    public List<SubTask> findByTaskIdForOwner(long ownerId, long taskId) {
        return jdbc.query("SELECT leaf.* FROM sub_task leaf WHERE leaf.task_id = ? AND "
                + OWNER_SCOPE + " = ?) ORDER BY leaf.order_index, leaf.id", this::mapRow, taskId, ownerId);
    }

    public Optional<SubTask> findByIdForOwner(long ownerId, long id) {
        return jdbc.query("SELECT leaf.* FROM sub_task leaf WHERE leaf.id = ? AND "
                + OWNER_SCOPE + " = ?)", this::mapRow, id, ownerId).stream().findFirst();
    }

    public boolean updateForOwner(long ownerId, SubTask subTask) {
        return jdbc.update("""
                UPDATE sub_task leaf SET title = ?, description = ?, scheduled_date = ?, order_index = ?
                WHERE leaf.id = ? AND
                """ + OWNER_SCOPE + " = ?)", subTask.getTitle(), subTask.getDescription(),
                sqlDate(subTask.getScheduledDate()), subTask.getOrderIndex(), subTask.getId(), ownerId) == 1;
    }

    public boolean updateCompletionForOwner(long ownerId, SubTask subTask) {
        return jdbc.update("UPDATE sub_task leaf SET done = ?, completed_at = ? WHERE leaf.id = ? AND "
                + OWNER_SCOPE + " = ?)", subTask.isDone(), subTask.getCompletedAt(), subTask.getId(), ownerId) == 1;
    }

    public boolean deleteByIdForOwner(long ownerId, long id) {
        return jdbc.update("DELETE FROM sub_task leaf WHERE leaf.id = ? AND " + OWNER_SCOPE + " = ?)", id, ownerId) == 1;
    }

    private final JdbcTemplate jdbc;
    public SubTaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Long> insertForOwner(long ownerId, SubTask subTask) {
        if (subTask.getTaskId() == null) {
            throw new IllegalArgumentException("SubTask phải thuộc một Task");
        }
        return jdbc.query("""
                INSERT INTO sub_task (task_id, title, description, scheduled_date, order_index)
                SELECT t.id, ?, ?, ?, ?
                FROM task t
                WHERE t.id = ? AND
                """ + TaskRepository.EFFECTIVE_OWNER + " = ? RETURNING id",
                (row, index) -> row.getLong(1), subTask.getTitle(), subTask.getDescription(),
                sqlDate(subTask.getScheduledDate()), subTask.getOrderIndex(), subTask.getTaskId(), ownerId)
                .stream().findFirst();
    }
    private Date sqlDate(LocalDate date) { return date == null ? null : Date.valueOf(date); }
    private SubTask mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SubTask(rs.getLong("id"), rs.getLong("task_id"), rs.getString("title"), rs.getString("description"),
                rs.getObject("scheduled_date", LocalDate.class), rs.getBoolean("done"),
                rs.getObject("completed_at", OffsetDateTime.class), rs.getInt("order_index"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
