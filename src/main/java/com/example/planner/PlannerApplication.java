package com.example.planner;

import com.example.planner.config.DatabaseTargetGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@SpringBootApplication
public class PlannerApplication {

	@Bean
	@Lazy(false)
	DataSource dataSource(Environment environment) {
		DatabaseTargetGuard.verifyRuntimeSettings(environment);
		// Keep runtime closed until the 003 catalog and owner-scoped routes are ready.
		throw new IllegalStateException("The complete Planner runtime is disabled during the 003 foundation");
	}

	public static void main(String[] args) {
		SpringApplication.run(PlannerApplication.class, args);
	}

}
