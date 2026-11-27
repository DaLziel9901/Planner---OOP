package com.example.planner;

import com.example.planner.support.AuthTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@SpringBootTest
class PlannerApplicationTests {

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry properties) {
		AuthTestDatabase.registerApplicationProperties(properties);
	}

	@Test
	void contextLoads() {
	}

}
