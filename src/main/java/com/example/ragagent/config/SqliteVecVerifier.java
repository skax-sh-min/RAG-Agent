package com.example.ragagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * sqlite-vec 네이티브 확장이 실제로 로드됐는지 기동 시점에 검증한다.
 *
 * <p>{@code app.vectorstore.type=sqlite-vec}일 때만 생성된다. {@link DataSourceConfig#configureSqliteVec}가
 * 풀링된 커넥션마다 확장을 로드하도록 설정하며, 이 빈은 {@code vec_version()} 호출로 확인하고
 * 실패 시 즉각 예외를 던진다 — 첫 쿼리까지 미루면 "no such function" 같은 불명확한 오류가 발생하기 때문.
 */
@Component
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "sqlite-vec")
public class SqliteVecVerifier {

    private static final Logger log = LoggerFactory.getLogger(SqliteVecVerifier.class);

    private final JdbcTemplate jdbc;

    public SqliteVecVerifier(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc) {
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
