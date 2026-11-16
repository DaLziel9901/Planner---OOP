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
    // Chỉ đọc thuộc tính Task, chưa nạp SubTask/tiến độ.
    private static final String COLUMNS = "id, plan_id, sub_plan_id, title, description, start_date, end_date, order_index, created_at";
    private static final String INDEPENDENT = "plan_id IS NULL AND sub_plan_id IS NULL";

    public List<Task> findIndependent() {
        return jdbc.query("SELECT " + COLUMNS + " FROM task WHERE " + INDEPENDENT
                + " ORDER BY order_index, id", this::mapRow);
    }

    public List<Task> findByPlanId(long planId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task WHERE plan_id = ? ORDER BY order_index, id", this::mapRow, planId);
    }
    public List<Task> findBySubPlanId(long subPlanId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task WHERE sub_plan_id = ? ORDER BY order_index, id", this::mapRow, subPlanId);
    }

    public Optional<Task> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task WHERE id = ?",
                this::mapRow, id).stream().findFirst();
    }

    // Gọi trong giao dịch service, cùng hàng sẽ được đọc lại sau khóa.
    public boolean lockTask(long id) {
        return !jdbc.queryForList("SELECT id FROM task WHERE id = ?"
                + " FOR UPDATE", Long.class, id).isEmpty();
    }

    public long insert(Task task) {
        Long id = jdbc.queryForObject("""
                INSERT INTO task (plan_id, sub_plan_id, title, description, start_date, end_date, order_index)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, task.getPlanId(), task.getSubPlanId(), task.getTitle(), task.getDescription(), sqlDate(task.getStartDate()),
                sqlDate(task.getEndDate()), task.getOrderIndex());
        if (id == null) throw new IllegalStateException("Database không trả về ID Task vừa tạo");
        return id;
    }

    public boolean update(Task task) {
        // FK nơi chứa và created_at không lấy từ đối tượng cập nhật.
        return jdbc.update("UPDATE task SET title = ?, description = ?, start_date = ?, end_date = ?, order_index = ?"
                        + " WHERE id = ?",
                task.getTitle(), task.getDescription(), sqlDate(task.getStartDate()), sqlDate(task.getEndDate()),
                task.getOrderIndex(), task.getId()) == 1;
    }

    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM task WHERE id = ?", id) == 1;
    }

    private Date sqlDate(LocalDate date) { return date == null ? null : Date.valueOf(date); }

    private Task mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Task(rs.getLong("id"), rs.getObject("plan_id", Long.class), rs.getObject("sub_plan_id", Long.class), rs.getString("title"), rs.getString("description"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getInt("order_index"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
