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
    private final JdbcTemplate jdbc;
    public TaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    // Lát cắt hiện tại chỉ quản lý Task độc lập, chưa nạp SubTask/tiến độ.
    private static final String COLUMNS = "id, title, description, start_date, end_date, order_index, created_at";
    private static final String INDEPENDENT = "plan_id IS NULL AND sub_plan_id IS NULL";

    public List<Task> findIndependent() {
        return jdbc.query("SELECT " + COLUMNS + " FROM task WHERE " + INDEPENDENT
                + " ORDER BY order_index, id", this::mapRow);
    }

    public Optional<Task> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task WHERE id = ? AND " + INDEPENDENT,
                this::mapRow, id).stream().findFirst();
    }

    // Gọi trong giao dịch service, cùng hàng sẽ được đọc lại sau khóa.
    public boolean lockTask(long id) {
        return !jdbc.queryForList("SELECT id FROM task WHERE id = ? AND " + INDEPENDENT
                + " FOR UPDATE", Long.class, id).isEmpty();
    }

    public long insert(Task task) {
        requireIndependent(task);
        Long id = jdbc.queryForObject("""
                INSERT INTO task (title, description, start_date, end_date, order_index)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, task.getTitle(), task.getDescription(), sqlDate(task.getStartDate()),
                sqlDate(task.getEndDate()), task.getOrderIndex());
        if (id == null) throw new IllegalStateException("Database không trả về ID Task vừa tạo");
        return id;
    }

    public boolean update(Task task) {
        requireIndependent(task);
        return jdbc.update("UPDATE task SET title = ?, description = ?, start_date = ?, end_date = ?, order_index = ?"
                        + " WHERE id = ? AND " + INDEPENDENT,
                task.getTitle(), task.getDescription(), sqlDate(task.getStartDate()), sqlDate(task.getEndDate()),
                task.getOrderIndex(), task.getId()) == 1;
    }

    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM task WHERE id = ? AND " + INDEPENDENT, id) == 1;
    }

    private void requireIndependent(Task task) {
        if (task.getPlanId() != null || task.getSubPlanId() != null)
            throw new IllegalArgumentException("Luồng này chỉ nhận Task độc lập");
    }

    private Date sqlDate(LocalDate date) { return date == null ? null : Date.valueOf(date); }

    private Task mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Task(rs.getLong("id"), null, null, rs.getString("title"), rs.getString("description"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getInt("order_index"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
