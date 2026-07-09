package com.example.ragagent.ingestion;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 회귀 — FTS5 키워드 인덱스 (인덱싱 / BM25 검색 / 버전 필터 / 삭제 / MATCH 빌더).
 */
class KeywordSearchRepositoryTest {

    @TempDir Path tmp;
    private KeywordSearchRepository repo;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("fts.db"));
        repo = new KeywordSearchRepository(new JdbcTemplate(ds));
        repo.init();
        assumeTrue(repo.isAvailable(), "FTS5 not available in this SQLite build");
    }

    private Document chunk(String springId, String docId, String version, int chunkIndex,
                          String content, String keywords) {
        return Document.builder()
                .id(springId)
                .text(content)
                .metadata(Map.of(
                        MetaKey.DOC_ID, docId,
                        MetaKey.VERSION, version,
                        MetaKey.FILENAME, "manual.pdf",
                        MetaKey.PAGE_OR_SLIDE, "1",
                        MetaKey.CHUNK_INDEX, chunkIndex,
                        MetaKey.EXCERPT_KEYWORDS, keywords))
                .build();
    }

    private Document taggedChunk(String springId, String docId, String version, int chunkIndex,
                                 String content, String tagsCsv) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put(MetaKey.DOC_ID, docId);
        m.put(MetaKey.VERSION, version);
        m.put(MetaKey.FILENAME, "manual.pdf");
        m.put(MetaKey.PAGE_OR_SLIDE, "1");
        m.put(MetaKey.CHUNK_INDEX, chunkIndex);
        m.put(MetaKey.EXCERPT_KEYWORDS, "kw");
        m.put(MetaKey.TAGS, tagsCsv);
        return Document.builder().id(springId).text(content).metadata(m).build();
    }

    @Test
    @DisplayName("distinctTags — doc_tags에서 정렬·중복 제거, 버전 스코프 적용")
    void distinctTags_collectsAndScopes() {
        repo.indexChunks(List.of(
                taggedChunk("s1", "D1", "v1", 0, "내용 알파", "billing,policy"),
                taggedChunk("s2", "D1", "v1", 1, "내용 베타", "policy"),
                taggedChunk("s3", "D2", "v2", 0, "내용 감마", "onboarding")));

        assertThat(repo.distinctTags(null)).containsExactly("billing", "onboarding", "policy");
        assertThat(repo.distinctTags("v1")).containsExactly("billing", "policy");
        assertThat(repo.distinctTags("v2")).containsExactly("onboarding");
    }

    @Test
    @DisplayName("정확 용어로 검색 시 해당 청크 반환 (메타 포함)")
    void index_and_search_findsByContent() {
        repo.indexChunks(List.of(
                chunk("s1", "D1", "latest", 0, "결제 오류 코드 ERR4521 발생 시 재시도", "ERR4521, 결제, 오류"),
                chunk("s2", "D1", "latest", 1, "로그인 화면 사용법 안내", "로그인, 화면")));

        List<Document> hits = repo.search("latest", "ERR4521", 10);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getId()).isEqualTo("s1");
        assertThat(hits.get(0).getMetadata().get(MetaKey.DOC_ID)).isEqualTo("D1");
        assertThat(hits.get(0).getMetadata().get(MetaKey.CHUNK_INDEX)).isEqualTo("0");
    }

    @Test
    @DisplayName("indexChunks — CHUNK_CONTEXT가 있으면 content 컬럼이 맥락+정규화 텍스트로 저장된다(Contextual BM25, §10.1)")
    void indexChunks_storesContextAndNormalizedTextAsContent() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put(MetaKey.DOC_ID, "D1");
        m.put(MetaKey.VERSION, "latest");
        m.put(MetaKey.FILENAME, "manual.pdf");
        m.put(MetaKey.PAGE_OR_SLIDE, "1");
        m.put(MetaKey.CHUNK_INDEX, 0);
        m.put(MetaKey.EXCERPT_KEYWORDS, "kw");
        m.put(MetaKey.CHUNK_CONTEXT, "설정가이드 > 네트워크절 고유맥락어");
        Document doc = Document.builder().id("s1").text("**본문**만 있고 다른 단어는 없음").metadata(m).build();

        repo.indexChunks(List.of(doc));

        // "고유맥락어"는 원문(raw text)에는 없고 CHUNK_CONTEXT에만 있는 용어 — 검색되면 content가
        // 원문이 아니라 맥락+정규화 텍스트임이 증명된다.
        List<Document> hits = repo.search("latest", "고유맥락어", 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getText()).contains("고유맥락어").doesNotContain("**본문**");
    }

    @Test
    @DisplayName("버전 필터 — 다른 버전 청크는 제외")
    void search_filtersByVersion() {
        repo.indexChunks(List.of(chunk("s1", "D1", "v1", 0, "공통키워드 알파값", "알파값")));
        repo.indexChunks(List.of(chunk("s2", "D2", "v2", 0, "공통키워드 알파값", "알파값")));

        assertThat(repo.search("v1", "공통키워드", 10)).extracting(Document::getId).containsExactly("s1");
        assertThat(repo.search("v2", "공통키워드", 10)).extracting(Document::getId).containsExactly("s2");
    }

    @Test
    @DisplayName("search — 활용형 종결어미가 붙어도 어간만으로 청크를 찾는다 (trigram 부분열 매칭, §10.4)")
    void search_matchesBareStemAgainstInflectedForm() {
        repo.indexChunks(List.of(chunk("s1", "D1", "latest", 0, "문서를 업로드하면 자동으로 인덱싱됩니다", "문서, 업로드")));

        // "인덱싱"(어간, 3글자)만 검색해도 본문의 "인덱싱됩니다"(활용형)에서 부분열로 발견된다 —
        // unicode61이었다면 전체 토큰이 달라 매칭되지 않았을 케이스.
        List<Document> hits = repo.search("latest", "인덱싱", 10);

        assertThat(hits).extracting(Document::getId).containsExactly("s1");
    }

    @Test
    @DisplayName("init — 기존 unicode61(doc_tags 포함) 테이블을 감지하면 trigram으로 자동 재구축하고 기존 행을 보존한다 (§10.4)")
    void init_migratesLegacyUnicode61TableToTrigramPreservingRows() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("legacy.db"));
        JdbcTemplate legacyJdbc = new JdbcTemplate(ds);

        legacyJdbc.execute("""
                CREATE VIRTUAL TABLE chunk_fts USING fts5(
                    spring_doc_id UNINDEXED, doc_id UNINDEXED, version UNINDEXED,
                    filename UNINDEXED, page UNINDEXED, chunk_index UNINDEXED, doc_tags UNINDEXED,
                    content, keywords, tokenize = 'unicode61'
                )
                """);
        legacyJdbc.update("""
                INSERT INTO chunk_fts (spring_doc_id, doc_id, version, filename, page, chunk_index, doc_tags, content, keywords)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "s1", "D1", "latest", "manual.pdf", "1", "0", "billing",
                "문서를 업로드하면 자동으로 인덱싱됩니다", "문서, 업로드");

        KeywordSearchRepository migrated = new KeywordSearchRepository(legacyJdbc);
        migrated.init();

        assertThat(migrated.isAvailable()).isTrue();
        // Bare-stem query ("인덱싱" vs. indexed "인덱싱됩니다") only matches under trigram — proves
        // the rebuild actually re-tokenized existing rows (not just recreated an empty table), since
        // this same query would find nothing against the original unicode61 whole-token index.
        List<Document> hits = migrated.search("latest", "인덱싱", 10);
        assertThat(hits).extracting(Document::getId).containsExactly("s1");
        assertThat(migrated.tagsByDocIds(List.of("D1")).get("D1")).containsExactly("billing");
    }

    @Test
    @DisplayName("deleteByDocId — 해당 문서 청크만 제거")
    void deleteByDocId_removesDocChunks() {
        repo.indexChunks(List.of(
                chunk("s1", "D1", "latest", 0, "삭제대상 키워드콘텐츠", "콘텐츠"),
                chunk("s2", "D2", "latest", 0, "유지대상 키워드콘텐츠", "콘텐츠")));

        repo.deleteByDocId("D1");

        assertThat(repo.search("latest", "키워드콘텐츠", 10))
                .extracting(Document::getId).containsExactly("s2");
    }

    @Test
    @DisplayName("deleteBySpringDocIds — 지정한 chunk id만 제거, 같은 doc_id의 다른 행은 보존")
    void deleteBySpringDocIds_removesOnlySpecifiedChunks() {
        // s1/s2 share doc_id D1 (simulates old+new rows momentarily coexisting during reindex).
        repo.indexChunks(List.of(
                chunk("s1", "D1", "latest", 0, "구버전 키워드콘텐츠", "콘텐츠"),
                chunk("s2", "D1", "latest", 1, "신버전 키워드콘텐츠", "콘텐츠")));

        repo.deleteBySpringDocIds(List.of("s1"));

        assertThat(repo.search("latest", "키워드콘텐츠", 10))
                .extracting(Document::getId).containsExactly("s2");
    }

    @Test
    @DisplayName("updateDocTags — 매칭되는 청크 행 수를 반환하고 doc_tags를 갱신한다")
    void updateDocTags_returnsMatchedRowCount() {
        repo.indexChunks(List.of(
                chunk("s1", "D1", "latest", 0, "내용 알파", "kw"),
                chunk("s2", "D1", "latest", 1, "내용 베타", "kw")));

        int updated = repo.updateDocTags("D1", "billing,policy");

        assertThat(updated).isEqualTo(2);
        assertThat(repo.tagsByDocIds(List.of("D1")).get("D1")).containsExactlyInAnyOrder("billing", "policy");
    }

    @Test
    @DisplayName("updateDocTags — 존재하지 않는 docId → 0 (고아 registry entry 감지용)")
    void updateDocTags_unknownDocId_returnsZero() {
        repo.indexChunks(List.of(chunk("s1", "D1", "latest", 0, "내용", "kw")));

        int updated = repo.updateDocTags("D-missing", "tag");

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("deleteBySpringDocIds(빈/널) — no-op")
    void deleteBySpringDocIds_emptyOrNull_isNoOp() {
        repo.indexChunks(List.of(chunk("s1", "D1", "latest", 0, "유지 콘텐츠", "kw")));

        repo.deleteBySpringDocIds(List.of());
        repo.deleteBySpringDocIds(null);

        assertThat(repo.search("latest", "콘텐츠", 10)).extracting(Document::getId).containsExactly("s1");
    }

    @Test
    @DisplayName("빈/널 질의 → 빈 결과")
    void search_blankQuery_returnsEmpty() {
        repo.indexChunks(List.of(chunk("s1", "D1", "latest", 0, "내용", "kw")));
        assertThat(repo.search("latest", "   ", 10)).isEmpty();
        assertThat(repo.search("latest", null, 10)).isEmpty();
    }

    @Test
    @DisplayName("toMatchQuery — 토큰 인용·OR 결합, 특수문자 안전")
    void toMatchQuery_quotesAndOrs() {
        assertThat(KeywordSearchRepository.toMatchQuery("결제오류 코드확인")).isEqualTo("\"결제오류\" OR \"코드확인\"");
        assertThat(KeywordSearchRepository.toMatchQuery("a")).isNull();            // 1글자 제외
        assertThat(KeywordSearchRepository.toMatchQuery("  ")).isNull();
        assertThat(KeywordSearchRepository.toMatchQuery("AND NOT \"")).isEqualTo("\"AND\" OR \"NOT\"");
    }

    @Test
    @DisplayName("toMatchQuery — 3자 미만 토큰은 제외된다 (trigram 최소 매칭 단위, §10.4)")
    void toMatchQuery_dropsTermsShorterThanThree() {
        assertThat(KeywordSearchRepository.toMatchQuery("문서 오류")).isNull();                     // 둘 다 2글자 → 전부 제외
        assertThat(KeywordSearchRepository.toMatchQuery("문서 코드확인")).isEqualTo("\"코드확인\""); // 2글자만 제외, 4글자는 유지
    }
}
