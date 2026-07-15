package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SearchTextBuilder (§10.1).
 */
class SearchTextBuilderTest {

    @Test
    @DisplayName("CHUNK_CONTEXT가 있으면 맥락 헤더 + 정규화된 본문 순서로 결합된다")
    void build_withContext_prependsContextHeader() {
        Document doc = new Document("**중요**한 내용",
                Map.of(MetaKey.CHUNK_CONTEXT, "문서.pdf > 설정 방법"));

        String result = SearchTextBuilder.build(doc);

        assertThat(result).isEqualTo("문서.pdf > 설정 방법\n\n중요한 내용");
    }

    @Test
    @DisplayName("CHUNK_CONTEXT가 없으면 정규화된 본문만 반환된다(선행 구분자 없음)")
    void build_withoutContext_returnsNormalizedTextOnly() {
        Document doc = new Document("**중요**한 내용", Map.of());

        String result = SearchTextBuilder.build(doc);

        assertThat(result).isEqualTo("중요한 내용");
    }

    @Test
    @DisplayName("CHUNK_CONTEXT가 빈 문자열이면 정규화된 본문만 반환된다")
    void build_blankContext_returnsNormalizedTextOnly() {
        Document doc = new Document("본문", Map.of(MetaKey.CHUNK_CONTEXT, ""));

        String result = SearchTextBuilder.build(doc);

        assertThat(result).isEqualTo("본문");
    }

    // ── §10.8.5 — precompute() short-circuit ────────────────────────────

    @Test
    @DisplayName("precompute — SEARCH_TEXT를 메타데이터에 저장하고, build()는 저장된 값을 재계산 없이 반환한다")
    void precompute_thenBuild_returnsStoredValueWithoutRecomputing() {
        Document doc = Document.builder().id("d1").text("**중요**한 내용")
                .metadata(Map.of(MetaKey.CHUNK_CONTEXT, "문서.pdf > 설정 방법"))
                .build();

        Document precomputed = SearchTextBuilder.precompute(doc);

        assertThat(precomputed.getId()).isEqualTo("d1");
        assertThat(precomputed.getMetadata().get(MetaKey.SEARCH_TEXT))
                .isEqualTo("문서.pdf > 설정 방법\n\n중요한 내용");
        assertThat(SearchTextBuilder.build(precomputed))
                .isEqualTo("문서.pdf > 설정 방법\n\n중요한 내용");
    }

    @Test
    @DisplayName("precompute — 원본 텍스트/다른 메타데이터는 보존된다")
    void precompute_preservesOriginalTextAndOtherMetadata() {
        Document doc = Document.builder().id("d1").text("원문 그대로")
                .metadata(Map.of(MetaKey.FILENAME, "가이드.pdf"))
                .build();

        Document precomputed = SearchTextBuilder.precompute(doc);

        assertThat(precomputed.getText()).isEqualTo("원문 그대로");
        assertThat(precomputed.getMetadata().get(MetaKey.FILENAME)).isEqualTo("가이드.pdf");
    }

    @Test
    @DisplayName("build — 사전계산된 값이 공백뿐이면 무시하고 재계산한다")
    void build_blankPrecomputedValue_recomputes() {
        Document doc = new Document("본문", Map.of(MetaKey.SEARCH_TEXT, "   "));

        assertThat(SearchTextBuilder.build(doc)).isEqualTo("본문");
    }
}
