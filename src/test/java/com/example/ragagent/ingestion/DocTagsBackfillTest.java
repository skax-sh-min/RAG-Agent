package com.example.ragagent.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 태그 출처가 {@code chunk_fts.doc_tags} → {@code doc_registry.tags} 로 옮겨간 뒤의 일회성 이관.
 */
class DocTagsBackfillTest {

    private final DocRegistry registry = mock(DocRegistry.class);
    private final KeywordSearchRepository keywordRepo = mock(KeywordSearchRepository.class);
    private final DocTagsBackfill backfill = new DocTagsBackfill(registry, keywordRepo);

    @Test
    @DisplayName("FTS 에 있던 태그를 registry 로 옮긴다")
    void copiesTagsFromFtsIntoTheRegistry() {
        when(registry.docIdsWithoutTags()).thenReturn(List.of("doc_a", "doc_b"));
        when(keywordRepo.tagsByDocIds(List.of("doc_a", "doc_b")))
                .thenReturn(Map.of("doc_a", List.of("가이드", "운영")));

        backfill.backfill();

        verify(registry).updateTags("doc_a", DocRegistry.SHARED, "가이드,운영");
        // 태그가 없던 문서도 빈 문자열로 기록한다 — NULL 로 두면 다음 기동에서 또 훑는다.
        verify(registry).updateTags("doc_b", DocRegistry.SHARED, "");
    }

    @Test
    @DisplayName("채울 것이 없으면 FTS 를 건드리지 않는다 — 재기동마다 코퍼스를 훑지 않는다")
    void doesNothingWhenNoRowsAreMissingTags() {
        when(registry.docIdsWithoutTags()).thenReturn(List.of());

        backfill.backfill();

        verifyNoInteractions(keywordRepo);
        verify(registry, never()).updateTags(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("FTS 조회가 실패해도 기동을 막지 않는다")
    void survivesAnFtsFailure() {
        when(registry.docIdsWithoutTags()).thenReturn(List.of("doc_a"));
        when(keywordRepo.tagsByDocIds(any())).thenThrow(new RuntimeException("FTS unavailable"));

        backfill.backfill();   // 던지지 않아야 한다

        verify(registry, never()).updateTags(eq("doc_a"), anyString(), anyString());
    }
}
