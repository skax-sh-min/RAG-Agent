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
}
