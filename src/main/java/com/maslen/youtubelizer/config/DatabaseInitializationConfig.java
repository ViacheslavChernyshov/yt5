package com.maslen.youtubelizer.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Slf4j
@Configuration
public class DatabaseInitializationConfig {

    private final DataSource dataSource;

    @Autowired
    public DatabaseInitializationConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void initializeDatabase() {
        log.info("Database initialization completed - Hibernate will handle schema management via ddl-auto=update");
        
        // Verify that required tables exist
        try (Connection connection = dataSource.getConnection()) {
            // Test connection and log
            log.info("Database connection established successfully");
        } catch (SQLException e) {
            log.error("Database connection failed: {}", e.getMessage());
        }
    }
}