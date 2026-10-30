package com.example.planner.repository;

import com.example.planner.model.Plan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class PlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public PlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Chỉ đọc thuộc tính Plan, chưa nạp Task/SubPlan.
    public List<Plan> findAll() {
        String sql = """
                SELECT id, title, goal, start_date, end_date, created_at
                FROM plan
                ORDER BY created_at DESC, id DESC
                """;

        return jdbcTemplate.query(sql, this::mapRow);
    }

    public Optional<Plan> findById(long id) {
        String sql = """
                SELECT id, title, goal, start_date, end_date, created_at
                FROM plan
                WHERE id = ?
                """;

        List<Plan> plans = jdbcTemplate.query(sql, this::mapRow, id);

        if (plans.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(plans.get(0));
    }

    public long insert(Plan plan) {
        String sql = """
                INSERT INTO plan (title, goal, start_date, end_date)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """;

        Long id = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                plan.getTitle(),
                plan.getGoal(),
                toSqlDate(plan.getStartDate()),
                toSqlDate(plan.getEndDate())
        );

        if (id == null) {
            throw new IllegalStateException(
                    "Database không trả về ID của Plan vừa tạo"
            );
        }

        return id;
    }

    public boolean update(Plan plan) {
        String sql = """
                UPDATE plan
                SET title = ?, goal = ?, start_date = ?, end_date = ?
                WHERE id = ?
                """;

        int affectedRows = jdbcTemplate.update(
                sql,
                plan.getTitle(),
                plan.getGoal(),
                toSqlDate(plan.getStartDate()),
                toSqlDate(plan.getEndDate()),
                plan.getId()
        );

        return affectedRows == 1;
    }

    public boolean deleteById(long id) {
        String sql = "DELETE FROM plan WHERE id = ?";

        int affectedRows = jdbcTemplate.update(sql, id);

        return affectedRows == 1;
    }

    private Plan mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Plan(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("goal"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Date toSqlDate(LocalDate date) {
        return date == null ? null : Date.valueOf(date);
    }
}