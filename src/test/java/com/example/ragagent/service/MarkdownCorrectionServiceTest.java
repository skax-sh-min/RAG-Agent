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
 * QA — MarkdownCorrectionService split + deterministic-overlap correction.
 *
 * <p>The correction pipeline splits raw MD into sections, corrects each in parallel, and
 * reassembles. Two things are safety-critical:
 * <ul>
 *   <li><b>Fence awareness</b> — batch/log dumps pasted into a fenced code block often contain
 *       lines like {@code "### Job ID : ..."} that look like headings but are just log content;
 *       splitting the fence in half sends each half to the LLM with no idea it's inside a code
 *       block, producing hallucinated language tags and leaked prompt delimiters.</li>
 *   <li><b>No boundary duplication</b> — at an UNNATURAL boundary a small overlap of the
 *       neighbour's lines is corrected in place around a marker, then cut back off
 *       DETERMINISTICALLY in code, so the overlap never survives into two adjacent sections.</li>
 * </ul>
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
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                java.util.List.of(), 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, 0.1, 8000));
        service = new MarkdownCorrectionService(llmRouter, props);
    }

    // ---------------------------------------------------------------------------------------------
    // splitBySections — fence awareness + chapter levels (##, ###, ####)
    // ---------------------------------------------------------------------------------------------

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
    @DisplayName("splitBySections — 펜스 밖의 실제 헤딩(##/###/####)은 여전히 섹션을 나눈다")
    void realHeadingsOutsideFenceStillSplit() {
        String md = """
                ## 첫 번째 절
                본문A

                ### 하위 절
                본문B

                #### 더 하위 절
                본문C
                """;

        List<String> sections = service.splitBySections(md);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0)).contains("첫 번째 절").contains("본문A");
        assertThat(sections.get(1)).contains("하위 절").contains("본문B");
        assertThat(sections.get(2)).startsWith("#### 더 하위 절").contains("본문C");
    }

    @Test
    @DisplayName("splitBySections — 문서 끝까지 펜스가 닫히지 않으면 안전하게 닫아준다 (기형 입력 방어)")
    void closesUnclosedFenceAtEndOfDocument() {
        String md = "```\n코드 내용\n(닫는 펜스 없음)";

        List<String> sections = service.splitBySections(md);

        assertThat(sections).hasSize(1);
        assertThat(countOccurrences(sections.get(0), "```")).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // splitByPages — PPTX slide bundling + oversized-slide split by shape-group blocks
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("splitByPages — 작은 슬라이드들은 한 교정 호출로 묶고, 페이지 안의 ###(소제목)은 나누지 않는다")
    void splitByPages_bundlesSmallSlides_keepingSlidesWhole() {
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

        // 두 슬라이드 모두 작으므로 한 번의 교정 호출(번들)로 묶인다 — 내용/순서/마커는 모두 보존
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0))
                .contains("[페이지: 1]").contains("1장 개요").contains("프로젝트 배경").contains("본문A")
                .contains("[페이지: 2]").contains("본문B");
    }

    @Test
    @DisplayName("splitByPages — 최대 페이지 수를 넘으면 다음 번들로 나뉘고, 각 슬라이드는 항상 온전히 유지된다")
    void splitByPages_startsNewBundleAfterMaxPages() {
        StringBuilder sb = new StringBuilder();
        for (int n = 1; n <= 6; n++) {
            sb.append("[페이지: ").append(n).append("]\n## ").append(n).append("번 슬라이드\n본문\n\n");
        }

        List<String> sections = service.splitByPages(sb.toString());

        // 최대 4페이지/번들 → 6페이지는 [1-4] + [5-6] 두 번들
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0))
                .contains("[페이지: 1]").contains("[페이지: 4]").doesNotContain("[페이지: 5]");
        assertThat(sections.get(1))
                .contains("[페이지: 5]").contains("[페이지: 6]").doesNotContain("[페이지: 4]");
    }

    @Test
    @DisplayName("splitByPages — 한 슬라이드가 예산을 초과하면 [도형 그룹]/[다이어그램] 블록 경계로 쪼갠다")
    void splitByPages_splitsOversizedSlideAtShapeGroupBlocks() {
        String big = "x".repeat(3000);
        String md = "[페이지: 1]\n## 거대한 슬라이드\n서두 텍스트\n\n"
                + "[도형 그룹]\n" + big + "\n[/도형 그룹]\n\n"
                + "[다이어그램]\n" + big + "\n[/다이어그램]\n";

        List<String> sections = service.splitByPages(md);

        // 한 슬라이드(> maxSectionChars=3750)가 그룹 블록 경계에서 여러 교정 섹션으로 분리된다
        assertThat(sections.size()).isGreaterThanOrEqualTo(2);
        assertThat(sections.get(0)).startsWith("[페이지: 1]").contains("거대한 슬라이드");
        assertThat(sections.stream().anyMatch(s -> s.stripLeading().startsWith("[도형 그룹")))
                .isTrue();
        assertThat(sections.stream().anyMatch(s -> s.stripLeading().startsWith("[다이어그램")))
                .isTrue();
    }

    @Test
    @DisplayName("splitByPages — 펜스 안의 '[페이지: ...]'처럼 보이는 줄은 분할하지 않는다 (펜스 인식 공유)")
    void splitByPages_fenceAwarenessIsShared() {
        String md = "[페이지: 1]\n## 로그 슬라이드\n\n```\n[페이지: 999] 이건 로그 내용일 뿐\n```\n";

        List<String> sections = service.splitByPages(md);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0)).contains("[페이지: 999] 이건 로그 내용일 뿐");
    }

    // ---------------------------------------------------------------------------------------------
    // isUnnaturalBoundary — the 3 confirmed signals (non-heading start, level jump)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("isUnnaturalBoundary — 깔끔한 헤딩 경계(##→###, ####→##)는 자연스러움(false)")
    void unnatural_cleanHeadingBoundaries_areNatural() {
        assertThat(service.isUnnaturalBoundary("## 앞 절\n본문", "### 뒤 절\n본문")).isFalse();
        assertThat(service.isUnnaturalBoundary("## 앞 절\n본문", "## 다음 절\n본문")).isFalse();
        assertThat(service.isUnnaturalBoundary("#### 깊은 절\n본문", "## 상위로 복귀\n본문")).isFalse();
    }

    @Test
    @DisplayName("isUnnaturalBoundary — 헤딩이 아닌 줄로 시작하면(크기 강제분할 등) 부자연스러움(true)")
    void unnatural_nonHeadingStart_isUnnatural() {
        assertThat(service.isUnnaturalBoundary("## 앞 절\n본문", "문장 중간이 잘려 시작함")).isTrue();
    }

    @Test
    @DisplayName("isUnnaturalBoundary — 비정상 헤딩(# H1, #====, ########)으로 시작하면 부자연스러움(true)")
    void unnatural_malformedHeadingStart_isUnnatural() {
        assertThat(service.isUnnaturalBoundary("## 앞 절\n본문", "# 문서 제목처럼 보이는 H1")).isTrue();
        assertThat(service.isUnnaturalBoundary("## 앞 절\n본문", "#===== 배너 줄")).isTrue();
        assertThat(service.isUnnaturalBoundary("## 앞 절\n본문", "######### 장식용 해시")).isTrue();
    }

    @Test
    @DisplayName("isUnnaturalBoundary — 레벨이 2단계 이상 점프하면(## 다음 ####) 부자연스러움(true)")
    void unnatural_headingLevelJump_isUnnatural() {
        assertThat(service.isUnnaturalBoundary("## 앞 절\n본문", "#### 두 단계 건너뜀\n본문")).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // correctSection — deterministic overlap cut + fallback
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("correctSection — tailOverlap이 있으면 SECTION_END 마커 앞만 남기고 뒤(다음 섹션 미리보기)는 잘라낸다")
    void correctSection_withTailOverlap_keepsBeforeEndMarker() {
        String section = "이 섹션 본문\n";
        // LLM이 마커를 그대로 두고, 마커 뒤에 오버랩(다음 섹션 시작)을 교정해 함께 반환
        String llmResponse = "이 섹션 본문.\n<<<SECTION_END>>>\n다음 섹션 시작 줄 (잘려야 함)";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section, "다음 섹션 시작 줄");

        assertThat(result).isEqualTo("이 섹션 본문.");
        assertThat(result).doesNotContain("<<<SECTION_END>>>", "다음 섹션 시작");
    }

    @Test
    @DisplayName("correctSection — headOverlap이 있으면 SECTION_START 마커 뒤만 남기고 앞(이전 섹션 미리보기)은 잘라낸다")
    void correctSection_withHeadOverlap_keepsAfterStartMarker() {
        String section = "이 섹션 본문\n";
        String llmResponse = "이전 섹션 끝 줄 (잘려야 함)\n<<<SECTION_START>>>\n이 섹션 본문.";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section, "", "이전 섹션 끝 줄");

        assertThat(result).isEqualTo("이 섹션 본문.");
        assertThat(result).doesNotContain("<<<SECTION_START>>>", "이전 섹션 끝");
    }

    @Test
    @DisplayName("correctSection — head/tail 오버랩이 모두 있으면 두 마커 사이만 남긴다")
    void correctSection_withBothOverlaps_keepsBetweenMarkers() {
        String section = "이 섹션 본문\n";
        String llmResponse = "이전 섹션 끝\n<<<SECTION_START>>>\n이 섹션 본문.\n<<<SECTION_END>>>\n다음 섹션 시작";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section, "다음 섹션 시작", "이전 섹션 끝");

        assertThat(result).isEqualTo("이 섹션 본문.");
        assertThat(result).doesNotContain("이전 섹션 끝", "다음 섹션 시작",
                "<<<SECTION_START>>>", "<<<SECTION_END>>>");
    }

    @Test
    @DisplayName("correctSection — 오버랩이 있는데 LLM이 마커를 지워버리면 오버랩 없이 재교정한다")
    void correctSection_markerMissing_reCorrectsWithoutOverlap() {
        String section = "이 섹션 본문\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("이 섹션 본문.\n다음 섹션 시작이 섞여버림 (마커 없음)") // 1st: 마커 유실 → 신뢰 불가
                .thenReturn("이 섹션 본문.");                                    // 2nd: 오버랩 없이 재교정
        String result = service.correctSection(section, "다음 섹션 시작");

        assertThat(result).isEqualTo("이 섹션 본문.");
        // 마커 누락 감지 → 정확히 2번 호출(오버랩 포함 1회 + 재교정 1회)
        verify(llmRouter, times(2)).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("correctSection — 오버랩이 없으면 경계 처리 없이 LLM 교정 결과를 그대로 반환한다")
    void correctSection_noOverlap_returnsLlmResultVerbatim() {
        String section = "본문입니다\n\n```java\nSystem.out.println(1);\n```\n";
        String corrected = "본문 입니다.\n\n```java\nSystem.out.println(1);\n```\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(corrected);

        String result = service.correctSection(section);

        assertThat(result).isEqualTo(corrected);
    }

    // ---------------------------------------------------------------------------------------------
    // leading/trailing non-blank line helpers
    // ---------------------------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------------------------
    // reapplyHeadingNumbers (unchanged behaviour)
    // ---------------------------------------------------------------------------------------------

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
