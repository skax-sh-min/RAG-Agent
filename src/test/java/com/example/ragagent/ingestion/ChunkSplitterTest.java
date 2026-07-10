package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

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
    @DisplayName("splitDocuments — pptx는 섹션 병합 전략을 타지만, 서로 다른 슬라이드(page_or_slide)는 병합되지 않는다")
    void splitDocuments_pptx_routesToSectionStrategy_neverMergesDifferentSlides() {
        List<Document> docs = List.of(
                new Document("## 첫 슬라이드\n\n짧은 내용", Map.of(MetaKey.PAGE_OR_SLIDE, 1)),
                new Document("## 둘째 슬라이드\n\n짧은 내용", Map.of(MetaKey.PAGE_OR_SLIDE, 2)));

        List<Document> result = splitter.splitDocuments(docs, "deck.pptx", 2000, 200, 100);

        assertThat(result).hasSize(2); // both short, but different slides — merge is blocked
    }

    @Test
    @DisplayName("splitDocuments — chunkSize를 넘는 단일 슬라이드는 슬라이딩 윈도우로 분할된다 (더 이상 무조건 유지되지 않음)")
    void splitDocuments_pptx_splitsOversizedSingleSlide() {
        List<Document> docs = List.of(
                new Document("## 긴 슬라이드\n\n" + "A".repeat(3000), Map.of(MetaKey.PAGE_OR_SLIDE, 1)));

        List<Document> result = splitter.splitDocuments(docs, "deck.pptx", 2000, 200, 100);

        assertThat(result).hasSizeGreaterThan(1);
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
    @DisplayName("splitDocuments — 처리되지 않는 확장자는 섹션 병합 없이 슬라이딩 윈도우만 적용")
    void splitDocuments_other_appliesSlidingWindowOnly() {
        List<Document> docs = List.of(new Document("Y".repeat(2500)));

        List<Document> result = splitter.splitDocuments(docs, "notes.xyz", 2000, 200, 100);

        assertThat(result.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("splitDocuments — 스캔 아닌 pdf(source_type=file)는 섹션 병합 전략을 탄다 (짧은 섹션끼리 병합)")
    void splitDocuments_nonScannedPdf_mergesShortSections() {
        List<Document> docs = List.of(
                new Document("짧은 섹션 A", Map.of(MetaKey.SOURCE_TYPE, "file")),
                new Document("짧은 섹션 B", Map.of(MetaKey.SOURCE_TYPE, "file")));

        List<Document> result = splitter.splitDocuments(docs, "report.pdf", 2000, 200, 100);

        assertThat(result).hasSize(1); // merged into one — proves the section-merge branch was taken
    }

    @Test
    @DisplayName("splitDocuments — 스캔 pdf(source_type=ocr)는 섹션 병합 없이 슬라이딩 윈도우만 적용된다 (기존 동작 유지)")
    void splitDocuments_scannedPdf_neverMergesStaysOnSlidingWindow() {
        List<Document> docs = List.of(
                new Document("짧은 섹션 A", Map.of(MetaKey.SOURCE_TYPE, "ocr")),
                new Document("짧은 섹션 B", Map.of(MetaKey.SOURCE_TYPE, "ocr")));

        List<Document> result = splitter.splitDocuments(docs, "scanned.pdf", 2000, 200, 100);

        assertThat(result).hasSize(2); // never merged — scanned PDF stays on the legacy sliding-window path
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
    @DisplayName("enforceMaxChars — 장식 줄로 raw 길이가 상한을 넘어도 정규화 길이가 상한 이하면 분할하지 않는다(§10.1-보완)")
    void enforceMaxChars_measuresNormalizedLengthForOverflowGate() {
        String text = "실제 내용입니다\n" + "=".repeat(80); // raw ~89자, 정규화 시 장식줄 제거되어 ~8자

        List<Document> result = splitter.enforceMaxChars(List.of(new Document(text)), 50, "x.md");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo(text);
    }

    @Test
    @DisplayName("mergeShortSections — 장식 줄로 raw 길이가 부풀려져도 정규화 길이 기준으로 병합 여부를 판단한다(§10.1-보완)")
    void mergeShortSections_measuresNormalizedLengthForMergeDecision() {
        String base = "짧은 내용\n" + "-".repeat(80); // raw 86자(threshold75=75 초과) → 정규화 시 5자(threshold40=40 미만)
        List<Document> docs = List.of(new Document(base), new Document("다음 섹션"));

        List<Document> result = splitter.mergeShortSections(docs, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).contains("짧은 내용", "다음 섹션");
    }

    @Test
    @DisplayName("slidingWindow — 코드블록이 청크 경계에서 잘리면 이어지는 조각마다 여는 펜스(언어 태그)가 재삽입되고, " +
            "블록이 그 조각 안에서 끝나지 않으면 닫는 펜스도 덧붙는다")
    void slidingWindow_codeBlockSplitAcrossPieces_reopensFenceOnContinuations() {
        // 10자/줄로 정렬: fence("```python\n")=10자, 데이터 100줄×10자=1000자, 닫는 펜스("```\n")=4자 → 총 1014자.
        // chunkSize=300, overlap=30 → 경계가 항상 줄 경계와 맞아떨어지도록 설계된 값.
        String fenceOpen = "```python\n";
        String dataLine = "a".repeat(9) + "\n";
        String fenceClose = "```\n";
        String code = fenceOpen + dataLine.repeat(100) + fenceClose;
        Document doc = new Document(code);

        List<Document> pieces = splitter.slidingWindow(doc, 300, 30, 50);

        assertThat(pieces).hasSize(4);

        // 첫 조각: 실제 여는 펜스를 그대로 포함 (재삽입 아님) — 이중 펜스 없음
        String first = pieces.get(0).getText();
        assertThat(first).startsWith("```python");
        assertThat(countOccurrences(first, "```")).isEqualTo(1);

        // 중간 조각들: 이어지는 조각이므로 여는 펜스 재삽입 + 그 조각 안에서 블록이 안 끝나므로 닫는 펜스도 추가
        for (int i = 1; i <= 2; i++) {
            String piece = pieces.get(i).getText();
            assertThat(piece).as("piece " + i).startsWith("```python");
            assertThat(piece).as("piece " + i).endsWith("```");
            assertThat(countOccurrences(piece, "```")).as("piece " + i).isEqualTo(2);
        }

        // 마지막 조각: 여는 펜스는 재삽입되지만, 원본의 실제 닫는 펜스를 이미 포함하므로 중복 추가되지 않음
        String last = pieces.get(3).getText();
        assertThat(last).startsWith("```python");
        assertThat(last).endsWith("```");
        assertThat(countOccurrences(last, "```")).isEqualTo(2);
    }

    @Test
    @DisplayName("slidingWindow — 표가 청크 경계에서 잘리면 이어지는 조각마다 헤더+구분행이 재삽입된다")
    void slidingWindow_tableSplitAcrossPieces_reinjectsHeaderOnContinuations() {
        // 6자/줄로 정렬: 헤더("|HHH|\n")=6자, 구분("|---|\n")=6자, 데이터 100행×6자=600자 → 총 612자.
        String header = "|HHH|\n";
        String separator = "|---|\n";
        String dataRow = "|xxx|\n";
        String table = header + separator + dataRow.repeat(100);
        Document doc = new Document(table);

        List<Document> pieces = splitter.slidingWindow(doc, 200, 12, 50);

        assertThat(pieces).hasSizeGreaterThanOrEqualTo(3);

        String first = pieces.get(0).getText();
        assertThat(first).startsWith("|HHH|").contains("|---|");
        // 첫 조각은 이미 헤더가 원본 그대로 있으므로 중복 삽입되지 않는다
        assertThat(countOccurrences(first, "|HHH|")).isEqualTo(1);

        for (int i = 1; i < pieces.size(); i++) {
            String piece = pieces.get(i).getText();
            assertThat(piece).as("piece " + i).startsWith("|HHH|");
            assertThat(piece).as("piece " + i).contains("|---|");
        }
    }

    @Test
    @DisplayName("reopenTruncatedBlock — 코드/표 어느 쪽도 아니면 청크를 그대로 반환")
    void reopenTruncatedBlock_plainText_returnsUnchanged() {
        String text = "그냥 본문입니다.\n계속됩니다.";
        String chunk = text.substring(5);

        assertThat(splitter.reopenTruncatedBlock(text, 5, text.length(), chunk)).isEqualTo(chunk);
    }

    @Test
    @DisplayName("reopenCodeFence — 원본의 여는 펜스 줄(언어 태그 포함)을 그대로 재사용한다")
    void reopenCodeFence_reusesOriginalOpeningFenceLine() {
        String full = "```java\ncode line 1\ncode line 2\n```\n";
        ChunkSplitter.Range range = new ChunkSplitter.Range(0, full.length());

        String result = splitter.reopenCodeFence(full, range, 20, "code line 2");

        assertThat(result).isEqualTo("```java\ncode line 2\n```");
    }

    @Test
    @DisplayName("reinjectTableHeader — 헤더 다음 줄이 표 형식이 아니면 헤더행만 재삽입한다")
    void reinjectTableHeader_secondLineNotTableLike_prependsHeaderOnly() {
        String full = "|H|\n지나가는 본문\n|2|\n"; // 헤더 바로 다음 줄이 표가 아님(파이프 미포함)
        ChunkSplitter.Range range = new ChunkSplitter.Range(0, full.length());

        String result = splitter.reinjectTableHeader(full, range, "|2|");

        assertThat(result).isEqualTo("|H|\n|2|");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
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
