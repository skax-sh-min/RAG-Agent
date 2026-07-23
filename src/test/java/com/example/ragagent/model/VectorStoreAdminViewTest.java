package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** /admin 상태 카드 DB 경로 표시(파일명 추출·hover 팝오버 HTML) 단위 테스트. */
class VectorStoreAdminViewTest {

    private VectorStoreAdminView view(String operationalDbPath, String vectorDbPath) {
        return new VectorStoreAdminView("sqlite-vec", true, 5, 42, null, "v0.1.9", 768,
                operationalDbPath, vectorDbPath);
    }

    @Test
    @DisplayName("operationalDbFileName/vectorDbFileName — 전체 경로에서 파일명만 추출한다")
    void fileNames_extractBareFileNameFromFullPath() {
        VectorStoreAdminView v = view("C:\\projects\\toy\\RAG-Agent\\data\\memory.db",
                "C:\\projects\\toy\\RAG-Agent\\data\\vector.db");

        assertThat(v.operationalDbFileName()).isEqualTo("memory.db");
        assertThat(v.vectorDbFileName()).isEqualTo("vector.db");
    }

    @Test
    @DisplayName("operationalDbFileName/vectorDbFileName — 경로가 null이면 null 반환")
    void fileNames_nullWhenPathNull() {
        VectorStoreAdminView v = view(null, null);

        assertThat(v.operationalDbFileName()).isNull();
        assertThat(v.vectorDbFileName()).isNull();
    }

    @Test
    @DisplayName("dbPathsPopoverHtml — 벡터 DB가 분리된 경우 두 경로를 <br>로 이어붙인다")
    void popoverHtml_includesBothPathsWhenSeparated() {
        VectorStoreAdminView v = view("/data/memory.db", "/data/vector.db");

        assertThat(v.dbPathsPopoverHtml())
                .isEqualTo("운영 DB: /data/memory.db<br>벡터 DB: /data/vector.db");
    }

    @Test
    @DisplayName("dbPathsPopoverHtml — 벡터 DB 경로가 없으면(chroma) 운영 DB 한 줄만 반환한다")
    void popoverHtml_operationalOnlyWhenNoVectorPath() {
        VectorStoreAdminView v = view("/data/memory.db", null);

        assertThat(v.dbPathsPopoverHtml()).isEqualTo("운영 DB: /data/memory.db");
    }
}
