package com.example.ragagent.repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * DB 에 쓰는 시각 — 언제나 <b>UTC</b>, {@code yyyy-MM-dd HH:mm:ss}(SQLite {@code datetime('now')} 와 같은 모양).
 *
 * <p>예전에는 {@code thread_meta}·{@code curated_qa}·{@code curated_submission}·{@code chunk_report} 의 저장소가 각자
 * {@code LocalDateTime.now()} 로 <b>서버 로컬</b> 시각을 써서, 같은 모양의 문자열이 테이블에 따라 다른 시간대였다(KST
 * 서버에서 {@code conversation_turns} 와 9시간 차이). 값에 시간대 표시가 없어 읽는 쪽이 가를 수 없으므로 쓰는 쪽을 여기로
 * 모았다. 그때까지 쌓인 값은 Flyway V9 가 UTC 로 옮겼다. 존을 주지 않은 {@code LocalDateTime.now()} 는
 * {@code DbTimestampConventionTest} 가 막는다.
 */
public final class DbTimestamp {

    public static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DbTimestamp() {}

    /** 지금, UTC. */
    public static String now() {
        return LocalDateTime.now(ZoneOffset.UTC).format(FORMAT);
    }
}
