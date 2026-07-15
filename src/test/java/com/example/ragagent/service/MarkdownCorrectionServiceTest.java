package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — MarkdownCorrectionService.splitBySections() fence awareness.
 *
 * Regression: batch/log dumps pasted into a fenced code block often contain lines like
 * {@code "### Job ID : ..."} that look like markdown H3 headings but are just log content.
 * Before this fix, splitBySections() ignored fence state and split the block in half at that
 * line — each half then went to the LLM separately with no idea it was inside (or missing) a
 * code fence, which reliably produced hallucinated language tags, re-wrapped fences, and leaked
 * "[/DOCUMENT]" prompt delimiters in the corrected output (reported by user with a real batch-job
 * log MD file).
 */
class MarkdownCorrectionServiceTest {

    private MarkdownCorrectionService service;
    private LlmRouter llmRouter;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        AppProperties props = mock(AppProperties.class);
        AppProperties.IndexingConfig indexing = mock(AppProperties.IndexingConfig.class);
        when(indexing.maxConcurrentLlmCalls()).thenReturn(2);
        when(props.indexingSafe()).thenReturn(indexing);
        service = new MarkdownCorrectionService(llmRouter, props, 8000);
    }

    @Test
    @DisplayName("splitBySections — 펜스 안의 '### ...' 로그 줄은 섹션을 분리하지 않는다 (배치 로그 재현)")
    void fencedLogLinesLookingLikeHeadingsDoNotSplitTheFence() {
        String md = """
                #### DB 배치 로그

                ```
                #################### BATCH START INFORMATION  ##############
                ### Job ID           : BEDU0001
                ### Job Instance ID  : BEDU000120210817L001
                ### Start time       : 2021-08-17 20:16:13
                #################### BATCH START INFORMATION  ##############
                ```

                ```
                #################### BATCH RESULT ##########################
                ### Job ID           : BEDU0001
                ### End time         : 2021-08-17 20:18:13
                #################### BATCH RESULT ##########################
                ```
                """;

        List<String> sections = service.splitBySections(md);

        assertThat(sections).hasSize(1);
        String section = sections.get(0);
        // both fences survive intact, each still opening exactly once and closing exactly once
        assertThat(countOccurrences(section, "```")).isEqualTo(4);
        assertThat(section).contains("### Job ID           : BEDU0001").contains("### End time");
    }

    @Test
    @DisplayName("splitBySections — 펜스 밖의 실제 헤딩은 여전히 섹션을 나눈다 (회귀 방지)")
    void realHeadingsOutsideFenceStillSplit() {
        String md = """
                ## 첫 번째 절
                본문A

                ## 두 번째 절
                본문B
                """;

        List<String> sections = service.splitBySections(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0)).contains("첫 번째 절").contains("본문A");
        assertThat(sections.get(1)).contains("두 번째 절").contains("본문B");
    }

    @Test
    @DisplayName("splitBySections — 펜스가 섹션 아주 초반(< MIN_SECTION_CHARS/2)에 시작하면 " +
            "크기 초과로도 다음 섹션으로 미루지 않고 그대로 자란다")
    void doesNotDeferFenceStartingVeryEarlyInSection() {
        StringBuilder sb = new StringBuilder("```\n"); // fence opens at offset 0 (< 250)
        for (int i = 0; i < 500; i++) sb.append("로그 라인 ").append(i).append('\n');
        sb.append("```\n");
        String md = sb.toString();
        assertThat(md.length()).isGreaterThan(500); // sanity: this would normally trigger a size flush

        List<String> sections = service.splitBySections(md);

        // The fence itself must survive as a single, balanced unit — a trailing blank section
        // from the final newline (a pre-existing split("\n", -1) artifact, unrelated to fences)
        // is harmless, so assert on fence balance rather than the total section count.
        List<String> withFence = sections.stream().filter(s -> s.contains("```")).toList();
        assertThat(withFence).hasSize(1);
        assertThat(countOccurrences(withFence.get(0), "```")).isEqualTo(2);
    }

    @Test
    @DisplayName("splitBySections — 펜스 시작 지점이 MIN_SECTION_CHARS/2 이상이면 " +
            "펜스 이전 내용을 flush하고 펜스 전체를 다음 섹션으로 미룬다")
    void defersFenceToNextSectionWhenItStartsPastThreshold() {
        // "before" content is 301 chars into the section when the fence opens (>= 250 threshold).
        String before = "A".repeat(300) + "\n";
        String openFence = "```\n";
        String fenceBody = "B".repeat(4000) + "\n"; // pushes current well past maxSectionChars(3750)
        String closeFence = "```\n";
        String md = before + openFence + fenceBody + closeFence;

        List<String> sections = service.splitBySections(md);

        assertThat(sections).hasSizeGreaterThanOrEqualTo(2);
        String beforeSection = sections.get(0);
        assertThat(beforeSection).doesNotContain("```").contains("A".repeat(300));

        String fenceSection = sections.stream()
                .filter(s -> s.contains("```"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no section contains the deferred fence"));
        assertThat(fenceSection).startsWith("```");
        assertThat(fenceSection).contains("B".repeat(4000));
        assertThat(countOccurrences(fenceSection, "```")).isEqualTo(2);
    }

    @Test
    @DisplayName("splitBySections — 문서 끝까지 펜스가 닫히지 않으면 안전하게 닫아준다 (기형 입력 방어)")
    void closesUnclosedFenceAtEndOfDocument() {
        String md = "```\n코드 내용\n(닫는 펜스 없음)";

        List<String> sections = service.splitBySections(md);

        assertThat(sections).hasSize(1);
        assertThat(countOccurrences(sections.get(0), "```")).isEqualTo(2);
    }

    @Test
    @DisplayName("splitByPages — [페이지: N] 마커 단위로만 분할하고, 페이지 안의 ###(소제목)은 분할하지 않는다")
    void splitByPages_groupsBySlideMarker_ignoringInnerSubheading() {
        // PptxToMarkdownConverter 출력 형태: 슬라이드 하나가 ##(챕터) + ###(부제목) 두 헤딩을 가질 수 있음
        String md = """
                [페이지: 1]
                ## 1장 개요

                ### 프로젝트 배경

                본문A

                [페이지: 2]
                ## 2번 슬라이드

                본문B
                """;

        List<String> sections = service.splitByPages(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0))
                .startsWith("[페이지: 1]")
                .contains("1장 개요").contains("프로젝트 배경").contains("본문A")
                .doesNotContain("[페이지: 2]");
        assertThat(sections.get(1))
                .startsWith("[페이지: 2]")
                .contains("본문B");
    }

    @Test
    @DisplayName("splitByPages — [페이지: N] 마커는 이전 슬라이드 꼬리가 아니라 자기 슬라이드 섹션의 맨 앞에 붙는다")
    void splitByPages_keepsPageMarkerAtFrontOfOwnSection() {
        String md = "[페이지: 1]\n## 첫 슬라이드\n본문A\n\n[페이지: 2]\n## 둘째 슬라이드\n본문B\n";

        List<String> sections = service.splitByPages(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0)).doesNotContain("[페이지: 2]"); // 다음 페이지 마커가 새어 들어오지 않음
        assertThat(sections.get(1)).startsWith("[페이지: 2]");
    }

    @Test
    @DisplayName("splitByPages — 펜스 안의 '[페이지: ...]'처럼 보이는 줄은 분할하지 않는다 (splitBySections와 동일한 펜스 인식 공유)")
    void splitByPages_fenceAwarenessIsShared() {
        String md = "[페이지: 1]\n## 로그 슬라이드\n\n```\n[페이지: 999] 이건 로그 내용일 뿐\n```\n";

        List<String> sections = service.splitByPages(md);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0)).contains("[페이지: 999] 이건 로그 내용일 뿐");
    }

    @Test
    @DisplayName("leadingNonBlankLines — 빈 줄/공백뿐인 줄은 세지 않고 건너뛴 뒤, 실제 내용 줄만 앞에서부터 N개 가져온다")
    void leadingNonBlankLines_skipsBlankLines() {
        String section = "\n   \n## 헤딩\n\n본문 첫줄\n\n본문 둘째줄\n본문 셋째줄\n본문 넷째줄\n";

        String result = MarkdownCorrectionService.leadingNonBlankLines(section, 3);

        assertThat(result).isEqualTo("## 헤딩\n본문 첫줄\n본문 둘째줄");
    }

    @Test
    @DisplayName("trailingNonBlankLines — 뒤에서부터 빈 줄/공백뿐인 줄을 건너뛰고, 실제 내용 줄만 N개를 원래 순서대로 가져온다")
    void trailingNonBlankLines_skipsBlankLines() {
        String section = "## 헤딩\n본문A\n\n본문B\n본문C\n\n   \n본문D\n";

        String result = MarkdownCorrectionService.trailingNonBlankLines(section, 3);

        assertThat(result).isEqualTo("본문B\n본문C\n본문D");
    }

    @Test
    @DisplayName("correctSection — lookahead가 있으면 RESULT_START/RESULT_END 사이만 추출하고, " +
            "마커 밖의 미리보기 잔여 텍스트는 위치(앞/뒤)와 무관하게 모두 버려진다")
    void correctSection_withLookahead_extractsOnlyBetweenResultMarkers() {
        String section = "#### DB 배치 로그\n\n```\n#### BATCH START ####\n### Job ID : BEDU0001\n```\n";
        String correctedBody = "#### DB 배치 로그\n\n```\n#### BATCH START ####\n### Job ID : BEDU0001\n```";
        // LLM이 지시대로 RESULT_START/RESULT_END로 감싸 반환하되, 마커 밖(뒤)에도 미리보기 잔여
        // 텍스트가 남아있는 상황 — 사용자가 보고한 "참고용으로 뒤에 붙인 문구가 그대로 남는" 케이스.
        // 마커 밖은 어디에 있든(앞이든 뒤든) LLM의 처리에 기대지 않고 코드가 결정론적으로 버린다.
        String llmResponse = "<<<RESULT_START>>>\n" + correctedBody + "\n<<<RESULT_END>>>\n"
                + "## 이건 다음 섹션 미리보기라 버려져야 함";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section, "## 이건 다음 섹션 미리보기라 버려져야 함");

        assertThat(result).isEqualTo(correctedBody);
        assertThat(result).doesNotContain("<<<RESULT_START>>>", "<<<RESULT_END>>>", "다음 섹션 미리보기");
    }

    @Test
    @DisplayName("correctSection — RESULT_START 앞에 붙은 전문/서두도 마커 밖이므로 버려진다")
    void correctSection_withLookahead_discardsPreambleBeforeStartMarker() {
        String section = "본문입니다\n";
        String llmResponse = "다음은 교정된 결과입니다:\n<<<RESULT_START>>>\n본문 입니다.\n<<<RESULT_END>>>\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section, "다음 섹션 미리보기");

        assertThat(result).isEqualTo("본문 입니다.");
    }

    @Test
    @DisplayName("correctSection — lookahead가 있는데 LLM이 RESULT_START/RESULT_END로 감싸지 않으면 " +
            "미리보기 유출을 막기 위해 lookahead 없이 재교정한다")
    void correctSection_lookaheadButMarkersMissing_reCorrectsWithoutLookahead() {
        String section = "본문입니다\n";
        // 첫 호출(lookahead 포함): 마커 없이 미리보기가 섞인 응답 → 신뢰 불가
        // 재호출(lookahead 없음): 깔끔한 교정 결과. 두 호출을 순서대로 스텁.
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("본문 입니다.\n다음 섹션 미리보기가 섞여버림")   // 1st: no RESULT_START/END
                .thenReturn("본문 입니다.");                                  // 2nd: clean re-correction

        String result = service.correctSection(section, "다음 섹션 미리보기");

        assertThat(result).isEqualTo("본문 입니다.");
        // 마커 누락 감지 → 정확히 2번 호출(lookahead 포함 1회 + 재교정 1회)
        verify(llmRouter, times(2)).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("correctSection — RESULT_START만 있고 RESULT_END가 없으면(끝 마커 누락) " +
            "lookahead 없이 재교정한다")
    void correctSection_startMarkerWithoutEndMarker_reCorrectsWithoutLookahead() {
        String section = "본문입니다\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("<<<RESULT_START>>>\n본문 입니다.\n다음 섹션 미리보기까지 이어짐") // no RESULT_END
                .thenReturn("본문 입니다.");

        String result = service.correctSection(section, "다음 섹션 미리보기");

        assertThat(result).isEqualTo("본문 입니다.");
        verify(llmRouter, times(2)).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("correctSection — lookbehind만 있어도 RESULT_START/RESULT_END 사이만 추출하고, " +
            "마커 앞(전)에 남은 이전 섹션 미리보기 잔여물은 버려진다 (lookahead 쪽과 대칭)")
    void correctSection_withLookbehindOnly_extractsOnlyBetweenResultMarkers() {
        String section = "본문입니다\n";
        String correctedBody = "본문 입니다.";
        String llmResponse = "## 이건 이전 섹션 미리보기라 버려져야 함\n<<<RESULT_START>>>\n"
                + correctedBody + "\n<<<RESULT_END>>>";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section, "", "## 이건 이전 섹션 미리보기라 버려져야 함");

        assertThat(result).isEqualTo(correctedBody);
        assertThat(result).doesNotContain("<<<RESULT_START>>>", "<<<RESULT_END>>>", "이전 섹션 미리보기");
    }

    @Test
    @DisplayName("correctSection — lookbehind와 lookahead가 함께 있어도 RESULT_START/RESULT_END " +
            "사이만 추출하고 양쪽 미리보기 잔여물이 모두 버려진다")
    void correctSection_withBothLookbehindAndLookahead_extractsOnlyBetweenResultMarkers() {
        String section = "본문입니다\n";
        String correctedBody = "본문 입니다.";
        String llmResponse = "이전 섹션 잔여물\n<<<RESULT_START>>>\n" + correctedBody
                + "\n<<<RESULT_END>>>\n다음 섹션 잔여물";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section, "다음 섹션 미리보기", "이전 섹션 미리보기");

        assertThat(result).isEqualTo(correctedBody);
        assertThat(result).doesNotContain("이전 섹션 잔여물", "다음 섹션 잔여물");
    }

    @Test
    @DisplayName("correctSection — lookbehind만 있는데 RESULT_START/RESULT_END로 감싸지 않으면 " +
            "경계 컨텍스트 없이 재교정한다")
    void correctSection_lookbehindOnlyMarkersMissing_reCorrectsWithoutBoundaryContext() {
        String section = "본문입니다\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("이전 섹션 미리보기가 섞여버림\n본문 입니다.")   // 1st: no RESULT_START/END
                .thenReturn("본문 입니다.");                                  // 2nd: clean re-correction

        String result = service.correctSection(section, "", "이전 섹션 미리보기");

        assertThat(result).isEqualTo("본문 입니다.");
        verify(llmRouter, times(2)).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("correctSection — lookahead가 없으면 경계 처리 없이 LLM 교정 결과를 그대로 반환한다")
    void correctSection_noLookahead_returnsLlmResultVerbatim() {
        String section = "본문입니다\n\n```java\nSystem.out.println(1);\n```\n";
        String corrected = "본문 입니다.\n\n```java\nSystem.out.println(1);\n```\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(corrected);

        String result = service.correctSection(section);

        assertThat(result).isEqualTo(corrected);
    }

    @Test
    @DisplayName("reapplyHeadingNumbers — 번호 붙은 헤딩이 하나도 없으면 그대로 반환한다(새로 번호를 붙이지 않음)")
    void reapplyHeadingNumbers_noNumberedHeadings_returnsUnchanged() {
        String md = "## 첫 번째 절\n본문A\n\n## 두 번째 절\n본문B\n";

        String result = service.reapplyHeadingNumbers(md);

        assertThat(result).isEqualTo(md);
    }

    @Test
    @DisplayName("reapplyHeadingNumbers — 이미 번호가 있던 문서에서 헤딩이 줄면(코드블록 편집 등) 남은 헤딩 번호를 다시 계산한다")
    void reapplyHeadingNumbers_headingRemoved_renumbersRemaining() {
        // 원래 3개였던 헤딩(1./2./3.) 중 가운데가 편집으로 사라져 번호가 어긋난 상황을 재현
        String md = "## 1. 첫 번째 절\n본문A\n\n## 3. 세 번째 절\n본문B\n";

        String result = service.reapplyHeadingNumbers(md);

        assertThat(result).contains("## 1. 첫 번째 절").contains("## 2. 세 번째 절");
        assertThat(result).doesNotContain("## 3.");
    }

    @Test
    @DisplayName("reapplyHeadingNumbers — 코드 블록 안의 번호처럼 보이는 줄은 헤딩으로 취급하지 않는다")
    void reapplyHeadingNumbers_ignoresFencedContent() {
        String md = "## 1. 첫 번째 절\n"
                + "```\n"
                + "## 2. 이건 로그 내용일 뿐\n"
                + "```\n"
                + "\n## 2. 두 번째 절\n본문\n";

        String result = service.reapplyHeadingNumbers(md);

        assertThat(result).contains("## 2. 이건 로그 내용일 뿐"); // 펜스 안은 그대로
        assertThat(result).contains("## 2. 두 번째 절"); // 펜스 밖 헤딩은 정상적으로 2번 유지
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
