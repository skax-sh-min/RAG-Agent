package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** /admin 상태 카드 DB 파일 표시(파일명 추출·hover 팝오버 HTML) 단위 테스트. */
class VectorStoreAdminViewTest {

    private VectorStoreAdminView view(String backend, String dbPath) {
        return new VectorStoreAdminView(backend, true, 5, 42, null, "v0.1.9", 768, dbPath);
    }

    @Test
    @DisplayName("dbFileName — 전체 경로에서 파일명만 추출한다 (구분자 / 와 \\ 모두)")
    void fileName_extractsBareFileNameFromFullPath() {
        assertThat(view("sqlite-vec", "C:\\projects\\toy\\RAG-Agent\\data\\memory.db").dbFileName())
                .isEqualTo("memory.db");
        assertThat(view("sqlite-vec", "/app/data/memory.db").dbFileName()).isEqualTo("memory.db");
    }

    @Test
    @DisplayName("dbFileName — 경로가 null이면 null 반환")
    void fileName_nullWhenPathNull() {
        assertThat(view("sqlite-vec", null).dbFileName()).isNull();
    }

    @Test
    @DisplayName("dbPathPopoverHtml — sqlite-vec: 전체 경로 + 벡터까지 이 파일 하나에 있다")
    void popoverHtml_sqliteVecHoldsEverything() {
        assertThat(view("sqlite-vec", "/data/memory.db").dbPathPopoverHtml())
                .isEqualTo("/data/memory.db<br>운영 데이터 + 벡터 + 키워드 색인");
    }

    @Test
    @DisplayName("dbPathPopoverHtml — chroma: 벡터는 이 파일이 아니라 Chroma 서버에 있다고 말한다")
    void popoverHtml_chromaVectorsLiveOnTheServer() {
        assertThat(view("chroma", "/data/memory.db").dbPathPopoverHtml())
                .isEqualTo("/data/memory.db<br>운영 데이터 + 키워드 색인 (벡터는 Chroma 서버)");
    }
}
