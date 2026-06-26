package com.example.ragagent.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VectorStoreFacade} 책임 단위 테스트.
 *
 * <p>Chroma 내부 동작(R-1/S-3)은 {@link ChromaVectorStoreProviderTest}에서 검증하며,
 * 여기서는 façade 고유 책임만 확인한다: (1) {@code SAFE_VERSION} 정규화,
 * (2) {@link VectorStoreProvider}로의 위임(pass-through).
 */
class VectorStoreFacadeTest {

    private final VectorStoreProvider provider = mock(VectorStoreProvider.class);
    private final VectorStoreFacade facade = new VectorStoreFacade(provider);

    @Test
    @DisplayName("search: 정상 버전 그대로 위임 + 반환값 pass-through")
    void search_delegatesWithVersion() {
        List<Document> expected = List.of(Document.builder().id("d1").text("t").build());
        when(provider.search("owner", "질문", "1.0", 7)).thenReturn(expected);

        assertThat(facade.search("owner", "질문", "1.0", 7)).isSameAs(expected);
        verify(provider).search("owner", "질문", "1.0", 7);
    }

    @Test
    @DisplayName("search: 허용되지 않는 버전 → 'latest'로 정규화 후 위임")
    void search_sanitizesInvalidVersion() {
        facade.search("owner", "질문", "한글 / 버전", 7);
        verify(provider).search(eq("owner"), eq("질문"), eq("latest"), eq(7));
    }

    @Test
    @DisplayName("search: null 버전 → 'latest'")
    void search_nullVersion() {
        facade.search("owner", "질문", null, 7);
        verify(provider).search(eq("owner"), eq("질문"), eq("latest"), eq(7));
    }

    @Test
    @DisplayName("searchBatch: 버전 검증 적용 후 위임")
    void searchBatch_sanitizesVersion() {
        when(provider.searchBatch(eq("owner"), eq(List.of("q1", "q2")), eq("latest"), eq(7)))
                .thenReturn(List.of(List.of(), List.of()));

        facade.searchBatch("owner", List.of("q1", "q2"), "bad version!", 7);
        verify(provider).searchBatch(eq("owner"), eq(List.of("q1", "q2")), eq("latest"), eq(7));
    }

    @Test
    @DisplayName("add: 유효한 버전은 그대로 위임")
    void add_delegatesRawVersion() {
        List<Document> docs = List.of(Document.builder().id("d1").text("t").build());
        facade.add("owner", "v2", docs);
        verify(provider).add("owner", "v2", docs);
    }

    @Test
    @DisplayName("deleteByDocIds: 유효한 버전은 그대로 위임")
    void delete_delegatesRawVersion() {
        facade.deleteByDocIds("owner", "v2", List.of("d1", "d2"));
        verify(provider).deleteByDocIds("owner", "v2", List.of("d1", "d2"));
    }
}
