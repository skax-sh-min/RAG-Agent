package com.example.ragagent.repository;

import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.ingestion.SearchTextBuilder;
import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 청크 id 로 FTS 행을 찾는 세 조회(재사용 해시 · 출처 미리보기 · 원문 보기)와 신고 스냅샷이
 * {@code chunk_fts_key} 를 타고도 예전과 같은 값을 돌려주는지 — 진짜 FTS5 위에서.
 *
 * <p>일부러 {@code vec_document_chunks} 를 만들지 않는다. 그게 Chroma 배포의 실제 모양이고,
 * 예전 SQL 은 그 테이블을 무조건 조인해서 그 배포에서는 대화 열기 자체가 죽었다.
 */
class QuestionReuseChunkLookupTest {

    @TempDir Path tmp;
    private JdbcTemplate jdbc;
    private KeywordSearchRepository fts;
    private QuestionReuseRepository repo;
    private Document c1;
    private Document c2;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("lookup.db"));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE conversation_turns (id INTEGER PRIMARY KEY, user_id TEXT)");
        jdbc.update("INSERT INTO conversation_turns (id, user_id) VALUES (7, 'u1')");

        fts = new KeywordSearchRepository(jdbc);
        // init() 은 패키지 전용 — 운영 부트스트랩(@PostConstruct)과 같은 진입점을 그대로 쓴다.
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(fts, "init");
        assumeTrue(fts.isAvailable(), "FTS5 not available in this SQLite build");
        repo = new QuestionReuseRepository(jdbc, jdbc);
        repo.init();

        c1 = chunk("c1", "D1", 0, "3", "1.2", "결제 오류 코드 ERR4521 발생 시 재시도");
        c2 = chunk("c2", "D1", 1, "4", "1.3", "로그인 화면 사용법 안내");
        fts.indexChunks(List.of(c1, c2));
        repo.saveTurnSourceRefs(7L, "u1", "t1", List.of(
                new QuestionReuseRepository.SourceSnapshot("c1", "D1", hash(c1), 0.7, "active"),
                new QuestionReuseRepository.SourceSnapshot("c2", "D1", hash(c2), null, "active")));
    }

    private static Document chunk(String id, String docId, int index, String page, String chapter, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of(
                MetaKey.DOC_ID, docId,
                MetaKey.VERSION, "latest",
                MetaKey.FILENAME, "manual.pdf",
                MetaKey.PAGE_OR_SLIDE, page,
                MetaKey.CHAPTER_NO, chapter,
                MetaKey.CHUNK_INDEX, index,
                MetaKey.EXCERPT_KEYWORDS, "kw")).build();
    }

    private static String hash(Document d) {
        return KeywordSearchRepository.contentHash(SearchTextBuilder.build(d));
    }

    @Test
    @DisplayName("currentChunkHashes — 저장된 검색 텍스트의 해시를 키 테이블에서 준다 (예전 계산값과 동일)")
    void currentChunkHashes_matchTheStoredSearchText() {
        Map<String, String> hashes = repo.currentChunkHashes(Set.of("c1", "c2", "missing"));

        assertThat(hashes).containsOnlyKeys("c1", "c2")
                .containsEntry("c1", hash(c1))
                .containsEntry("c2", hash(c2));
    }

    @Test
    @DisplayName("findSourcePreviewRows — vec 테이블이 없는 Chroma 배포에서도 위치·본문이 FTS 에서 채워진다")
    void previewRows_workWithoutVecTable() {
        List<QuestionReuseRepository.SourcePreviewRow> rows = repo.findSourcePreviewRows(7L);

        assertThat(rows).extracting(QuestionReuseRepository.SourcePreviewRow::chunkId)
                .containsExactlyInAnyOrder("c1", "c2");
        QuestionReuseRepository.SourcePreviewRow r1 = rows.stream()
                .filter(r -> r.chunkId().equals("c1")).findFirst().orElseThrow();
        assertThat(r1.filename()).isEqualTo("manual.pdf");
        assertThat(r1.pageOrSlide()).isEqualTo("3");
        assertThat(r1.chapterNo()).isEqualTo("1.2");
        assertThat(r1.content()).isEqualTo(SearchTextBuilder.build(c1));
    }

    @Test
    @DisplayName("findChunkFullText — FTS 본문을 키의 rowid 로 찾고, 없는 청크는 null")
    void fullText_viaKeyRowid() {
        assertThat(repo.findChunkFullText("c2")).isEqualTo(SearchTextBuilder.build(c2));
        assertThat(repo.findChunkFullText("missing")).isNull();
    }

    @Test
    @DisplayName("청크가 지워지면 해시는 사라지고 미리보기 행은 본문 없이 남는다 (배지 규칙: 출처는 사라지지 않는다)")
    void deletedChunk_dropsHashButKeepsPreviewRow() {
        fts.deleteBySpringDocIds(List.of("c1"));

        assertThat(repo.currentChunkHashes(Set.of("c1", "c2"))).containsOnlyKeys("c2");
        QuestionReuseRepository.SourcePreviewRow r1 = repo.findSourcePreviewRows(7L).stream()
                .filter(r -> r.chunkId().equals("c1")).findFirst().orElseThrow();
        assertThat(r1.content()).isNull();
        assertThat(repo.findChunkFullText("c1")).isNull();
    }

    @Test
    @DisplayName("신고 스냅샷(ChunkReportRepository.findChunkLocation) — 같은 키 경로로 위치와 검색 텍스트를 읽는다")
    void chunkReportLocation_viaKeyRowid() {
        ChunkReportRepository reports = new ChunkReportRepository(jdbc, jdbc);
        reports.init();

        Optional<ChunkReportRepository.ChunkLocation> loc = reports.findChunkLocation("c1");

        assertThat(loc).isPresent();
        assertThat(loc.get().docId()).isEqualTo("D1");
        assertThat(loc.get().version()).isEqualTo("latest");
        assertThat(loc.get().filename()).isEqualTo("manual.pdf");
        assertThat(loc.get().content()).isEqualTo(SearchTextBuilder.build(c1));
        assertThat(reports.findChunkLocation("missing")).isEmpty();
    }
}
