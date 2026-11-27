package com.example.planner.integration;

import com.example.planner.model.Plan;
import com.example.planner.repository.*;
import com.example.planner.service.*;
import com.example.planner.support.AuthTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
abstract class PostgresTreeTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({PlanRepository.class, SubPlanRepository.class, TaskRepository.class, SubTaskRepository.class,
            PlanService.class, SubPlanService.class, TaskService.class, SubTaskService.class, PlanTreeService.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return AuthTestDatabase.dataSource();
        }

        @Bean
        JdbcTemplate jdbc(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        JdbcTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlanRepository plans;
    @Autowired TaskRepository tasks;
    @Autowired SubTaskRepository leaves;
    @Autowired SubPlanService subPlans;
    @Autowired TaskService taskService;
    @Autowired SubTaskService leafService;
    @Autowired PlanTreeService trees;

    private final List<Long> planIds = new ArrayList<>();
    private final List<Long> independentTaskIds = new ArrayList<>();

    long plan() {
        long id = plans.insert(new Plan("JUnit tree fixture"));
        planIds.add(id);
        return id;
    }

    long subPlan(long planId, Long parentId) {
        return subPlans.create(planId, parentId, "JUnit branch", null, null, null, 0);
    }

    long task(Long planId, Long subPlanId) {
        long id = taskService.create(planId, subPlanId, "JUnit task", null, null, null, 0);
        if (planId == null && subPlanId == null) independentTaskIds.add(id);
        return id;
    }

    long leaf(long taskId) {
        return leafService.create(taskId, "JUnit leaf", null, null, 0);
    }

    @AfterEach
    void cleanFixtures() {
        try {
            for (long id : independentTaskIds) tasks.deleteById(id);
        } finally {
            for (long id : planIds) plans.deleteById(id);
        }
    }

    int backendPid() {
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }
}
