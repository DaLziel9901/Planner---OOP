package com.example.planner;

import com.example.planner.config.DatabaseTargetGuard;
import com.example.planner.config.ReleaseDatabaseBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@SpringBootApplication
public class PlannerApplication {

	@Bean
	@Lazy(false)
	DataSource dataSource(Environment environment) throws SQLException {
		DatabaseTargetGuard.verifyRuntimeSettings(environment);
		String url = environment.getRequiredProperty("spring.datasource.url");
		String role = environment.getRequiredProperty("spring.datasource.username");
		String password = environment.getRequiredProperty("spring.datasource.password");
		DataSource source = new AbstractDataSource() {
			@Override
			public Connection getConnection() throws SQLException {
				return DatabaseTargetGuard.openRuntime(url, role, password, DriverManager::getConnection);
			}

			@Override
			public Connection getConnection(String username, String password) throws SQLException {
				throw new SQLException("Per-connection PostgreSQL settings are disabled");
			}
		};
		try (Connection connection = source.getConnection()) {
			DatabaseTargetGuard.verifyRuntimeCatalog(connection);
		}
		return source;
	}

	public static void main(String[] args) throws SQLException {
		if (args.length == 1 && "--planner-bootstrap".equals(args[0])) {
			ReleaseDatabaseBootstrap.initialize(System.getenv("DB_URL"), System.getenv("DB_USERNAME"),
					System.getenv("DB_PASSWORD"));
			return;
		}
		SpringApplication.run(PlannerApplication.class, args);
	}

}
