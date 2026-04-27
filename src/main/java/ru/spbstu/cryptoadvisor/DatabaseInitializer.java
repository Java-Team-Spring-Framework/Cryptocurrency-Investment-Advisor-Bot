package ru.spbstu.cryptoadvisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Collectors;

@Component
public class DatabaseInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private static final int MAX_RETRIES = 15;
    private static final long RETRY_DELAY_MS = 3000;

    private final DataSource dataSource;

    public DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("Running database schema initialization...");
        ClassPathResource resource = new ClassPathResource("schema.sql");
        String sql;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            sql = reader.lines().collect(Collectors.joining("\n"));
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                log.info("Database schema initialized successfully on attempt {}", attempt);
                return;
            } catch (Exception e) {
                log.warn("Schema init attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    log.error("Failed to initialize database schema after {} attempts", MAX_RETRIES);
                    throw e;
                }
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }
}
