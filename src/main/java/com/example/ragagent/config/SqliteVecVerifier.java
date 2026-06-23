package com.example.ragagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 5 Step 5.2 — verifies the sqlite-vec native extension actually loaded.
 *
 * <p>Only created when {@code app.vectorstore.type=sqlite-vec}; the default {@code chroma}
 * backend never instantiates this bean. {@link DataSourceConfig#configureSqliteVec} arranges
 * the extension to be loaded on every pooled connection — here we just confirm it at startup
 * by calling {@code vec_version()} and fail fast (with operator guidance) if it is missing,
 * rather than surfacing a cryptic "no such function" error on the first user query.
 */
@Component
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "sqlite-vec")
public class SqliteVecVerifier {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecVerifier.class);

    private final JdbcTemplate jdbc;

    public SqliteVecVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void verify() {
        try {
            String version = jdbc.queryForObject("SELECT vec_version()", String.class);
            log.info("[SQLITE-VEC] extension loaded — vec_version()={}", version);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "sqlite-vec 확장 로드 검증 실패 (SELECT vec_version()). "
                    + "app.vectorstore.sqlite-vec.extension-path 가 올바른 vec0 바이너리를 가리키는지, "
                    + "현재 플랫폼용 바이너리인지 확인하세요. 원인: " + e.getMessage(), e);
        }
    }
}
