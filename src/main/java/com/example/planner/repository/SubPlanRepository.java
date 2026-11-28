package com.example.planner.repository;

import com.example.planner.model.SubPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

@Repository
public class SubPlanRepository {
    private final JdbcTemplate jdbc;

    public SubPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> insertForOwner(long ownerId, SubPlan subPlan) {
        return jdbc.query("""
                INSERT INTO sub_plan (plan_id, parent_sub_plan_id, title, description, start_date, end_date, order_index)
                SELECT p.id, ?, ?, ?, ?, ?, ?
                FROM plan p WHERE p.id = ? AND p.owner_user_id = ?
                  AND (CAST(? AS BIGINT) IS NULL OR EXISTS (
                      SELECT 1 FROM sub_plan parent WHERE parent.id = ? AND parent.plan_id = p.id))
                RETURNING id
                """, (row, index) -> row.getLong(1), subPlan.getParentSubPlanId(), subPlan.getTitle(),
                subPlan.getDescription(), subPlan.getStartDate(), subPlan.getEndDate(), subPlan.getOrderIndex(),
                subPlan.getPlanId(), ownerId, subPlan.getParentSubPlanId(), subPlan.getParentSubPlanId())
                .stream().findFirst();
    }

    public List<SubPlan> findByPlanIdsForOwner(long ownerId, Collection<Long> planIds) {
        if (planIds.isEmpty()) {
            return List.of();
        }
        return new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT s.* FROM sub_plan s JOIN plan p ON p.id = s.plan_id
                WHERE p.owner_user_id = :ownerId AND s.plan_id IN (:planIds)
                ORDER BY s.order_index, s.id
                """, Map.of("ownerId", ownerId, "planIds", planIds), this::mapRow);
    }

    public List<SubPlan> findByPlanIdForOwner(long ownerId, long planId) {
        return jdbc.query("""
                SELECT s.* FROM sub_plan s JOIN plan p ON p.id = s.plan_id
                WHERE s.plan_id = ? AND p.owner_user_id = ? ORDER BY s.order_index, s.id
                """, this::mapRow, planId, ownerId);
    }

    public Optional<SubPlan> findByIdForOwner(long ownerId, long id) {
        return jdbc.query("""
                SELECT s.* FROM sub_plan s JOIN plan p ON p.id = s.plan_id
                WHERE s.id = ? AND p.owner_user_id = ?
                """, this::mapRow, id, ownerId).stream().findFirst();
    }

    public boolean updateForOwner(long ownerId, SubPlan subPlan) {
        return jdbc.update("""
                UPDATE sub_plan s SET parent_sub_plan_id = ?, title = ?, description = ?,
                    start_date = ?, end_date = ?, order_index = ?
                FROM plan p
                WHERE s.id = ? AND s.plan_id = ? AND p.id = s.plan_id AND p.owner_user_id = ?
                  AND (CAST(? AS BIGINT) IS NULL OR EXISTS (
                      SELECT 1 FROM sub_plan parent WHERE parent.id = ? AND parent.plan_id = s.plan_id))
                """, subPlan.getParentSubPlanId(), subPlan.getTitle(), subPlan.getDescription(),
                toSqlDate(subPlan.getStartDate()), toSqlDate(subPlan.getEndDate()), subPlan.getOrderIndex(),
                subPlan.getId(), subPlan.getPlanId(), ownerId,
                subPlan.getParentSubPlanId(), subPlan.getParentSubPlanId()) == 1;
    }

    public boolean deleteByIdForOwner(long ownerId, long id) {
        return jdbc.update("""
                DELETE FROM sub_plan s USING plan p
                WHERE s.id = ? AND s.plan_id = p.id AND p.owner_user_id = ?
                """, id, ownerId) == 1;
    }

    public boolean lockPlanForOwner(long ownerId, long planId) {
        return !jdbc.queryForList("""
                SELECT id FROM plan WHERE id = ? AND owner_user_id = ? FOR UPDATE
                """, Long.class, planId, ownerId).isEmpty();
    }

    private SubPlan mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SubPlan(rs.getLong("id"), rs.getLong("plan_id"),
                rs.getObject("parent_sub_plan_id", Long.class), rs.getString("title"),
                rs.getString("description"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getInt("order_index"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private Date toSqlDate(LocalDate date) {
        return date == null ? null : Date.valueOf(date);
    }
}
