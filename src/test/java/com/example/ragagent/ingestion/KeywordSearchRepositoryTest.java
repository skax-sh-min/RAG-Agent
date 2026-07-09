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
    @DisplayName("버전 필터 — 다른 버전 청크는 제외")
    void search_filtersByVersion() {
        repo.indexChunks(List.of(chunk("s1", "D1", "v1", 0, "공통키워드 알파", "알파")));
        repo.indexChunks(List.of(chunk("s2", "D2", "v2", 0, "공통키워드 알파", "알파")));

        assertThat(repo.search("v1", "알파", 10)).extracting(Document::getId).containsExactly("s1");
        assertThat(repo.search("v2", "알파", 10)).extracting(Document::getId).containsExactly("s2");
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
        assertThat(KeywordSearchRepository.toMatchQuery("결제 오류")).isEqualTo("\"결제\" OR \"오류\"");
        assertThat(KeywordSearchRepository.toMatchQuery("a")).isNull();            // 1글자 제외
        assertThat(KeywordSearchRepository.toMatchQuery("  ")).isNull();
        assertThat(KeywordSearchRepository.toMatchQuery("OR AND \"")).isEqualTo("\"OR\" OR \"AND\"");
    }
}
