package com.example.planner.integration;

import com.example.planner.support.AuthTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class PlannerSchemaBootstrapTest {
    @Test
    @EnabledIfSystemProperty(named = "planner.test.bootstrap", matches = "true")
    void initializesDedicatedEmptyTestTarget() throws Exception {
        AuthTestDatabase.initializeEmptyPlannerSchema();
    }
}
