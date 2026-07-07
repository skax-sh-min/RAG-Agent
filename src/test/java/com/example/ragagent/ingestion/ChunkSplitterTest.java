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

    @Test
    @DisplayName("splitDocuments — 소제목이 있는 긴 섹션이 여러 청크로 나뉘면 두 번째 청크부터 소제목(N)이 재삽입된다")
    void splitDocuments_longSectionWithHeading_reinjectsNumberedHeadingFromSecondPiece() {
        String section = "## 소챕터명\n\n" + "가".repeat(3000);
        List<Document> docs = List.of(new Document(section));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 2000, 200, 100);

        assertThat(result.size()).isGreaterThanOrEqualTo(2);
        assertThat(result.get(0).getText()).startsWith("## 소챕터명");
        assertThat(result.get(0).getText()).doesNotContain("(1)");
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).getText()).startsWith("## 소챕터명 (" + i + ")");
        }
    }

    @Test
    @DisplayName("splitDocuments — 소제목 없는 긴 섹션은 재삽입 없이 그대로 슬라이딩 윈도우만 적용된다")
    void splitDocuments_longSectionWithoutHeading_noReinjection() {
        List<Document> docs = List.of(new Document("X".repeat(3000)));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 2000, 200, 100);

        assertThat(result).allSatisfy(d -> assertThat(d.getText()).doesNotContain("("));
    }

    @Test
    @DisplayName("extractLeadingHeading — 선행 텍스트 없이 헤딩으로 시작해야 인식된다")
    void extractLeadingHeading_recognizesLeadingHeadingOnly() {
        assertThat(splitter.extractLeadingHeading("### 제목 텍스트\n본문"))
                .isEqualTo(new ChunkSplitter.HeadingInfo("###", "제목 텍스트"));
        assertThat(splitter.extractLeadingHeading("본문 먼저\n## 제목")).isNull();
        assertThat(splitter.extractLeadingHeading("#헤딩아님(공백없음)")).isNull();
        assertThat(splitter.extractLeadingHeading(null)).isNull();
    }

    @Test
    @DisplayName("extractLeadingHeading — 레벨 1~6만 헤딩으로 인정, 7개 이상은 무시")
    void extractLeadingHeading_onlyLevels1Through6() {
        assertThat(splitter.extractLeadingHeading("###### 여섯단계"))
                .isEqualTo(new ChunkSplitter.HeadingInfo("######", "여섯단계"));
        assertThat(splitter.extractLeadingHeading("####### 일곱단계는아님")).isNull();
    }

    @Test
    @DisplayName("extractLeadingHeading — 코드펜스/표로 시작하면 헤딩이 아니다")
    void extractLeadingHeading_fenceOrTableIsNotHeading() {
        assertThat(splitter.extractLeadingHeading("```python\n# 주석\nx = 1\n```")).isNull();
        assertThat(splitter.extractLeadingHeading("| A | B |\n|---|---|\n| 1 | 2 |")).isNull();
    }

    @Test
    @DisplayName("reinjectHeadingForSplitPieces — 조각이 1개면 변경 없이 그대로 반환")
    void reinjectHeadingForSplitPieces_singlePiece_returnsUnchanged() {
        List<Document> pieces = List.of(new Document("## 소제목\n내용"));

        List<Document> result = splitter.reinjectHeadingForSplitPieces("## 소제목\n내용", pieces);

        assertThat(result).isSameAs(pieces);
    }

    @Test
    @DisplayName("splitDocuments — maxChunkChars 설정 시 pptx 슬라이드도 그 길이 이하로 강제 분할된다")
    void splitDocuments_maxChunkChars_capsUnsplitSlides() {
        List<Document> docs = List.of(new Document("문장. ".repeat(500))); // ~2000자 단일 슬라이드

        List<Document> result = splitter.splitDocuments(docs, "deck.pptx", 2000, 200, 100, 500);

        assertThat(result).hasSizeGreaterThan(1);
        assertThat(result).allSatisfy(d -> assertThat(d.getText().length()).isLessThanOrEqualTo(500));
    }

    @Test
    @DisplayName("splitDocuments — md 헤딩 재삽입 이후에도 maxChunkChars 상한이 최종 보장된다")
    void splitDocuments_md_reinjectThenHardCap() {
        String section = "## 소제목\n\n" + "가".repeat(3000);
        List<Document> docs = List.of(new Document(section));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 2000, 200, 100, 400);

        assertThat(result).allSatisfy(d -> assertThat(d.getText().length()).isLessThanOrEqualTo(400));
    }

    @Test
    @DisplayName("splitDocuments — maxChunkChars=0 이면 상한 없이 기존 동작 유지")
    void splitDocuments_maxChunkCharsZero_disabled() {
        List<Document> docs = List.of(new Document("A".repeat(1500)));

        List<Document> capped = splitter.splitDocuments(docs, "deck.pptx", 2000, 200, 100, 0);

        assertThat(capped).hasSize(1);
        assertThat(capped.get(0).getText()).hasSize(1500);
    }

    @Test
    @DisplayName("enforceMaxChars — maxChars<=0 이면 입력 리스트를 그대로(동일 참조) 반환")
    void enforceMaxChars_disabled_returnsSameReference() {
        List<Document> docs = List.of(new Document("A".repeat(5000)));

        assertThat(splitter.enforceMaxChars(docs, 0, "x.md")).isSameAs(docs);
    }

    @Test
    @DisplayName("hardSplitByLines — 줄 단위로 채우고, 상한을 넘는 한 줄은 문자 단위로 자른다")
    void hardSplitByLines_packsLinesAndCutsOversizedLine() {
        String threeLines = "A".repeat(30) + "\n" + "B".repeat(30) + "\n" + "C".repeat(30);
        List<String> packed = splitter.hardSplitByLines(threeLines, 70);
        assertThat(packed).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(70));
        assertThat(String.join("\n", packed)).contains("A".repeat(30)).contains("C".repeat(30));

        List<String> cut = splitter.hardSplitByLines("X".repeat(250), 100);
        assertThat(cut).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(100));
        assertThat(String.join("", cut)).isEqualTo("X".repeat(250)); // 손실 없이 재구성
    }
}
