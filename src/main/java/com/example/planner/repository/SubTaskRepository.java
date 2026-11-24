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
    public List<SubTask> findByTaskIds(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
                "SELECT " + COLUMNS + " FROM sub_task WHERE task_id IN (:ids) ORDER BY order_index, id",
                java.util.Map.of("ids", ids), this::mapRow);
    }
    private final JdbcTemplate jdbc;
    public SubTaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String COLUMNS = "id, task_id, title, description, scheduled_date, done, completed_at, order_index, created_at";

    public List<SubTask> findByTaskId(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sub_task WHERE task_id = ? ORDER BY order_index, id", this::mapRow, taskId);
    }
    public Optional<SubTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sub_task WHERE id = ?", this::mapRow, id).stream().findFirst();
    }
    public long insert(SubTask subTask) {
        if (subTask.getTaskId() == null) throw new IllegalArgumentException("SubTask phải thuộc một Task");
        Long id = jdbc.queryForObject("""
                INSERT INTO sub_task (task_id, title, description, scheduled_date, order_index, done, completed_at)
                VALUES (?, ?, ?, ?, ?, false, NULL) RETURNING id
                """, Long.class, subTask.getTaskId(), subTask.getTitle(), subTask.getDescription(),
                sqlDate(subTask.getScheduledDate()), subTask.getOrderIndex());
        if (id == null) throw new IllegalStateException("Database không trả về ID SubTask vừa tạo");
        return id;
    }
    public boolean update(SubTask subTask) {
        // Sửa nội dung không đổi Task, trạng thái hoàn thành hoặc thời điểm tạo.
        return jdbc.update("""
                UPDATE sub_task SET title = ?, description = ?, scheduled_date = ?, order_index = ? WHERE id = ?
                """, subTask.getTitle(), subTask.getDescription(), sqlDate(subTask.getScheduledDate()),
                subTask.getOrderIndex(), subTask.getId()) == 1;
    }
    public boolean updateCompletion(SubTask subTask) {
        return jdbc.update("UPDATE sub_task SET done = ?, completed_at = ? WHERE id = ?",
                subTask.isDone(), subTask.getCompletedAt(), subTask.getId()) == 1;
    }

    public boolean deleteById(long id) { return jdbc.update("DELETE FROM sub_task WHERE id = ?", id) == 1; }

    private Date sqlDate(LocalDate date) { return date == null ? null : Date.valueOf(date); }
    private SubTask mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SubTask(rs.getLong("id"), rs.getLong("task_id"), rs.getString("title"), rs.getString("description"),
                rs.getObject("scheduled_date", LocalDate.class), rs.getBoolean("done"),
                rs.getObject("completed_at", OffsetDateTime.class), rs.getInt("order_index"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
