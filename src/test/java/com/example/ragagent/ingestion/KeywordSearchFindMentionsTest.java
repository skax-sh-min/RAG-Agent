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
 * 영향도 분석의 전수 조회 — 순위 검색과 달리 "언급된 곳 전부"를 찾고, 3글자 미만은 찾지 못한다고 밝힌다.
 */
class KeywordSearchFindMentionsTest {

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

    private Document chunk(String id, String docId, String version, int idx, String content) {
        return Document.builder().id(id).text(content).metadata(Map.of(
                MetaKey.DOC_ID, docId, MetaKey.VERSION, version, MetaKey.FILENAME, docId + ".pdf",
                MetaKey.PAGE_OR_SLIDE, "1", MetaKey.CHUNK_INDEX, idx, MetaKey.EXCERPT_KEYWORDS, "kw")).build();
    }

    @Test
    @DisplayName("심어 둔 언급을 전부 찾는다 — 순위 검색의 topK 와 무관하다")
    void findsEveryPlantedMention() {
        repo.indexChunks(List.of(
                chunk("c1", "d1", "latest", 0, "ABC API 는 인증 토큰을 요구한다."),
                chunk("c2", "d1", "latest", 1, "무관한 설명이다."),
                chunk("c3", "d2", "latest", 0, "배포 시 abc api 호출 순서를 지킨다."),
                chunk("c4", "d3", "latest", 0, "ABC API 폐기 예정"),
                chunk("c5", "d4", "latest", 0, "다른 버전 문서", "other"),
                chunk("c6", "d5", "other", 0, "ABC API 이전 버전 설명")));

        KeywordSearchRepository.Mentions m = repo.findMentions("latest", "ABC API", 50);

        assertThat(m.tooShort()).isFalse();
        assertThat(m.truncated()).isFalse();
        assertThat(m.hits()).extracting(Document::getId).containsExactly("c1", "c3", "c4");
    }

    @Test
    @DisplayName("3글자 미만은 빈 결과가 아니라 tooShort 로 밝힌다 — '영향 없음'으로 읽히지 않게")
    void shortTermIsReportedNotSilentlyEmpty() {
        repo.indexChunks(List.of(chunk("c1", "d1", "latest", 0, "DB 설정")));

        KeywordSearchRepository.Mentions m = repo.findMentions("latest", "DB", 50);

        assertThat(m.tooShort()).isTrue();
        assertThat(m.hits()).isEmpty();
    }

    @Test
    @DisplayName("상한을 넘으면 truncated 이고 앞의 limit 개만 돌려준다")
    void truncatesAtTheLimit() {
        repo.indexChunks(List.of(
                chunk("c1", "d1", "latest", 0, "NOTX 사용"),
                chunk("c2", "d1", "latest", 1, "NOTX 사용 2"),
                chunk("c3", "d1", "latest", 2, "NOTX 사용 3")));

        KeywordSearchRepository.Mentions m = repo.findMentions("latest", "NOTX", 2);

        assertThat(m.truncated()).isTrue();
        assertThat(m.hits()).hasSize(2);
    }

    @Test
    @DisplayName("식 문법 문자(따옴표·별표·AND)가 섞여도 깨지지 않고 글자 그대로 찾는다")
    void metaCharactersAreLiteral() {
        repo.indexChunks(List.of(chunk("c1", "d1", "latest", 0, "값은 \"a AND b*\" 형태로 쓴다.")));

        KeywordSearchRepository.Mentions m = repo.findMentions("latest", "\"a AND b*\"", 10);

        assertThat(m.hits()).extracting(Document::getId).containsExactly("c1");
        assertThat(repo.findMentions("latest", "x\" OR \"y", 10).hits()).isEmpty();
    }

    private Document chunk(String id, String docId, String version, int idx, String content, String versionOverride) {
        return chunk(id, docId, versionOverride, idx, content);
    }
}
