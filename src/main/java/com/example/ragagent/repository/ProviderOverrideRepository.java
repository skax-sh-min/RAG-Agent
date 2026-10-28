package com.example.ragagent.repository;

import com.example.ragagent.config.ProviderOverride;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code llm_provider_override}(Flyway V10) — {@code /settings} 의 LLM 라우팅 표에서 고친 프로바이더. 이름 → 편집 한 행.
 *
 * <p>읽는 곳은 {@code ProviderCatalog} 하나다: 기동 때 전부 올려 라우터가 쓸 목록을 굳히고, 이후 쓰기는 그 캐시와 이 테이블을
 * 함께 고친다. 행을 지우면 그 프로바이더는 {@code application.properties} 정의로 돌아간다. API 키 칸은 없다.
 */
@Repository
public class ProviderOverrideRepository {

    private final JdbcTemplate jdbc;

    public ProviderOverrideRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 이름 순. 비어 있으면 빈 맵. */
    public Map<String, ProviderOverride> findAll() {
        Map<String, ProviderOverride> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT name, base_url, model, type, role, priority, context_size, added, deleted
                FROM llm_provider_override ORDER BY name
                """, rs -> {
            ProviderOverride o = map(rs);
            out.put(o.name(), o);
        });
        return out;
    }

    /** 그 이름의 행을 통째로 바꾼다(없으면 만든다). */
    public void upsert(ProviderOverride o) {
        jdbc.update("""
                INSERT INTO llm_provider_override
                    (name, base_url, model, type, role, priority, context_size, added, deleted, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET
                    base_url = excluded.base_url,
                    model = excluded.model,
                    type = excluded.type,
                    role = excluded.role,
                    priority = excluded.priority,
                    context_size = excluded.context_size,
                    added = excluded.added,
                    deleted = excluded.deleted,
                    updated_at = excluded.updated_at
                """,
                o.name(), o.baseUrl(), o.model(), o.type(), o.role(), o.priority(), o.contextSize(),
                o.added() ? 1 : 0, o.deleted() ? 1 : 0, DbTimestamp.now());
    }

    /** 행을 지운다 — 그 프로바이더는 properties 정의로 돌아간다. 지운 행 수. */
    public int delete(String name) {
        return jdbc.update("DELETE FROM llm_provider_override WHERE name = ?", name);
    }

    private static ProviderOverride map(ResultSet rs) throws SQLException {
        return new ProviderOverride(
                rs.getString("name"),
                rs.getString("base_url"),
                rs.getString("model"),
                rs.getString("type"),
                rs.getString("role"),
                rs.getObject("priority") == null ? null : rs.getInt("priority"),
                rs.getObject("context_size") == null ? null : rs.getInt("context_size"),
                rs.getInt("added") == 1,
                rs.getInt("deleted") == 1);
    }
}
