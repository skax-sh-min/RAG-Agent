package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최대 분할 전략 (`app.chunk-split-granular=true`) — the alternative to the default size-driven
 * chapter merge.
 *
 * <p>Covers: min-chunk-size is ignored (short subsections survive as their own chunks), the single
 * lead-in exception (heading + ≤2 sentences folds into the child chapter below it) and its limits,
 * block-aware boundaries keeping tables/code fences whole even at {@code chunk-overlap=0}, PPTX
 * slides no longer merging, and byte-for-byte equality with the old behavior when the flag is off.
 */
class ChunkSplitterGranularTest {

    private ChunkSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new ChunkSplitter();
    }

    private static Document section(String text) {
        return new Document(text, new HashMap<>(Map.of(MetaKey.CHAPTER_NO, "0")));
    }

    private static Document slide(String text, int page) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.PAGE_OR_SLIDE, page);
        meta.put(MetaKey.CHAPTER_NO, "0");
        return new Document(text, meta);
    }

    private List<Document> split(List<Document> docs, String filename, int chunkSize,
                                 int overlap, int minChunkSize, boolean granular) {
        return splitter.splitDocuments(docs, filename, chunkSize, overlap, minChunkSize, 0, granular);
    }

    private static List<String> texts(List<Document> docs) {
        List<String> out = new ArrayList<>();
        for (Document d : docs) out.add(d.getText());
        return out;
    }

    // ── min-chunk-size 무시 ──────────────────────────────────────────────────

    @Test
    @DisplayName("최대 분할 — minChunkSize보다 짧은 소제목들도 각각 독립 청크로 남는다")
    void granular_keepsShortSectionsSeparate() {
        List<Document> docs = List.of(
                section("## 설치\n\n설치 방법은 이렇습니다. 준비물을 챙기세요. 그리고 실행합니다."),
                section("## 설정\n\n설정 값을 넣습니다. 저장하면 반영됩니다. 재시작은 불필요합니다."),
                section("## 삭제\n\n삭제는 이렇게 합니다. 백업을 먼저 하세요. 복구는 불가합니다."));

        // 기본 전략: minChunkSize(500)에 한참 못 미치므로 전부 한 청크로 묶인다.
        List<Document> merged = split(docs, "guide.md", 1500, 0, 500, false);
        assertThat(merged).hasSize(1);

        // 최대 분할: minChunkSize를 보지 않으므로 3개 그대로 유지.
        List<Document> granular = split(docs, "guide.md", 1500, 0, 500, true);
        assertThat(granular).hasSize(3);
        assertThat(texts(granular)).allSatisfy(t -> assertThat(t).startsWith("## "));
    }

    @Test
    @DisplayName("최대 분할 — 형제 챕터끼리는 아무리 짧아도 병합되지 않는다")
    void granular_neverMergesSiblings() {
        List<Document> docs = List.of(
                section("### 가\n\n한 줄."),
                section("### 나\n\n한 줄."),
                section("### 다\n\n한 줄."));

        List<Document> out = split(docs, "guide.md", 1500, 0, 500, true);
        assertThat(out).hasSize(3);
    }

    // ── 도입부(제목+2문장 이내) 예외 ──────────────────────────────────────────

    @Test
    @DisplayName("도입부 — 제목만 있는 챕터는 바로 아래 하위 챕터와 통합된다")
    void leadIn_headingOnlyMergesIntoChild() {
        List<Document> docs = List.of(
                section("## 3장 배포"),
                section("### 3.1 준비\n\n준비 단계입니다. 도구를 설치하세요. 계정을 만드세요."),
                section("### 3.2 실행\n\n실행 단계입니다. 명령을 입력하세요. 로그를 확인하세요."));

        List<Document> out = split(docs, "guide.md", 1500, 0, 500, true);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).satisfies(d -> {
            assertThat(d.getText()).contains("## 3장 배포").contains("### 3.1 준비");
            assertThat(d.getText()).doesNotContain("3.2 실행");
        });
        assertThat(out.get(1).getText()).contains("### 3.2 실행");
    }

    @Test
    @DisplayName("도입부 — 2문장 이내면 통합, 3문장이면 독립 청크로 남는다")
    void leadIn_boundaryIsTwoSentences() {
        List<Document> twoSentences = List.of(
                section("## 개요\n\n이 장을 소개합니다. 아래에서 다룹니다."),
                section("### 세부\n\n세부 내용입니다. 자세히 봅니다. 끝입니다."));
        assertThat(split(twoSentences, "g.md", 1500, 0, 500, true)).hasSize(1);

        List<Document> threeSentences = List.of(
                section("## 개요\n\n이 장을 소개합니다. 아래에서 다룹니다. 한 문장 더 있습니다."),
                section("### 세부\n\n세부 내용입니다. 자세히 봅니다. 끝입니다."));
        assertThat(split(threeSentences, "g.md", 1500, 0, 500, true)).hasSize(2);
    }

    @Test
    @DisplayName("도입부 — 종결부호 없는 짧은 목록 3줄은 '2문장 이내'로 보지 않는다")
    void leadIn_bulletListIsNotALeadIn() {
        List<Document> docs = List.of(
                section("## 준비물\n\n- 노트북\n- 케이블\n- 어댑터"),
                section("### 연결\n\n연결 방법입니다. 순서대로 하세요. 완료됩니다."));

        assertThat(split(docs, "g.md", 1500, 0, 500, true)).hasSize(2);
    }

    @Test
    @DisplayName("도입부 — 표나 코드 블록이 있으면 길이와 무관하게 통합 대상이 아니다")
    void leadIn_tableOrCodeIsContent() {
        List<Document> withTable = List.of(
                section("## 스펙\n\n| 항목 | 값 |\n|---|---|\n| A | 1 |"),
                section("### 상세\n\n상세 설명입니다. 더 봅니다. 끝."));
        assertThat(split(withTable, "g.md", 1500, 0, 500, true)).hasSize(2);

        List<Document> withCode = List.of(
                section("## 예시\n\n```java\nint a = 1;\n```"),
                section("### 상세\n\n상세 설명입니다. 더 봅니다. 끝."));
        assertThat(split(withCode, "g.md", 1500, 0, 500, true)).hasSize(2);
    }

    @Test
    @DisplayName("도입부 — 다음이 형제/상위 챕터면 통합하지 않는다 (하위 챕터 전용)")
    void leadIn_onlyMergesIntoDeeperHeading() {
        List<Document> sibling = List.of(
                section("## 개요"),
                section("## 본문\n\n본문입니다. 이어집니다. 끝."));
        assertThat(split(sibling, "g.md", 1500, 0, 500, true)).hasSize(2);

        List<Document> parent = List.of(
                section("### 하위 도입"),
                section("## 상위\n\n상위 내용입니다. 이어집니다. 끝."));
        assertThat(split(parent, "g.md", 1500, 0, 500, true)).hasSize(2);
    }

    @Test
    @DisplayName("도입부 — 헤딩 사다리는 도입부가 이어지는 동안만 연쇄 통합된다")
    void leadIn_chainsDownHeadingLadderWhileStillALeadIn() {
        List<Document> docs = List.of(
                section("## A"),
                section("### A-1"),
                section("#### A-1-1\n\n실제 내용입니다. 두 번째 문장. 세 번째 문장."),
                section("#### A-1-2\n\n다른 내용입니다. 두 번째 문장. 세 번째 문장."));

        List<Document> out = split(docs, "g.md", 1500, 0, 500, true);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getText()).contains("## A").contains("### A-1").contains("#### A-1-1");
        assertThat(out.get(1).getText()).contains("#### A-1-2");
    }

    @Test
    @DisplayName("도입부 — 통합 결과가 chunkSize를 넘으면 통합하지 않는다")
    void leadIn_neverExceedsChunkSize() {
        List<Document> docs = List.of(
                section("## 개요"),
                section("### 본문\n\n" + "가".repeat(400)));

        assertThat(split(docs, "g.md", 300, 0, 100, true)).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("도입부 통합 — 프롤로그 chapter_no '0'은 흡수한 실제 챕터 번호로 교체된다")
    void leadIn_replacesPrologueChapterNo() {
        Document intro = new Document("## 도입", new HashMap<>(Map.of(MetaKey.CHAPTER_NO, "0")));
        Document child = new Document("### 실제\n\n내용입니다. 둘. 셋.",
                new HashMap<>(Map.of(MetaKey.CHAPTER_NO, "2.1")));

        List<Document> out = split(List.of(intro, child), "g.md", 1500, 0, 500, true);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("2.1");
    }

    // ── 표/코드 블록 보호 ────────────────────────────────────────────────────

    /**
     * chunkSize=500 기준으로 <b>첫 경계(≈500자)가 코드 블록 내부에 떨어지도록</b> 크기를 맞춘 픽스처:
     * 제목(12) + 앞 산문 400자 → 펜스가 ≈412자에서 시작해 ≈724자에서 끝나므로 500은 그 안쪽이다.
     * 이 조건이 아니면(블록이 첫 청크에 통째로 들어가면) 두 전략 모두 자를 일이 없어 대조가 성립하지 않는다.
     */
    private static String codeBlockStraddlingBoundary() {
        StringBuilder body = new StringBuilder("## 코드 예제\n\n");
        body.append(("설".repeat(39) + "\n").repeat(10));                  // 400자
        body.append("```java\n");
        for (int i = 0; i < 10; i++) {
            body.append("int value").append(i).append(" = ").append(i).append(";")
                .append(" ".repeat(12)).append("\n");                     // 30자 × 10
        }
        body.append("```\n");
        body.append(("뒷".repeat(39) + "\n").repeat(10));                  // 400자
        return body.toString();
    }

    @Test
    @DisplayName("블록 보호 — overlap=0이어도 경계에 걸친 코드 블록이 잘리지 않는다")
    void granular_keepsCodeFenceWhole() {
        List<Document> out = split(List.of(section(codeBlockStraddlingBoundary())),
                "g.md", 500, 0, 100, true);

        assertThat(out).hasSizeGreaterThan(1);
        // 펜스 전체가 한 청크 안에 들어 있고, 이어짐 마커(양방향)는 전혀 쓰이지 않았다.
        long whole = out.stream().filter(d -> d.getText().contains("int value0 = 0;")
                && d.getText().contains("int value9 = 9;")).count();
        assertThat(whole).isEqualTo(1);
        assertThat(texts(out)).noneMatch(t -> t.contains(ChunkSplitter.CODE_CONTINUATION_BEFORE)
                || t.contains(ChunkSplitter.CODE_CONTINUATION_AFTER));
    }

    @Test
    @DisplayName("블록 보호 — overlap=0인 기본 전략에서는 같은 코드 블록이 잘린다 (대조군)")
    void defaultStrategy_stillCutsCodeFenceAtZeroOverlap() {
        List<Document> out = split(List.of(section(codeBlockStraddlingBoundary())),
                "g.md", 500, 0, 100, false);

        // 어느 청크에도 코드 블록 전체가 들어 있지 않고, 이어받는 청크에 복구 마커가 붙는다.
        // (마커는 블록 '안에서 시작하는' 청크에만 붙으므로 잘린 앞쪽이 아니라 뒤쪽에 나타난다.)
        assertThat(texts(out)).noneMatch(t -> t.contains("int value0 = 0;") && t.contains("int value9 = 9;"));
        assertThat(texts(out)).anyMatch(t -> t.contains(ChunkSplitter.CODE_CONTINUATION_BEFORE));
    }

    @Test
    @DisplayName("블록 보호 — 경계에 걸친 표는 헤더째 다음 청크로 넘어간다")
    void granular_keepsTableWhole() {
        StringBuilder body = new StringBuilder("## 표 예제\n\n");
        body.append(("설".repeat(39) + "\n").repeat(10));                  // 400자
        body.append("| 항목 | 값 | 비고 |\n|---|---|---|\n");
        for (int i = 0; i < 10; i++) body.append("| 항목").append(i).append(" | 값 | 비고 |\n");
        body.append("\n");
        body.append(("뒷".repeat(39) + "\n").repeat(10));

        List<Document> out = split(List.of(section(body.toString())), "g.md", 500, 0, 100, true);

        long whole = out.stream().filter(d -> d.getText().contains("| 항목0 |")
                && d.getText().contains("| 항목9 |")).count();
        assertThat(whole).isEqualTo(1);
    }

    @Test
    @DisplayName("블록 인지 tolerance — granular일 때만 overlap과 분리된다")
    void blockToleranceFor_decouplesFromOverlapOnlyWhenGranular() {
        assertThat(ChunkSplitter.blockToleranceFor(false, 1500, 0)).isZero();
        assertThat(ChunkSplitter.blockToleranceFor(false, 1500, 120)).isEqualTo(120);
        assertThat(ChunkSplitter.blockToleranceFor(true, 1500, 0)).isEqualTo(750);
        // overlap이 더 크면 그쪽을 존중한다.
        assertThat(ChunkSplitter.blockToleranceFor(true, 1000, 900)).isEqualTo(900);
    }

    // ── PPTX/PDF ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("최대 분할 — PPTX는 슬라이드를 넘어 병합하지 않는다 (1슬라이드 = 1청크)")
    void granular_keepsOneChunkPerSlide() {
        List<Document> slides = List.of(
                slide("## 제목\n\n### 소제목\n\n첫 슬라이드.", 1),
                slide("## 제목\n\n### 소제목\n\n둘째 슬라이드.", 2),
                slide("## 다른 제목\n\n### 다른 소제목\n\n셋째 슬라이드.", 3));

        // 기본 전략은 짧은 슬라이드를 묶는다(동일 헤딩 병합 + 크기 기준 병합).
        assertThat(split(slides, "deck.pptx", 1500, 0, 500, false)).hasSizeLessThan(3);
        // 최대 분할은 그대로 3개.
        assertThat(split(slides, "deck.pptx", 1500, 0, 500, true)).hasSize(3);
    }

    @Test
    @DisplayName("최대 분할 — 한 슬라이드가 제목/본문 섹션으로 나뉘어 들어와도 청크는 하나로 합쳐진다")
    void granular_joinsSectionsWithinOneSlide() {
        // 로더가 실제로 내놓는 형태: 헤딩도 섹션 경계라서 제목 있는 슬라이드는 두 섹션으로 도착한다.
        List<Document> rawSections = List.of(
                slide("## 매출 현황", 1),
                slide("### 1분기\n\n1분기 매출은 100억입니다.", 1),
                slide("## 매출 현황", 2),
                slide("### 2분기\n\n2분기 매출은 120억입니다.", 2));

        List<Document> out = split(rawSections, "deck.pptx", 1500, 0, 500, true);

        // 슬라이드당 1청크 — 본문 없는 "## 매출 현황"만의 청크가 생기면 안 된다.
        assertThat(out).hasSize(2);
        assertThat(texts(out)).noneMatch(t -> t.strip().equals("## 매출 현황"));
        assertThat(out.get(0).getText()).contains("## 매출 현황").contains("1분기 매출은 100억");
        assertThat(out.get(1).getText()).contains("## 매출 현황").contains("2분기 매출은 120억");
        // 페이지 귀속(정확한 인용)은 그대로 유지된다.
        assertThat(out.get(0).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(1);
        assertThat(out.get(1).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(2);
    }

    @Test
    @DisplayName("joinSectionsWithinPage — page_or_slide가 없는 문서(md/docx/txt)에는 무영향")
    void joinSectionsWithinPage_isNoOpWithoutPageMetadata() {
        List<Document> docs = List.of(section("## 가\n\n내용."), section("## 나\n\n내용."));

        assertThat(texts(splitter.joinSectionsWithinPage(docs))).isEqualTo(texts(docs));
    }

    // ── 회귀 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("granular=false는 기존 6-인자 호출과 완전히 동일한 결과를 낸다")
    void granularFalse_isByteForByteTheLegacyBehavior() {
        List<Document> docs = List.of(
                section("## 1장\n\n" + "가".repeat(300)),
                section("### 1.1\n\n짧은 절."),
                section("## 2장\n\n" + "나".repeat(900)));

        List<Document> legacy = splitter.splitDocuments(docs, "g.md", 800, 50, 200, 0);
        List<Document> explicit = splitter.splitDocuments(docs, "g.md", 800, 50, 200, 0, false);

        assertThat(texts(explicit)).isEqualTo(texts(legacy));
    }

    @Test
    @DisplayName("isLeadInSection — 빈 본문/한 문장/두 문장은 도입부, 세 문장부터는 아니다")
    void isLeadInSection_unitCounting() {
        assertThat(splitter.isLeadInSection("## 제목")).isTrue();
        assertThat(splitter.isLeadInSection("## 제목\n\n한 문장입니다.")).isTrue();
        assertThat(splitter.isLeadInSection("## 제목\n\n하나. 둘.")).isTrue();
        assertThat(splitter.isLeadInSection("## 제목\n\n하나. 둘. 셋.")).isFalse();
        // 종결부호가 없어도 줄 수로 센다.
        assertThat(splitter.isLeadInSection("## 제목\n\n종결부호 없는 한 줄")).isTrue();
        assertThat(splitter.isLeadInSection("## 제목\n\n한 줄\n두 줄\n세 줄")).isFalse();
        // 연속 종결부호는 한 문장.
        assertThat(splitter.isLeadInSection("## 제목\n\n정말요?! 놀랍습니다...")).isTrue();
    }
}
