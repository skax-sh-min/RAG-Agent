package com.example.ragagent.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ChunkSplitter — extracted from DocumentIndexer (§EDIT.md #4).
 * mergeTinyChunks tests were migrated here (previously invoked via reflection
 * on the private DocumentIndexer method) and now call the package-private
 * method directly since ChunkSplitter has no external dependencies.
 */
class ChunkSplitterTest {

    private ChunkSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new ChunkSplitter();
    }

    @Test
    @DisplayName("작은 중간 청크(< overlap)는 앞 청크로 병합된다")
    void mergeTinyChunks_mergesMiddleTinyChunkIntoPrevious() {
        List<String> out = splitter.mergeTinyChunks(
                List.of("A".repeat(220), "B".repeat(90), "C".repeat(210)), 200);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).contains("A".repeat(220)).contains("B".repeat(90));
        assertThat(out.get(1)).contains("C".repeat(210));
    }

    @Test
    @DisplayName("시작부 작은 청크(< overlap)는 다음 청크 앞에 병합된다")
    void mergeTinyChunks_mergesLeadingTinyChunkIntoNext() {
        List<String> out = splitter.mergeTinyChunks(
                List.of("X".repeat(80), "Y".repeat(230), "Z".repeat(220)), 200);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).startsWith("X".repeat(80));
        assertThat(out.get(0)).contains("Y".repeat(230));
        assertThat(out.get(1)).contains("Z".repeat(220));
    }

    @Test
    @DisplayName("청크 길이가 overlap과 같으면 병합하지 않는다")
    void mergeTinyChunks_doesNotMergeWhenLengthEqualsOverlap() {
        List<String> out = splitter.mergeTinyChunks(
                List.of("A".repeat(220), "B".repeat(200), "C".repeat(220)), 200);

        assertThat(out).hasSize(3);
        assertThat(out.get(1)).isEqualTo("B".repeat(200));
    }

    @Test
    @DisplayName("작은 청크를 병합할 때 overlap 구간이 중복되지 않는다")
    void mergeTinyChunks_deduplicatesOverlapText() {
        String overlap = "__OVERLAP__";
        String first = "A".repeat(220) + overlap;
        String tiny = overlap + "B".repeat(60);
        List<String> out = splitter.mergeTinyChunks(List.of(first, tiny), 200);

        assertThat(out).hasSize(1);
        String merged = out.get(0);
        assertThat(merged.indexOf(overlap)).isEqualTo(merged.lastIndexOf(overlap));
        assertThat(merged).contains("B".repeat(60));
    }

    @Test
    @DisplayName("splitDocuments — pptx는 슬라이드 유지, 분할하지 않는다")
    void splitDocuments_pptx_keepsSlidesUnsplit() {
        List<Document> docs = List.of(new Document("A".repeat(3000)), new Document("B".repeat(10)));

        List<Document> result = splitter.splitDocuments(docs, "deck.pptx", 2000, 200, 100);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).hasSize(3000);
    }

    @Test
    @DisplayName("splitDocuments — md는 섹션 병합 후 chunkSize 초과분만 슬라이딩 윈도우 적용")
    void splitDocuments_md_mergesShortSectionsAndSplitsLongOnes() {
        List<Document> docs = List.of(new Document("짧은 섹션 내용"), new Document("X".repeat(3000)));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 2000, 200, 100);

        assertThat(result).isNotEmpty();
        assertThat(result.stream().mapToInt(d -> d.getText().length()).max().orElse(0)).isLessThanOrEqualTo(2000);
    }

    @Test
    @DisplayName("splitDocuments — 기타 확장자는 섹션 병합 없이 슬라이딩 윈도우만 적용")
    void splitDocuments_other_appliesSlidingWindowOnly() {
        List<Document> docs = List.of(new Document("Y".repeat(2500)));

        List<Document> result = splitter.splitDocuments(docs, "scan.pdf", 2000, 200, 100);

        assertThat(result.size()).isGreaterThanOrEqualTo(2);
    }
}
