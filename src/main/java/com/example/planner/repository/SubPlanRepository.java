package com.example.planner.repository;

import com.example.planner.model.SubPlan;
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
public class SubPlanRepository {
    private final JdbcTemplate jdbc;

    public SubPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Đọc toàn bộ tầng của Plan, không chỉ các SubPlan tầng 1.
    public List<SubPlan> findByPlanId(long planId) {
        return jdbc.query("SELECT * FROM sub_plan WHERE plan_id = ? ORDER BY order_index, id",
                this::mapRow, planId);
    }

    public Optional<SubPlan> findById(long id) {
        return jdbc.query("SELECT * FROM sub_plan WHERE id = ?", this::mapRow, id)
                .stream().findFirst();
    }

    public long insert(SubPlan subPlan) {
        Long id = jdbc.queryForObject("""
                INSERT INTO sub_plan
                    (plan_id, parent_sub_plan_id, title, description, start_date, end_date, order_index)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, subPlan.getPlanId(), subPlan.getParentSubPlanId(),
                subPlan.getTitle(), subPlan.getDescription(), toSqlDate(subPlan.getStartDate()),
                toSqlDate(subPlan.getEndDate()), subPlan.getOrderIndex());
        if (id == null) {
            throw new IllegalStateException("Database không trả ID SubPlan mới");
        }
        return id;
    }

    // Không đổi plan_id hoặc created_at; chỉ chuyển nhánh trong cùng Plan.
    public boolean update(SubPlan subPlan) {
        return jdbc.update("""
                UPDATE sub_plan SET parent_sub_plan_id = ?, title = ?, description = ?,
                    start_date = ?, end_date = ?, order_index = ?
                WHERE id = ? AND plan_id = ?
                """, subPlan.getParentSubPlanId(), subPlan.getTitle(), subPlan.getDescription(),
                toSqlDate(subPlan.getStartDate()), toSqlDate(subPlan.getEndDate()),
                subPlan.getOrderIndex(), subPlan.getId(), subPlan.getPlanId()) == 1;
    }

    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM sub_plan WHERE id = ?", id) == 1;
    }

    // Service gọi trong @Transactional: các thao tác cấu trúc cùng Plan lần lượt thực hiện.
    public boolean lockPlan(long planId) {
        return !jdbc.queryForList("SELECT id FROM plan WHERE id = ? FOR UPDATE", Long.class, planId).isEmpty();
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
