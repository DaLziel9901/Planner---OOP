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

    public long insertForOwner(long ownerId, Plan plan) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO plan (owner_user_id, title, goal, start_date, end_date)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, ownerId, plan.getTitle(), plan.getGoal(),
                toSqlDate(plan.getStartDate()), toSqlDate(plan.getEndDate()));
    }

    public List<Plan> findAllForOwner(long ownerId) {
        return jdbcTemplate.query("""
                SELECT id, title, goal, start_date, end_date, created_at
                FROM plan WHERE owner_user_id = ?
                ORDER BY created_at DESC, id DESC
                """, this::mapRow, ownerId);
    }

    public Optional<Plan> findByIdForOwner(long ownerId, long id) {
        return jdbcTemplate.query("""
                SELECT id, title, goal, start_date, end_date, created_at
                FROM plan WHERE id = ? AND owner_user_id = ?
                """, this::mapRow, id, ownerId).stream().findFirst();
    }

    public boolean updateForOwner(long ownerId, Plan plan) {
        return jdbcTemplate.update("""
                UPDATE plan SET title = ?, goal = ?, start_date = ?, end_date = ?
                WHERE id = ? AND owner_user_id = ?
                """, plan.getTitle(), plan.getGoal(), toSqlDate(plan.getStartDate()),
                toSqlDate(plan.getEndDate()), plan.getId(), ownerId) == 1;
    }

    public boolean deleteByIdForOwner(long ownerId, long id) {
        return jdbcTemplate.update("DELETE FROM plan WHERE id = ? AND owner_user_id = ?", id, ownerId) == 1;
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
