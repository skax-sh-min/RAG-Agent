package com.example.ragagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the data directory before HikariCP opens the SQLite database.
 *
 * Without this, LlmUsageRepository.init() (and the other @PostConstruct
 * repository inits) fail at startup because ./data/memory.db cannot be
 * created when the parent directory does not yet exist.  RagService would
 * normally create the directory, but its @PostConstruct runs later in the
 * dependency chain.
 */
@Configuration
public class DataSourceConfig {

    @Value("${app.data-dir:./data}")
    private String dataDir;

    @Value("${spring.datasource.hikari.maximum-pool-size:1}")
    private int maxPoolSize;

    @Bean
    public DataSource dataSource() throws IOException {
        Path dir = Path.of(dataDir);
        Files.createDirectories(dir);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dir.toAbsolutePath() + "/memory.db");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(maxPoolSize);
        return new HikariDataSource(config);
    }
}
