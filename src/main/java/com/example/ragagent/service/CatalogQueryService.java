package com.example.ragagent.service;

import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.repository.LlmUsageRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 앱 내부 메타데이터를 <b>미리 정한 이름</b>으로만 조회한다 (AGENT_MCP_DESIGN §3.3 {@code queryCatalog}). 자유 SQL 은 없다 — 이름이
 * {@link Query} 의 값이 아니면 거절하고, 각 조회는 정해진 서비스·저장소 메서드 하나를 부를 뿐이다. 그래서 {@code users}, {@code app_secret},
 * {@code persistent_logins}, {@code settings_override}, {@code llm_provider_override} 같은 테이블에는 이 클래스에서 닿는 길이 없다.
 *
 * <p>결과는 {@value #MAX_ROWS}행으로 자른다({@link Result#truncated()}).
 */
@Service
public class CatalogQueryService {

    public static final int MAX_ROWS = 50;
    static final int DEFAULT_RECENT = 10;
    static final String VERSION = "latest";

    /** 조회 이름과 관리자 전용 여부. 이름은 호출자가 보는 값 그대로다. */
    public enum Query {
        DOCUMENTS_LIST("documents.list", false),
        DOCUMENTS_RECENT("documents.recent", false),
        TAGS_LIST("tags.list", false),
        REPORTS_OPEN("reports.open", true),
        USAGE_SUMMARY("usage.summary", true);

        private final String wireName;
        private final boolean adminOnly;

        Query(String wireName, boolean adminOnly) {
            this.wireName = wireName;
            this.adminOnly = adminOnly;
        }

        public String wireName() { return wireName; }

        public boolean adminOnly() { return adminOnly; }

        public static Optional<Query> parse(String name) {
            if (name == null) return Optional.empty();
            String n = name.strip().toLowerCase(Locale.ROOT);
            for (Query q : values()) if (q.wireName.equals(n)) return Optional.of(q);
            return Optional.empty();
        }

        public static List<String> names() {
            return java.util.Arrays.stream(values()).map(Query::wireName).toList();
        }
    }

    public record Result(String name, List<Map<String, Object>> rows, boolean truncated) {}

    private final RagService ragService;
    private final ChunkReportService reportService;
    private final LlmUsageRepository usageRepository;

    public CatalogQueryService(RagService ragService, ChunkReportService reportService,
                               LlmUsageRepository usageRepository) {
        this.ragService = ragService;
        this.reportService = reportService;
        this.usageRepository = usageRepository;
    }

    /**
     * @param param 조회마다 한 가지 선택 인자 — {@code documents.list} 는 태그 필터, {@code documents.recent} 는 개수. 나머지는 무시한다
     * @throws IllegalArgumentException 모르는 이름
     */
    public Result query(Query query, String param) {
        return switch (query) {
            case DOCUMENTS_LIST -> documents(query, param, MAX_ROWS);
            case DOCUMENTS_RECENT -> documents(query, null, recentCount(param));
            case TAGS_LIST -> tags();
            case REPORTS_OPEN -> openReports();
            case USAGE_SUMMARY -> usage();
        };
    }

    private Result documents(Query query, String tag, int limit) {
        String wanted = tag == null ? "" : tag.strip();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        // listDocuments 는 색인 시각 내림차순이다(최근 문서가 앞).
        for (DocumentInfo d : ragService.listDocuments(DocRegistry.SHARED)) {
            if (!wanted.isEmpty() && (d.tags() == null || d.tags().stream().noneMatch(wanted::equalsIgnoreCase))) continue;
            if (d.version() != null && !VERSION.equals(d.version())) continue;
            if (rows.size() >= limit) { truncated = true; break; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docId", d.docId());
            row.put("file", d.displayName() != null && !d.displayName().isBlank() ? d.displayName() : d.filename());
            row.put("tags", d.tags() == null ? List.of() : d.tags());
            row.put("chunks", d.chunks());
            row.put("indexedAt", d.indexedAt());
            rows.add(row);
        }
        return new Result(query.wireName(), List.copyOf(rows), truncated);
    }

    private Result tags() {
        List<String> all = ragService.listTags(VERSION);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String t : all) {
            if (rows.size() >= MAX_ROWS) break;
            rows.add(Map.of("tag", t));
        }
        return new Result(Query.TAGS_LIST.wireName(), List.copyOf(rows), all.size() > MAX_ROWS);
    }

    private Result openReports() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var g : reportService.openGroups(0, MAX_ROWS)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", g.chunkId());
            row.put("docId", g.docId());
            row.put("file", g.filename());
            row.put("reportCount", g.reportCount());
            row.put("lastReportedAt", g.lastReportedAt());
            rows.add(row);
        }
        return new Result(Query.REPORTS_OPEN.wireName(), List.copyOf(rows), reportService.openChunkCount() > rows.size());
    }

    private Result usage() {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> providers = usageRepository.usedProviders().stream().sorted().toList();
        for (String p : providers) {
            if (rows.size() >= MAX_ROWS) break;
            var day = usageRepository.getDaily(p);
            var month = usageRepository.getMonthly(p);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", p);
            row.put("todayCalls", day.callCount());
            row.put("todayTokens", day.totalTokens());
            row.put("monthCalls", month.callCount());
            row.put("monthTokens", month.totalTokens());
            rows.add(row);
        }
        return new Result(Query.USAGE_SUMMARY.wireName(), List.copyOf(rows), providers.size() > rows.size());
    }

    private static int recentCount(String param) {
        if (param == null || param.isBlank()) return DEFAULT_RECENT;
        try {
            return Math.max(1, Math.min(MAX_ROWS, Integer.parseInt(param.strip())));
        } catch (NumberFormatException e) {
            return DEFAULT_RECENT;
        }
    }
}
