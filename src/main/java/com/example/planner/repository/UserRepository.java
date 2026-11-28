package com.example.planner.repository;

import com.example.planner.model.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class UserRepository {
    private static final RowMapper<AppUser> USER = (row, index) -> new AppUser(
            row.getLong("id"), row.getString("username"), row.getString("password_hash"),
            row.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(String username, String passwordHash) {
        return jdbc.update("""
                INSERT INTO public.app_user (username, password_hash)
                VALUES (?, ?)
                ON CONFLICT (username) DO NOTHING
                """, username, passwordHash) == 1;
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("""
                SELECT id, username, password_hash, created_at FROM public.app_user WHERE username = ?
                """, USER, username).stream().findFirst();
    }
}
