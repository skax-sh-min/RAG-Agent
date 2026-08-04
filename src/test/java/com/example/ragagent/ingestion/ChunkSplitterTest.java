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
                List.of("A".repeat(220), "B".repeat(90), "C".repeat(210)), 200, 0);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).contains("A".repeat(220)).contains("B".repeat(90));
        assertThat(out.get(1)).contains("C".repeat(210));
    }

    @Test
    @DisplayName("시작부 작은 청크(< overlap)는 다음 청크 앞에 병합된다")
    void mergeTinyChunks_mergesLeadingTinyChunkIntoNext() {
        List<String> out = splitter.mergeTinyChunks(
                List.of("X".repeat(80), "Y".repeat(230), "Z".repeat(220)), 200, 0);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).startsWith("X".repeat(80));
        assertThat(out.get(0)).contains("Y".repeat(230));
        assertThat(out.get(1)).contains("Z".repeat(220));
    }

    @Test
    @DisplayName("청크 길이가 overlap과 같으면 병합하지 않는다")
    void mergeTinyChunks_doesNotMergeWhenLengthEqualsOverlap() {
        List<String> out = splitter.mergeTinyChunks(
                List.of("A".repeat(220), "B".repeat(200), "C".repeat(220)), 200, 0);

        assertThat(out).hasSize(3);
        assertThat(out.get(1)).isEqualTo("B".repeat(200));
    }

    @Test
    @DisplayName("overlap=0이면 우연히 일치하는 접미/접두사를 중복으로 오인해 지우지 않는다")
    void mergeTinyChunks_atZeroOverlap_neverDropsCoincidentallyMatchingText() {
        // 반복적인 내용(같은 형식의 표 행·목록 등)에서는 앞 조각의 끝과 뒤 조각의 시작이 길게
        // 일치할 수 있다. overlap=0이면 조각들은 애초에 겹치지 않으므로 이 일치는 우연이며,
        // 지우면 본문이 사라진다(예전에는 min-chunk-size만큼 스캔해 실제로 지웠다).
        String repeated = "| 항목 | 값 |\n".repeat(12);          // 앞 조각
        String tinyTail = "| 항목 | 값 |\n| 마지막 | 값 |";      // min 미만이라 앞으로 병합됨

        List<String> out = splitter.mergeTinyChunks(List.of(repeated, tinyTail), 200, 0);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("| 마지막 | 값 |");
        // 두 입력의 문자 수가 (구분자 제외하고) 모두 보존된다.
        assertThat(out.get(0).replace("\n", "").length())
                .isEqualTo(repeated.replace("\n", "").length() + tinyTail.replace("\n", "").length());
    }

    @Test
    @DisplayName("slidingWindow — overlap=0에서 반복 텍스트가 재병합돼도 내용이 유실되지 않는다")
    void slidingWindow_atZeroOverlap_preservesRepetitiveTextThroughTinyChunkMerge() {
        // 조각이 minChunkSize보다 작아 mergeTinyChunks 경로를 반드시 타도록 chunkSize == minChunkSize.
        String body = "## 반복 표\n\n" + ("내".repeat(79) + "\n").repeat(20);
        Document doc = new Document(body, new java.util.HashMap<>(Map.of(MetaKey.CHAPTER_NO, "0")));

        List<Document> pieces = splitter.slidingWindow(doc, 500, 0, 500);

        int totalChars = pieces.stream().mapToInt(d -> d.getText().replace("\n", "").length()).sum();
        assertThat(totalChars).isGreaterThanOrEqualTo(body.replace("\n", "").length());
    }

    @Test
    @DisplayName("작은 청크를 병합할 때 overlap 구간이 중복되지 않는다")
    void mergeTinyChunks_deduplicatesOverlapText() {
        String overlap = "__OVERLAP__";
        String first = "A".repeat(220) + overlap;
        String tiny = overlap + "B".repeat(60);
        List<String> out = splitter.mergeTinyChunks(List.of(first, tiny), 200, overlap.length());

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

    // ── mergeIdenticalHeadingSlides — 동일 ##+### 헤딩 연속 슬라이드 병합 ───────────────────

    @Test
    @DisplayName("mergeIdenticalHeadingSlides — ##+### 헤딩이 완전히 같은 연속 슬라이드는 하나로 합쳐지고, 두 번째 슬라이드의 헤딩은 제거되되 페이지 마커는 남는다")
    void mergeIdenticalHeadingSlides_mergesMatchingSlides_dedupesHeadingKeepsPageMarker() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n\n### 소챕터\n\n본문1", Map.of(MetaKey.PAGE_OR_SLIDE, 1)),
                new Document("## 큰챕터\n\n### 소챕터\n\n본문2", Map.of(MetaKey.PAGE_OR_SLIDE, 2)));

        List<Document> result = splitter.mergeIdenticalHeadingSlides(docs, 2000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText())
                .isEqualTo("## 큰챕터\n\n### 소챕터\n\n본문1\n\n[페이지: 2]\n\n본문2");
    }

    @Test
    @DisplayName("mergeIdenticalHeadingSlides — 합친 크기가 chunkSize를 넘으면 병합하지 않는다")
    void mergeIdenticalHeadingSlides_notMergedWhenCombinedExceedsChunkSize() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n\n### 소챕터\n\n" + "가".repeat(600), Map.of(MetaKey.PAGE_OR_SLIDE, 1)),
                new Document("## 큰챕터\n\n### 소챕터\n\n" + "나".repeat(600), Map.of(MetaKey.PAGE_OR_SLIDE, 2)));

        List<Document> result = splitter.mergeIdenticalHeadingSlides(docs, 1000);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("mergeIdenticalHeadingSlides — 헤딩이 조금이라도 다르면 병합하지 않는다")
    void mergeIdenticalHeadingSlides_notMergedWhenHeadingsDiffer() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n\n### 소챕터A\n\n본문1", Map.of(MetaKey.PAGE_OR_SLIDE, 1)),
                new Document("## 큰챕터\n\n### 소챕터B\n\n본문2", Map.of(MetaKey.PAGE_OR_SLIDE, 2)));

        List<Document> result = splitter.mergeIdenticalHeadingSlides(docs, 2000);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("mergeIdenticalHeadingSlides — 한쪽에라도 ##/### 중 하나가 없으면 병합하지 않는다")
    void mergeIdenticalHeadingSlides_notMergedWhenHeadingLevelMissing() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n\n본문1", Map.of(MetaKey.PAGE_OR_SLIDE, 1)), // ### 없음
                new Document("## 큰챕터\n\n### 소챕터\n\n본문2", Map.of(MetaKey.PAGE_OR_SLIDE, 2)));

        List<Document> result = splitter.mergeIdenticalHeadingSlides(docs, 2000);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("mergeIdenticalHeadingSlides — 3개 이상 연속으로 헤딩이 같아도 기본값 2개까지만 합쳐진다")
    void mergeIdenticalHeadingSlides_cappedAtTwoSlidesEvenWhenThirdAlsoMatches() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n\n### 소챕터\n\n본문1", Map.of(MetaKey.PAGE_OR_SLIDE, 1)),
                new Document("## 큰챕터\n\n### 소챕터\n\n본문2", Map.of(MetaKey.PAGE_OR_SLIDE, 2)),
                new Document("## 큰챕터\n\n### 소챕터\n\n본문3", Map.of(MetaKey.PAGE_OR_SLIDE, 3)));

        List<Document> result = splitter.mergeIdenticalHeadingSlides(docs, 2000);

        assertThat(result).hasSize(2); // 1+2만 합쳐지고, 3은 그 자체로 새 그룹(다음이 없어 단독)
        assertThat(result.get(0).getText()).isEqualTo(
                "## 큰챕터\n\n### 소챕터\n\n본문1\n\n[페이지: 2]\n\n본문2");
        assertThat(result.get(1).getText()).isEqualTo("## 큰챕터\n\n### 소챕터\n\n본문3");
    }

    @Test
    @DisplayName("mergeIdenticalHeadingSlides — 4개 연속으로 헤딩이 같으면 2개씩 두 그룹으로 나뉜다")
    void mergeIdenticalHeadingSlides_fourMatchingSlidesFormTwoIndependentPairs() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n\n### 소챕터\n\n본문1", Map.of(MetaKey.PAGE_OR_SLIDE, 1)),
                new Document("## 큰챕터\n\n### 소챕터\n\n본문2", Map.of(MetaKey.PAGE_OR_SLIDE, 2)),
                new Document("## 큰챕터\n\n### 소챕터\n\n본문3", Map.of(MetaKey.PAGE_OR_SLIDE, 3)),
                new Document("## 큰챕터\n\n### 소챕터\n\n본문4", Map.of(MetaKey.PAGE_OR_SLIDE, 4)));

        List<Document> result = splitter.mergeIdenticalHeadingSlides(docs, 2000);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo(
                "## 큰챕터\n\n### 소챕터\n\n본문1\n\n[페이지: 2]\n\n본문2");
        assertThat(result.get(1).getText()).isEqualTo(
                "## 큰챕터\n\n### 소챕터\n\n본문3\n\n[페이지: 4]\n\n본문4");
    }

    @Test
    @DisplayName("mergeIdenticalHeadingSlides — 세 번째 슬라이드부터 헤딩이 다르면 앞의 두 개만 합쳐진다")
    void mergeIdenticalHeadingSlides_stopsChainWhenLaterSlideDiffers() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n\n### 소챕터\n\n본문1", Map.of(MetaKey.PAGE_OR_SLIDE, 1)),
                new Document("## 큰챕터\n\n### 소챕터\n\n본문2", Map.of(MetaKey.PAGE_OR_SLIDE, 2)),
                new Document("## 다른챕터\n\n### 다른소챕터\n\n본문3", Map.of(MetaKey.PAGE_OR_SLIDE, 3)));

        List<Document> result = splitter.mergeIdenticalHeadingSlides(docs, 2000);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).contains("본문1").contains("[페이지: 2]").contains("본문2");
        assertThat(result.get(1).getText()).contains("다른챕터").contains("본문3");
    }

    @Test
    @DisplayName("mergeIdenticalHeadingSlides — 병합된 청크의 메타데이터는 첫 슬라이드 것을 유지한다")
    void mergeIdenticalHeadingSlides_keepsFirstSlideMetadata() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n\n### 소챕터\n\n본문1", Map.of(MetaKey.PAGE_OR_SLIDE, 1)),
                new Document("## 큰챕터\n\n### 소챕터\n\n본문2", Map.of(MetaKey.PAGE_OR_SLIDE, 2)));

        List<Document> result = splitter.mergeIdenticalHeadingSlides(docs, 2000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(1);
    }

    @Test
    @DisplayName("splitDocuments — pptx에서 ##+### 헤딩이 완전히 같은 연속 슬라이드는 mergeShortSections 이전에 하나로 합쳐진다")
    void splitDocuments_pptx_mergesIdenticalDualHeadingSlidesBeforeSectionMerge() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n\n### 소챕터\n\n" + "본문 ".repeat(80), Map.of(MetaKey.PAGE_OR_SLIDE, 1)),
                new Document("## 큰챕터\n\n### 소챕터\n\n" + "본문 ".repeat(80), Map.of(MetaKey.PAGE_OR_SLIDE, 2)),
                new Document("## 다른 슬라이드\n\n짧음", Map.of(MetaKey.PAGE_OR_SLIDE, 3)));

        List<Document> result = splitter.splitDocuments(docs, "deck.pptx", 2000, 200, 100);

        // 1·2번 슬라이드는 하나로 합쳐지고, 서로 다른 3번 슬라이드는 병합되지 않고 남는다
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).contains("[페이지: 2]");
        assertThat(countOccurrences(result.get(0).getText(), "## 큰챕터")).isEqualTo(1);
        assertThat(result.get(1).getText()).contains("다른 슬라이드");
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
    @DisplayName("slidingWindow — 코드블록이 청크 경계에서 잘리면 이어지는 조각마다 여는 펜스(언어 태그)와 " +
            "'이전 청크에서 계속' 주석이 재삽입되고, 블록이 그 조각 안에서 끝나지 않으면 닫는 펜스와 " +
            "'다음 청크로 계속' 주석도 덧붙는다")
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

        // 첫 조각: 실제 여는 펜스를 그대로 포함(재삽입도 BEFORE 주석도 없음 — 앞에 이어지는 내용이 없음),
        // 다만 이 조각 안에서 블록이 끝나지 않으므로 닫는 펜스 + AFTER 주석이 덧붙는다.
        String first = pieces.get(0).getText();
        assertThat(first).startsWith("```python");
        assertThat(first).doesNotContain(ChunkSplitter.CODE_CONTINUATION_BEFORE);
        assertThat(first).endsWith(ChunkSplitter.CODE_CONTINUATION_AFTER);
        assertThat(countOccurrences(first, "```")).isEqualTo(2);

        // 중간 조각들: 앞뒤 모두 이어지므로 BEFORE 주석+재삽입 펜스로 시작해 AFTER 주석으로 끝난다.
        for (int i = 1; i <= 2; i++) {
            String piece = pieces.get(i).getText();
            assertThat(piece).as("piece " + i).startsWith(ChunkSplitter.CODE_CONTINUATION_BEFORE);
            assertThat(piece).as("piece " + i).contains("```python");
            assertThat(piece).as("piece " + i).endsWith(ChunkSplitter.CODE_CONTINUATION_AFTER);
            assertThat(countOccurrences(piece, "```")).as("piece " + i).isEqualTo(2);
        }

        // 마지막 조각: 앞에서 이어지므로 BEFORE 주석+재삽입 펜스로 시작하지만, 원본의 실제 닫는 펜스를
        // 이미 포함하므로 AFTER 주석이나 중복 닫는 펜스는 추가되지 않는다.
        String last = pieces.get(3).getText();
        assertThat(last).startsWith(ChunkSplitter.CODE_CONTINUATION_BEFORE);
        assertThat(last).contains("```python");
        assertThat(last).endsWith("```");
        assertThat(last).doesNotContain(ChunkSplitter.CODE_CONTINUATION_AFTER);
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
    @DisplayName("reopenTruncatedBlock — 코드블록의 실제 시작을 포함해도(from-before 아님) 그 조각 안에서 블록이 " +
            "끝나지 않으면 닫는 펜스 + AFTER 주석을 덧붙인다")
    void reopenTruncatedBlock_containsRealStartButBlockContinues_appendsAfterMarker() {
        String full = "```java\ncode line 1\ncode line 2\n```\n";
        String chunk = "```java\ncode line 1"; // start=0은 블록의 실제 시작, end=20은 블록이 끝나기 전

        String result = splitter.reopenTruncatedBlock(full, 0, 20, chunk);

        assertThat(result).isEqualTo(chunk + "\n```\n" + ChunkSplitter.CODE_CONTINUATION_AFTER);
        assertThat(result).doesNotContain(ChunkSplitter.CODE_CONTINUATION_BEFORE);
    }

    @Test
    @DisplayName("reopenCodeFence — 앞뒤 모두 이어지면 원본의 여는 펜스 줄(언어 태그 포함)을 재사용하고 " +
            "BEFORE/AFTER 주석을 펜스 바깥에 덧붙인다")
    void reopenCodeFence_reusesOriginalOpeningFenceLine() {
        String full = "```java\ncode line 1\ncode line 2\n```\n";
        ChunkSplitter.Range range = new ChunkSplitter.Range(0, full.length());

        // chunkStart(10) > range.start()(0) → continuesFromBefore; chunkEnd(20) < range.end()(36) → continuesAfter
        String result = splitter.reopenCodeFence(full, range, 10, 20, "code line 2");

        String expected = ChunkSplitter.CODE_CONTINUATION_BEFORE + "\n```java\ncode line 2\n```\n"
                + ChunkSplitter.CODE_CONTINUATION_AFTER;
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("reopenCodeFence — 이 조각이 블록의 실제 시작을 포함하면 여는 펜스를 재삽입/BEFORE 주석 없이 그대로 두고, " +
            "블록이 이 조각 안에서 끝나지 않을 때만 닫는 펜스 + AFTER 주석을 덧붙인다")
    void reopenCodeFence_containsRealStart_onlyAppendsAfterMarkerWhenBlockContinues() {
        String full = "```java\ncode line 1\ncode line 2\n```\n";
        ChunkSplitter.Range range = new ChunkSplitter.Range(0, full.length());

        // chunkStart(0) == range.start() → continuesFromBefore=false; chunkEnd(20) < range.end()(36) → continuesAfter=true
        String result = splitter.reopenCodeFence(full, range, 0, 20, "```java\ncode line 1");

        assertThat(result).isEqualTo("```java\ncode line 1\n```\n" + ChunkSplitter.CODE_CONTINUATION_AFTER);
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

    // ── 챕터 기반 병합(md/docx/txt) ────────────────────────────────────────────

    @Test
    @DisplayName("mergeSectionsByChapter — 규칙1: 작은 현재 + 하위 레벨 다음의 합이 chunkSize 이내면 한 청크로 병합")
    void chapterMerge_rule1_mergesWhenCombinedFits() {
        List<Document> docs = List.of(
                new Document("## 챕터A\n짧은 본문"),
                new Document("### 챕터A-1\n짧은 하위 본문"));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 1000, 100, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).contains("## 챕터A").contains("### 챕터A-1");
    }

    @Test
    @DisplayName("mergeSectionsByChapter — 다음이 더 상위(##) 챕터면 병합 금지, 작은 ###는 이전 청크로 뒤로 병합")
    void chapterMerge_parentForbidden_tinyChildFoldsBackward() {
        List<Document> docs = List.of(
                new Document("## 챕터A\n" + "가".repeat(200)),
                new Document("### 챕터A-1\n짧은 내용"),
                new Document("## 챕터B\n" + "나".repeat(200)));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 1000, 100, 100);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).contains("챕터A-1"); // 상위 ##로 못 가고 이전(A)으로 뒤로 병합
        assertThat(result.get(1).getText()).contains("챕터B");
    }

    @Test
    @DisplayName("mergeSectionsByChapter — 규칙2: 합은 chunkSize 초과지만 다음 단독은 이내면 전방 분리, 작은 현재는 뒤로 병합")
    void chapterMerge_rule2_separatesThenFoldsBackward() {
        List<Document> docs = List.of(
                new Document("## A\n" + "가".repeat(250)),
                new Document("## B\n" + "나".repeat(50)),
                new Document("## C\n" + "다".repeat(250)));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 300, 30, 100);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).contains("B"); // B는 C로 전방 병합되지 않고 A로 뒤로 병합
        assertThat(result.get(1).getText()).contains("C");
    }

    @Test
    @DisplayName("mergeSectionsByChapter — 규칙3: 큰 다음에 prepend 후 마지막 조각이 min*1.5 이상이면 병합(1그룹)")
    void chapterMerge_rule3_mergesWhenLastPieceLargeEnough() {
        List<Document> docs = List.of(
                new Document("가".repeat(20)),
                new Document("나".repeat(800)));

        List<ChunkSplitter.SectionGroup> groups = splitter.mergeSectionsByChapter(docs, 300, 30, 100);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).doc().getText()).contains("가").contains("나");
    }

    @Test
    @DisplayName("mergeSectionsByChapter — 규칙3: prepend 후 마지막 조각이 min*1.5 미만이면 병합 안 함(2그룹)")
    void chapterMerge_rule3_splitsWhenLastPieceTooSmall() {
        List<Document> docs = List.of(
                new Document("가".repeat(20)),
                new Document("나".repeat(610)));

        List<ChunkSplitter.SectionGroup> groups = splitter.mergeSectionsByChapter(docs, 300, 30, 100);

        assertThat(groups).hasSize(2);
    }

    @Test
    @DisplayName("mergeSectionsByChapter — 헤딩 없는(level 0) 다음 섹션은 상위 챕터가 아니므로 병합 허용")
    void chapterMerge_headinglessNextIsNotParent_allowsMerge() {
        List<Document> docs = List.of(
                new Document("### 하위\n짧음"),
                new Document("본문만 있고 헤딩 없음"));

        List<ChunkSplitter.SectionGroup> groups = splitter.mergeSectionsByChapter(docs, 1000, 100, 100);

        assertThat(groups).hasSize(1);
    }

    @Test
    @DisplayName("mergeSectionsByChapter — 첫 섹션이 H1(챕터번호 '0')이라 실제 '## 1장'과 병합되면 " +
            "그 실제 챕터번호를 그룹 메타데이터로 사용한다")
    void chapterMerge_prologueChapterNoIsReplacedByFirstRealChapterFound() {
        List<Document> docs = List.of(
                new Document("# 문서 제목\n짧은 인트로",
                        Map.of(MetaKey.CHAPTER_NO, "0")),
                new Document("## 1장 개요\n짧은 본문",
                        Map.of(MetaKey.CHAPTER_NO, "1")));

        List<ChunkSplitter.SectionGroup> groups = splitter.mergeSectionsByChapter(docs, 1000, 100, 100);

        assertThat(groups).hasSize(1); // 프롤로그가 작아서 전방 병합됨
        assertThat(groups.get(0).doc().getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("1");
    }

    @Test
    @DisplayName("mergeSectionsByChapter — 병합된 섹션 전부 챕터번호가 '0'이면(진짜 프롤로그) '0'을 유지한다")
    void chapterMerge_allZeroChapterNo_staysZero() {
        List<Document> docs = List.of(
                new Document("# 문서 제목\n짧은 인트로",
                        Map.of(MetaKey.CHAPTER_NO, "0")),
                new Document("헤딩 없는 본문",
                        Map.of(MetaKey.CHAPTER_NO, "0")));

        List<ChunkSplitter.SectionGroup> groups = splitter.mergeSectionsByChapter(docs, 1000, 100, 100);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).doc().getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("0");
    }

    @Test
    @DisplayName("mergeSectionsByChapter — 시작 섹션이 이미 실제 챕터번호면 병합 뒤에도 그대로 유지된다(첫 섹션 메타데이터 우선 관례)")
    void chapterMerge_startSectionAlreadyRealChapterNo_isPreserved() {
        List<Document> docs = List.of(
                new Document("### 하위\n짧음", Map.of(MetaKey.CHAPTER_NO, "1.1")),
                new Document("본문만 있고 헤딩 없음", Map.of(MetaKey.CHAPTER_NO, "1.1")));

        List<ChunkSplitter.SectionGroup> groups = splitter.mergeSectionsByChapter(docs, 1000, 100, 100);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).doc().getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("1.1");
    }

    // ── 부모 챕터 브레드크럼 ──────────────────────────────────────────────────

    @Test
    @DisplayName("부모 브레드크럼 — ### 하위 챕터 청크 맨 앞에 바로 위 부모 ## 헤딩 1줄이 붙는다")
    void breadcrumb_childChapterGetsParentHeadingPrepended() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n" + "가".repeat(200)),
                new Document("### 소챕터\n" + "나".repeat(200)));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 1000, 100, 100);

        assertThat(result).hasSize(2);
        assertThat(result.get(1).getText()).startsWith("## 큰챕터\n### 소챕터");
    }

    @Test
    @DisplayName("부모 브레드크럼 — ## 최상위 챕터 청크에는 아무 헤딩도 붙지 않는다")
    void breadcrumb_topLevelChapterGetsNothing() {
        List<Document> docs = List.of(
                new Document("# 문서\n" + "머".repeat(200)),
                new Document("## 챕터\n" + "가".repeat(200)));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 1000, 100, 100);

        assertThat(result).hasSize(2);
        assertThat(result.get(1).getText()).startsWith("## 챕터");
        assertThat(result.get(1).getText()).doesNotContain("문서");
    }

    @Test
    @DisplayName("부모 브레드크럼 — #### 청크에는 최상위 ##가 아니라 바로 위 ### 부모가 붙는다")
    void breadcrumb_usesImmediateParentNotTopLevel() {
        List<Document> docs = List.of(
                new Document("## 대\n" + "가".repeat(200)),
                new Document("### 중\n" + "나".repeat(200)),
                new Document("#### 소\n" + "다".repeat(200)));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 1000, 100, 100);

        assertThat(result).hasSize(3);
        assertThat(result.get(2).getText()).startsWith("### 중\n#### 소");
        assertThat(result.get(2).getText()).doesNotContain("대"); // 최상위 ## 대는 붙지 않음
    }

    @Test
    @DisplayName("부모 브레드크럼 — 긴 하위 섹션이 여러 조각으로 나뉘면 첫 조각에만 부모가 붙고 꼬리 조각엔 자기 헤딩(N)만")
    void breadcrumb_onlyFirstPieceOfSplitChildGetsParent() {
        List<Document> docs = List.of(
                new Document("## 큰챕터\n" + "가".repeat(200)),
                new Document("### 소챕터\n" + "나".repeat(3000)));

        List<Document> result = splitter.splitDocuments(docs, "doc.md", 2000, 200, 100);

        // 첫 조각(부모 프리펜드) 이후 소챕터가 슬라이딩 분할되어 꼬리 조각이 하나 이상 존재
        Document firstChildPiece = result.stream()
                .filter(d -> d.getText().contains("### 소챕터"))
                .findFirst().orElseThrow();
        assertThat(firstChildPiece.getText()).startsWith("## 큰챕터\n### 소챕터");

        // 꼬리 조각은 자기 헤딩 "### 소챕터 (N)"만 갖고 부모(## 큰챕터)는 없음
        List<Document> tailPieces = result.stream()
                .filter(d -> d.getText().startsWith("### 소챕터 ("))
                .toList();
        assertThat(tailPieces).isNotEmpty();
        assertThat(tailPieces).allSatisfy(d -> assertThat(d.getText()).doesNotContain("큰챕터"));
    }
}
