package com.example.ragagent.service;

import org.junit.jupiter.api.parallel.ResourceLock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
@ResourceLock("global-state")
class MarkdownCorrectionServiceTest {

    private MarkdownCorrectionService service;
    private LlmRouter llmRouter;
    private Logger correctionLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        AppProperties props = mock(AppProperties.class);
        AppProperties.IndexingConfig indexing = mock(AppProperties.IndexingConfig.class);
        when(indexing.maxConcurrentLlmCalls()).thenReturn(2);
        when(props.indexingSafe()).thenReturn(indexing);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                java.util.List.of(), 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, 0.1, 8000, true));
        service = new MarkdownCorrectionService(llmRouter, props);
    }

    @BeforeEach
    void attachLogCapture() {
        correctionLogger = (Logger) org.slf4j.LoggerFactory.getLogger(MarkdownCorrectionService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        correctionLogger.addAppender(logAppender);
        correctionLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachLogCapture() {
        correctionLogger.detachAppender(logAppender);
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
    @DisplayName("splitBySections — 헤딩 없는 PDF도 [페이지: N] 마커를 경계로 페이지마다 나뉜다")
    void splitBySections_splitsOnPageMarkerForHeadinglessPdf() {
        // PdfToMarkdownConverter no longer emits "## N페이지", so the page marker is the only
        // per-page boundary the correction step can split on.
        String md = """
                [페이지: 1]
                첫 페이지 본문입니다.

                [페이지: 2]
                둘째 페이지 본문입니다.

                [페이지: 3]
                셋째 페이지 본문입니다.
                """;

        List<String> sections = service.splitBySections(md);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0)).startsWith("[페이지: 1]").contains("첫 페이지 본문");
        assertThat(sections.get(1)).startsWith("[페이지: 2]").contains("둘째 페이지 본문");
        assertThat(sections.get(2)).startsWith("[페이지: 3]").contains("셋째 페이지 본문");
    }

    @Test
    @DisplayName("splitBySections — 펜스 안의 [페이지: N] 같은 줄은 경계로 취급하지 않는다")
    void splitBySections_pageMarkerInsideFenceDoesNotSplit() {
        String md = """
                [페이지: 1]
                로그 예시:

                ```
                [페이지: 2]
                (펜스 안 — 진짜 페이지 마커가 아님)
                ```
                """;

        List<String> sections = service.splitBySections(md);

        assertThat(sections).hasSize(1); // the fenced [페이지: 2] must not start a new section
        assertThat(countOccurrences(sections.get(0), "```")).isEqualTo(2);
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
    // correctSection — table protection (LLM must never see/touch table content)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("correctSection — 표 셀 내용은 LLM 프롬프트에 그대로 노출되지 않고 [TABLE_PLACEHOLDER_N]으로만 전달된다")
    void correctSection_withTable_neverExposesTableContentToLlm() {
        String section = """
                ## 설정값

                | 항목 | 설명 |
                | --- | --- |
                | 포트: 8080 | 서버 포트 |
                """;

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeWithTracking(any(), any(), any(), callCaptor.capture())).thenReturn(section);

        service.correctSection(section);

        ChatModel mockModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(mockModel.call(promptCaptor.capture())).thenReturn(null);
        callCaptor.getValue().apply(mockModel);

        String sentPrompt = promptCaptor.getValue().getContents();
        assertThat(sentPrompt).doesNotContain("포트: 8080");
        assertThat(sentPrompt).doesNotContain("| 항목 | 설명 |");
        assertThat(sentPrompt).contains("[TABLE_PLACEHOLDER_0]");
    }

    @Test
    @DisplayName("correctSection — LLM이 자리표시자를 그대로 두면 원본 표가 정확히 복원된다(셀 안 ':' 보존, '|'로 오염되지 않음)")
    void correctSection_withTable_restoresOriginalTableVerbatim() {
        String section = """
                ## 설정값

                | 항목 | 값 |
                | --- | --- |
                | 포트: 8080 | 기본값 |
                | 제한: 100 | 초당 요청 |
                """;
        // LLM이 표를 [TABLE_PLACEHOLDER_0]로 받아 그대로 반환했다고 가정(정상 동작).
        String llmResponse = "## 설정값\n\n[TABLE_PLACEHOLDER_0]\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section);

        assertThat(result).contains("| 포트: 8080 | 기본값 |", "| 제한: 100 | 초당 요청 |");
        assertThat(result).doesNotContain("[TABLE_PLACEHOLDER_0]");
    }

    @Test
    @DisplayName("correctSection — 표가 여러 개면 각각 독립된 자리표시자로 보호되고 순서대로 복원된다")
    void correctSection_withMultipleTables_eachRestoredIndependently() {
        String section = """
                ## 첫 번째 표

                | A | B |
                | --- | --- |
                | 시간: 10:00 | 첫 번째 |

                본문 설명입니다.

                ## 두 번째 표

                | C | D |
                | --- | --- |
                | 비율: 50% | 두 번째 |
                """;
        String llmResponse = "## 첫 번째 표\n\n[TABLE_PLACEHOLDER_0]\n\n본문 설명입니다.\n\n"
                + "## 두 번째 표\n\n[TABLE_PLACEHOLDER_1]\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section);

        assertThat(result).contains("| 시간: 10:00 | 첫 번째 |", "| 비율: 50% | 두 번째 |");
        assertThat(result).doesNotContain("[TABLE_PLACEHOLDER_0]", "[TABLE_PLACEHOLDER_1]");
    }

    @Test
    @DisplayName("correctSection — 응답에서 표 자리표시자가 유실되면 신뢰할 수 없으므로 원본 섹션을 그대로 반환한다")
    void correctSection_tablePlaceholderMissing_fallsBackToOriginalSection() {
        String section = """
                ## 설정값

                | 항목 | 값 |
                | --- | --- |
                | 포트: 8080 | 기본값 |
                """;
        // 자리표시자를 지워버리거나 다른 형태로 바꿔버린 응답 — 표 위치를 신뢰할 수 없다.
        String llmResponse = "## 설정값\n\n(표가 사라짐)\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(llmResponse);

        String result = service.correctSection(section);

        assertThat(result).isEqualTo(section);
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
    // normalizeCodeContent — collapse blank lines except before a multi-line comment or a
    // comment-less function/class start
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("normalizeCodeContent — 본문 중간의 빈 줄은 전부 제거된다")
    void normalizeCodeContent_removesOrdinaryBlankLines() {
        String code = "int a = 1;\n\n\nint b = 2;\n\nint c = 3;";

        String result = service.normalizeCodeContent(code);

        assertThat(result).isEqualTo("int a = 1;\nint b = 2;\nint c = 3;");
    }

    @Test
    @DisplayName("normalizeCodeContent — 여러 줄 블록 주석(/** */) 앞에는 빈 줄 1개를 남긴다")
    void normalizeCodeContent_keepsOneBlankBeforeBlockComment() {
        String code = "int a = 1;\n\n\n/**\n * 설명\n */\nvoid foo() {}";

        String result = service.normalizeCodeContent(code);

        assertThat(result).isEqualTo("int a = 1;\n\n/**\n * 설명\n */\nvoid foo() {}");
    }

    @Test
    @DisplayName("normalizeCodeContent — 연속된 두 줄 이상의 라인 주석(//) 앞에는 빈 줄 1개를 남긴다")
    void normalizeCodeContent_keepsOneBlankBeforeConsecutiveLineComments() {
        String code = "int a = 1;\n\n// line1\n// line2\nvoid foo() {}";

        String result = service.normalizeCodeContent(code);

        assertThat(result).isEqualTo("int a = 1;\n\n// line1\n// line2\nvoid foo() {}");
    }

    @Test
    @DisplayName("normalizeCodeContent — 단일 줄 주석 하나뿐이면 여러 줄 주석이 아니므로 앞의 빈 줄이 제거된다")
    void normalizeCodeContent_removesBlankBeforeSingleLineComment() {
        String code = "int a = 1;\n\n// only one line\nvoid foo() {}";

        String result = service.normalizeCodeContent(code);

        assertThat(result).isEqualTo("int a = 1;\n// only one line\nvoid foo() {}");
    }

    @Test
    @DisplayName("normalizeCodeContent — 주석이 없는 함수 시작부 앞에는 빈 줄 1개를 남긴다")
    void normalizeCodeContent_keepsOneBlankBeforeUncommentedFunction() {
        String code = "int a = 1;\n\n\npublic void bar() {\n    int x = 1;\n}";

        String result = service.normalizeCodeContent(code);

        assertThat(result).isEqualTo("int a = 1;\n\npublic void bar() {\n    int x = 1;\n}");
    }

    @Test
    @DisplayName("normalizeCodeContent — 함수 바로 위에 이미 주석이 있으면 주석-함수 사이의 빈 줄은 추가하지 않는다")
    void normalizeCodeContent_noExtraBlankBetweenCommentAndFunction() {
        String code = "int a = 1;\n\n// 설명\n\npublic void bar() {\n}";

        String result = service.normalizeCodeContent(code);

        // 주석 앞에는 빈 줄 1개(다중 주석 아니므로 실제로는 제거), 주석-함수 사이엔 추가 안 함
        assertThat(result).isEqualTo("int a = 1;\n// 설명\npublic void bar() {\n}");
    }

    @Test
    @DisplayName("normalizeCodeContent — 파이썬 def/class와 셸 함수도 함수 시작으로 인식한다")
    void normalizeCodeContent_recognizesPythonAndShellFunctionStarts() {
        String python = "x = 1\n\n\ndef foo():\n    pass";
        String shell = "VAR=1\n\n\ngreet() {\n    echo hi\n}";

        assertThat(service.normalizeCodeContent(python)).isEqualTo("x = 1\n\ndef foo():\n    pass");
        assertThat(service.normalizeCodeContent(shell)).isEqualTo("VAR=1\n\ngreet() {\n    echo hi\n}");
    }

    @Test
    @DisplayName("normalizeCodeContent — if/for 같은 제어문은 함수 시작으로 오인하지 않는다")
    void normalizeCodeContent_doesNotTreatControlFlowAsFunctionStart() {
        String code = "int a = 1;\n\n\nif (a > 0) {\n    a++;\n}";

        String result = service.normalizeCodeContent(code);

        assertThat(result).isEqualTo("int a = 1;\nif (a > 0) {\n    a++;\n}");
    }

    @Test
    @DisplayName("normalizeCodeContent — 코드 블록 맨 앞의 빈 줄은 절대 추가되지 않는다")
    void normalizeCodeContent_neverAddsLeadingBlankLine() {
        String code = "\n\n/**\n * 설명\n */\nvoid foo() {}";

        String result = service.normalizeCodeContent(code);

        assertThat(result).isEqualTo("/**\n * 설명\n */\nvoid foo() {}");
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

    // ---------------------------------------------------------------------------------------------
    // 인덱싱 중 이미지 설명(Vision) — LOCAL 프로바이더로 tracked 경로 + distinct 이미지 dedup
    // ---------------------------------------------------------------------------------------------

    @TempDir
    Path tmpDir;

    /** describeImage()가 mock을 통해 실제로 호출되도록 최소한의 스텁을 건다. */
    private void stubVisionAndCorrection(String imageDesc) {
        when(llmRouter.routeProvider(eq(TaskType.VISION), eq(RoutingMode.LOCAL_ONLY)))
                .thenReturn(mock(LlmProvider.class)); // 게이트 통과
        // 섹션 교정 호출(MDCORRECT)은 non-null만 반환하면 됨 — 이 테스트의 관심사가 아님
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("교정됨");
        // 이미지 설명 호출(VISION/LOCAL_ONLY/IMAGE_PREFIX)은 별도 값 — 마지막 스텁이 우선
        when(llmRouter.executeWithTracking(eq(TaskType.VISION), eq(RoutingMode.LOCAL_ONLY),
                eq(BackgroundUsage.IMAGE_PREFIX), any())).thenReturn(imageDesc);
    }

    private Path writeImage(String name) throws Exception {
        Path p = tmpDir.resolve(name);
        Files.write(p, new byte[]{(byte) 0x89, 'P', 'N', 'G'}); // 내용은 무의미(호출은 mock)
        return p;
    }

    @Test
    @DisplayName("이미지 설명 — VISION·LOCAL_ONLY·IMAGE_PREFIX tracked 경로로 호출된다(사용량 집계됨)")
    void imageDescription_routedThroughTrackedLocalVisionPath() throws Exception {
        stubVisionAndCorrection("도표 설명");
        Path img = writeImage("a.png");
        String md = "## 절\n[이미지: " + img.toAbsolutePath() + "]\n본문\n";

        service.correct(md, "doc", null, true); // addImageDescriptions=true

        // 핵심: 이미지 분석이 raw ChatClient가 아니라 tracked executeWithTracking(IMAGE_PREFIX)으로 감
        verify(llmRouter, times(1)).executeWithTracking(
                eq(TaskType.VISION), eq(RoutingMode.LOCAL_ONLY), eq(BackgroundUsage.IMAGE_PREFIX), any());
    }

    @Test
    @DisplayName("이미지 설명 — 같은 이미지를 여러 마커가 가리켜도 Vision 호출은 한 번만(distinct dedup)")
    void imageDescription_dedupsSameImageAcrossMarkers() throws Exception {
        stubVisionAndCorrection("설명");
        Path img = writeImage("shared.png");
        String abs = img.toAbsolutePath().toString();
        String md = "## 절1\n[이미지: " + abs + "]\n본문1\n\n## 절2\n[이미지: " + abs + "]\n본문2\n";

        service.correct(md, "doc", null, true);

        // prewarm이 distinct 경로만 모으고 캐시를 공유하므로 동일 파일은 1회만 분석
        verify(llmRouter, times(1)).executeWithTracking(
                eq(TaskType.VISION), eq(RoutingMode.LOCAL_ONLY), eq(BackgroundUsage.IMAGE_PREFIX), any());
    }

    @Test
    @DisplayName("이미지 설명 — onImageDescribed 콜백이 (0,total) 시작 신호 후 이미지마다 (N,total)로 호출된다")
    void imageDescription_reportsProgressViaCallback() throws Exception {
        stubVisionAndCorrection("설명");
        Path img1 = writeImage("p1.png");
        Path img2 = writeImage("p2.png");
        String md = "## 절1\n[이미지: " + img1.toAbsolutePath() + "]\n본문1\n\n"
                + "## 절2\n[이미지: " + img2.toAbsolutePath() + "]\n본문2\n";

        List<int[]> calls = new CopyOnWriteArrayList<>();
        BiConsumer<Integer, Integer> onImageDescribed = (done, total) -> calls.add(new int[]{done, total});

        service.correct(md, "doc", null, true, false, false, null, onImageDescribed);

        // 시작 신호(0,2) + 이미지 2장 완료마다 1회씩(합쳐서 1과 2) — 완료 순서는 병렬이라 비결정적.
        assertThat(calls).hasSize(3);
        assertThat(calls).allSatisfy(c -> assertThat(c[1]).isEqualTo(2));
        assertThat(calls.get(0)[0]).isEqualTo(0); // 시작 신호는 futures 생성 전 동기 호출이라 항상 첫 번째
        assertThat(calls.stream().map(c -> c[0]).toList()).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    @DisplayName("이미지 설명 — addImageDescriptions=false면 Vision 호출이 전혀 없다")
    void imageDescription_notInvokedWhenDisabled() throws Exception {
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("교정됨");
        Path img = writeImage("b.png");
        String md = "## 절\n[이미지: " + img.toAbsolutePath() + "]\n본문\n";

        service.correct(md, "doc", null); // addImageDescriptions 기본 false

        verify(llmRouter, never()).executeWithTracking(
                eq(TaskType.VISION), any(), eq(BackgroundUsage.IMAGE_PREFIX), any());
    }

    @Test
    @DisplayName("코드 언어 추론 — addHeadingNumbers=false 여도(PPTX·체크박스 off) 라벨 없는 코드 블록에 언어 태그가 붙는다")
    void codeLanguageInference_runsEvenWhenHeadingNumbersDisabled() {
        String section = "## 코드\n```\npublic class Foo {\n    private int x;\n}\n```\n";
        // LLM 교정은 섹션을 그대로 돌려주도록 스텁 — 관심사는 교정 이후의 언어 추론 패스다.
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(section);

        String withoutHeadingNumbers = service.correct(section, "doc", null, false, false, false, null);
        String withHeadingNumbers = service.correct(section, "doc", null, false, true, false, null);

        assertThat(withoutHeadingNumbers).contains("```java");
        assertThat(withHeadingNumbers).contains("```java"); // 기존 경로도 그대로 동작
    }

    @Test
    @DisplayName("코드 언어 추론 — 이미 태그가 있으면 보존하고, 소제목 번호는 addHeadingNumbers=true 일 때만 붙는다")
    void codeLanguageInference_keepsExistingTagAndHeadingNumbersStayGated() {
        String section = "## 코드\n```python\nprint('hi')\n```\n";
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn(section);

        String withoutHeadingNumbers = service.correct(section, "doc", null, false, false, false, null);
        String withHeadingNumbers = service.correct(section, "doc", null, false, true, false, null);

        assertThat(withoutHeadingNumbers).contains("```python").contains("## 코드");
        assertThat(withoutHeadingNumbers).doesNotContain("## 1. 코드"); // 번호는 여전히 붙지 않는다
        assertThat(withHeadingNumbers).contains("```python").contains("## 1. 코드");
    }

    @Test
    @DisplayName("correctSection — 응답의 펜스 개수가 홀수면 그 섹션은 교정을 버리고 원본을 유지한다")
    void correctSection_keepsOriginalWhenResponseFencesUnbalanced() {
        String section = "## 1장\n\n```java\nint a = 1;\n```\n";
        // 모델이 닫는 펜스를 빠뜨린 응답 — 그대로 두면 이어붙인 문서 전체의 펜스 짝이 밀린다.
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("## 1장\n\n```java\nint a = 1;\n모델이 덧붙인 줄\n");

        String out = service.correct(section, "doc", null, false, false, false, null);

        assertThat(out).doesNotContain("모델이 덧붙인 줄");
        assertThat(out).contains("## 1장").contains("```java").contains("int a = 1;");
        assertThat(logAppender.list).anyMatch(e ->
                e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("응답의 코드 펜스 개수가 홀수"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ---------------------------------------------------------------------------------------------
    // Deterministic post-processing (fixClosingFences / postProcessMarkdown / looksLikeTableRow)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("fixClosingFences — 언어 태그가 붙은 닫는 펜스(```sql)는 순수 ``` 로 교정, 여는 펜스는 유지")
    void fixClosingFences_stripsLangFromCloser() {
        String md = "```sql\nSELECT 1;\n```sql\n";
        String fixed = MarkdownCorrectionService.fixClosingFences(md);
        assertThat(fixed).isEqualTo("```sql\nSELECT 1;\n```\n");
    }

    @Test
    @DisplayName("fixClosingFences — 닫는 펜스 없이 챕터 제목이 나오면 그 앞에 닫는 펜스를 삽입해 치유한다")
    void fixClosingFences_healsUnclosedFenceBeforeChapterHeading() {
        String md = "```java\nint x = 1;\n## 다음 장\n본문";
        String fixed = MarkdownCorrectionService.fixClosingFences(md);
        assertThat(fixed).isEqualTo("```java\nint x = 1;\n```\n## 다음 장\n본문");
    }

    @Test
    @DisplayName("fixClosingFences — 7단계(#######)까지 챕터 제목으로 인정해 펜스를 치유한다")
    void fixClosingFences_healsUnclosedFenceAtLevelSeven() {
        String md = "```\ncode\n####### 레벨7 제목\n";
        String fixed = MarkdownCorrectionService.fixClosingFences(md);
        assertThat(fixed).isEqualTo("```\ncode\n```\n####### 레벨7 제목\n");
    }

    @Test
    @DisplayName("fixClosingFences — 8단계(########) 이상은 챕터 제목으로 보지 않아 펜스를 치유하지 않는다")
    void fixClosingFences_doesNotHealAtLevelEightOrMore() {
        String md = "```\ncode\n######## 아님\nmore code\n```";
        String fixed = MarkdownCorrectionService.fixClosingFences(md);
        assertThat(fixed).isEqualTo(md); // unchanged — no premature close inserted
    }

    @Test
    @DisplayName("fixClosingFences — 내용이 '#'으로 끝나는 배너 주석(### 주석 ###)은 제목으로 보지 않는다")
    void fixClosingFences_doesNotHealOnBannerCommentWithTrailingHash() {
        String md = "```c\n### 주석 ###\nint x = 1;\n```";
        String fixed = MarkdownCorrectionService.fixClosingFences(md);
        assertThat(fixed).isEqualTo(md); // unchanged — treated as a comment, not a heading
    }

    @Test
    @DisplayName("fixClosingFences — 순수 구분용 배너(### ###)도 제목으로 보지 않는다")
    void fixClosingFences_doesNotHealOnHashOnlyBanner() {
        String md = "```c\n### ###\nint x = 1;\n```";
        String fixed = MarkdownCorrectionService.fixClosingFences(md);
        assertThat(fixed).isEqualTo(md);
    }

    @Test
    @DisplayName("fixClosingFences — 문서 끝에서 열린 채 끝난 펜스는 닫는 펜스를 붙여 치유한다")
    void fixClosingFences_healsUnclosedFenceAtEndOfInput() {
        String md = "## 1장\n\n본문\n\n```java\nint x = 1;\n";
        String fixed = MarkdownCorrectionService.fixClosingFences(md);
        assertThat(fixed).isEqualTo("## 1장\n\n본문\n\n```java\nint x = 1;\n\n```");
        assertThat(fenceLines(fixed) % 2).isZero();
    }

    @Test
    @DisplayName("fixClosingFences — [페이지: N] 마커도 치유 지점 (PPTX/PDF는 ## 소제목이 없다)")
    void fixClosingFences_healsUnclosedFenceBeforePageMarker() {
        // 소제목이 전혀 없는 PPTX/비스캔 PDF 형태 — 치유가 없으면 뒤따르는 진짜 여는 펜스가
        // 닫는 펜스로 오인돼 문서 나머지 전체의 펜스 짝이 밀린다.
        String md = "[페이지: 1]\n\n```text\n설명 줄\n\n[페이지: 2]\n\n```java\nint a = 1;\n```java\n\n마무리\n";
        String fixed = MarkdownCorrectionService.fixClosingFences(md);

        assertThat(fixed).isEqualTo(
                "[페이지: 1]\n\n```text\n설명 줄\n\n```\n[페이지: 2]\n\n```java\nint a = 1;\n```\n\n마무리\n");
        assertThat(fenceLines(fixed) % 2).isZero();
        // 진짜 여는 펜스는 태그를 지켰고, 에코된 닫는 펜스만 순수 ``` 로 바뀌었다
        assertThat(countOccurrences(fixed, "```java\n")).isEqualTo(1);
    }

    @Test
    @DisplayName("fixClosingFences — 펜스 안의 [페이지: N]처럼 보이는 줄은 들여쓰기되어 있으면 치유 지점이 아니다")
    void fixClosingFences_pageMarkerHealingRequiresLineStart() {
        String md = "```text\n  [페이지: 2] 는 마커가 아니라 코드 내용\n```";
        assertThat(MarkdownCorrectionService.fixClosingFences(md)).isEqualTo(md);
    }

    @Test
    @DisplayName("normalizeCodeBlocks — 펜스 개수가 홀수면 언어 태그를 쓰지 않고 원본을 그대로 둔다")
    void normalizeCodeBlocks_skipsWhenFenceCountIsOdd() {
        // 닫는 펜스가 없는 문서 — 정규식이 짝을 한 칸 밀어 잡으면 '닫는 펜스'에 태그를 찍게 된다.
        String md = "```\n{\"a\": 1}\n```\n\n```\nint b = 2;\n";
        String out = service.normalizeCodeBlocks(md, true);
        assertThat(out).isEqualTo(md);
        assertThat(logAppender.list).anyMatch(e ->
                e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("코드 펜스 짝을 확정할 수 없어"));
    }

    @Test
    @DisplayName("normalizeCodeBlocks — 줄 중간 ``` 이 있으면(줄 단위 뷰와 정규식 뷰 불일치) 건너뛴다")
    void normalizeCodeBlocks_skipsWhenMidLineFencePresent() {
        // "…감쌉니다: ```" 의 ``` 는 줄 단위 패스에는 안 보이지만 FENCED_BLOCK 정규식에는 보인다.
        // 가드가 없으면 이 ``` 가 아래 ```java 를 닫는 펜스로 삼아 짝을 밀어버린다.
        String md = "코드는 다음처럼 감쌉니다: ```\n\n본문 문단\n\n```java\nint a = 1;\n```\n\n끝 문장\n";
        String out = service.normalizeCodeBlocks(md, true);
        assertThat(out).isEqualTo(md);
        assertThat(logAppender.list).anyMatch(e ->
                e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("코드 펜스 짝을 확정할 수 없어"));
    }

    @Test
    @DisplayName("재현 — 소제목 없는 문서의 미닫힘 펜스가 '```java 다음에 ```java'로 번지지 않는다")
    void unclosedFenceDoesNotPropagateDuplicateLanguageTag() {
        // 보고된 증상: 저장된 md에서 ```java 의 짝이 ``` 가 아니라 ```java 였다.
        // 원인은 앞쪽 미닫힘 펜스로 짝이 밀린 뒤 normalizeCodeBlocks 가 '닫는 펜스'에 태그를 써넣는 것.
        String joined = "[페이지: 1]\n\n```text\n설명 줄\n\n[페이지: 2]\n\n```java\nint a = 1;\n```java\n\n마무리\n";

        String saved = service.normalizeCodeBlocks(MarkdownCorrectionService.fixClosingFences(joined), true);

        assertThat(fenceLines(saved) % 2).isZero();               // 짝이 맞는다
        assertThat(countOccurrences(saved, "```java")).isEqualTo(1); // 여는 펜스 하나뿐
        assertThat(saved).contains("\n[페이지: 2]");               // 산문이 코드 블록에 삼켜지지 않았다
    }

    @Test
    @DisplayName("normalizeCodeBlocks — 펜스 짝이 정상이면 기존대로 언어 태그를 추론한다(가드 회귀 없음)")
    void normalizeCodeBlocks_stillNormalizesBalancedDocument() {
        String md = "설명\n\n```\n{\"a\": 1}\n```\n";
        assertThat(service.normalizeCodeBlocks(md, true)).isEqualTo("설명\n\n```json\n{\"a\": 1}\n```\n");
    }

    /** 펜스 줄(앞 공백 제외하고 ``` 로 시작하는 줄) 개수 — 짝이 맞는지 확인용. */
    private static int fenceLines(String text) {
        int count = 0;
        for (String line : text.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) count++;
        }
        return count;
    }

    @Test
    @DisplayName("fixClosingFences — 닫는 펜스 언어 태그 제거 시 라인 번호·사유가 DEBUG 로그로 남는다")
    void fixClosingFences_logsClosingFenceTagStrip() {
        String md = "```sql\nSELECT 1;\n```sql\n"; // 닫는 펜스는 3행

        MarkdownCorrectionService.fixClosingFences(md);

        assertThat(logAppender.list).anyMatch(e ->
                e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage().contains("3행")
                        && e.getFormattedMessage().contains("```sql")
                        && e.getFormattedMessage().contains("짝을 맞추기"));
    }

    @Test
    @DisplayName("fixClosingFences — 펜스 치유 시 라인 번호·사유가 DEBUG 로그로 남는다")
    void fixClosingFences_logsFenceHeal() {
        String md = "```java\nint x = 1;\n## 다음 장\n본문"; // 치유된 펜스는 다음 장 헤딩(3행) 직전

        MarkdownCorrectionService.fixClosingFences(md);

        assertThat(logAppender.list).anyMatch(e ->
                e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage().contains("3행")
                        && e.getFormattedMessage().contains("치유")
                        && e.getFormattedMessage().contains("다음 장"));
    }

    @Test
    @DisplayName("normalizeCodeBlocks — SQL 오분류를 java로 교정 시 라인 번호·사유가 DEBUG 로그로 남는다")
    void normalizeCodeBlocks_logsSqlToJavaCorrection() {
        String java = "public class Foo {\n    void bar() { System.out.println(1); }\n}";
        String md = "설명\n\n```sql\n" + java + "\n```\n"; // 여는 펜스는 3행

        service.normalizeCodeBlocks(md, false);

        assertThat(logAppender.list).anyMatch(e ->
                e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage().contains("3행")
                        && e.getFormattedMessage().contains("'sql' → 'java'")
                        && e.getFormattedMessage().contains("Java 신호"));
    }

    @Test
    @DisplayName("normalizeCodeBlocks — 태그 없는 블록의 언어 추론 시 라인 번호·사유가 DEBUG 로그로 남는다")
    void normalizeCodeBlocks_logsInferredLanguage() {
        String json = "{\"a\": 1}";
        String md = "설명\n\n```\n" + json + "\n```\n"; // 여는 펜스는 3행

        service.normalizeCodeBlocks(md, true);

        assertThat(logAppender.list).anyMatch(e ->
                e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage().contains("3행")
                        && e.getFormattedMessage().contains("(없음)' → 'json'")
                        && e.getFormattedMessage().contains("추론"));
    }

    @Test
    @DisplayName("looksLikeChapterHeadingNotComment — 2~7단계 제목은 true, 그 외/배너주석은 false")
    void looksLikeChapterHeadingNotComment_heuristic() {
        assertThat(MarkdownCorrectionService.looksLikeChapterHeadingNotComment("## 제목")).isTrue();
        assertThat(MarkdownCorrectionService.looksLikeChapterHeadingNotComment("####### 레벨7")).isTrue();
        assertThat(MarkdownCorrectionService.looksLikeChapterHeadingNotComment("######## 레벨8")).isFalse();
        assertThat(MarkdownCorrectionService.looksLikeChapterHeadingNotComment("# 레벨1")).isFalse();
        assertThat(MarkdownCorrectionService.looksLikeChapterHeadingNotComment("### 주석 ###")).isFalse();
        assertThat(MarkdownCorrectionService.looksLikeChapterHeadingNotComment("### ###")).isFalse();
        assertThat(MarkdownCorrectionService.looksLikeChapterHeadingNotComment("##제목")).isFalse(); // no space
    }

    @Test
    @DisplayName("postProcessMarkdown — 남아있는 [DOCUMENT]/[/DOCUMENT] 마커 줄 제거")
    void postProcess_dropsDocumentMarkers() {
        String md = "[DOCUMENT]\n# 제목\n본문\n[/DOCUMENT]";
        String out = MarkdownCorrectionService.postProcessMarkdown(md);
        assertThat(out).doesNotContain("[DOCUMENT]").doesNotContain("[/DOCUMENT]");
        assertThat(out).contains("# 제목").contains("본문");
    }

    @Test
    @DisplayName("postProcessMarkdown — 내용 없는 '-' 줄만 제거하고 '---'(수평선)·'- 항목'·표 구분줄은 보존")
    void postProcess_dropsContentlessDashOnly() {
        String md = "본문\n-\n- 실제 항목\n---\n";
        String out = MarkdownCorrectionService.postProcessMarkdown(md);
        String[] lines = out.split("\n", -1);
        assertThat(List.of(lines)).doesNotContain("-");      // lone dash gone
        assertThat(out).contains("- 실제 항목");             // real bullet kept
        assertThat(out).contains("---");                     // thematic break kept
    }

    @Test
    @DisplayName("postProcessMarkdown — 코드 블록 앞뒤에 빈 줄을 보장한다")
    void postProcess_blankLinesAroundCodeBlock() {
        String md = "설명입니다.\n```java\nint x = 1;\n```\n다음 문단.";
        String out = MarkdownCorrectionService.postProcessMarkdown(md);
        assertThat(out).isEqualTo("설명입니다.\n\n```java\nint x = 1;\n```\n\n다음 문단.");
    }

    @Test
    @DisplayName("postProcessMarkdown — 표 앞뒤에 빈 줄을 보장한다")
    void postProcess_blankLinesAroundTable() {
        String md = "앞 문장\n| 항목 | 값 |\n|------|-----|\n| a | b |\n뒤 문장";
        String out = MarkdownCorrectionService.postProcessMarkdown(md);
        assertThat(out).isEqualTo(
                "앞 문장\n\n| 항목 | 값 |\n|------|-----|\n| a | b |\n\n뒤 문장");
    }

    @Test
    @DisplayName("postProcessMarkdown — 소제목(##~#######) 앞뒤에 빈 줄을 보장한다")
    void postProcess_blankLinesAroundHeading() {
        String md = "앞 문단\n## 소제목\n본문입니다.";
        String out = MarkdownCorrectionService.postProcessMarkdown(md);
        assertThat(out).isEqualTo("앞 문단\n\n## 소제목\n\n본문입니다.");
    }

    @Test
    @DisplayName("postProcessMarkdown — 문서 첫 줄/마지막 줄 소제목에는 불필요한 빈 줄을 넣지 않는다")
    void postProcess_headingAtDocumentEdgesNoStrayBlank() {
        assertThat(MarkdownCorrectionService.postProcessMarkdown("## 첫 소제목\n본문"))
                .isEqualTo("## 첫 소제목\n\n본문");
        assertThat(MarkdownCorrectionService.postProcessMarkdown("본문\n## 마지막 소제목"))
                .isEqualTo("본문\n\n## 마지막 소제목");
    }

    @Test
    @DisplayName("postProcessMarkdown — 연속된 소제목 사이 빈 줄은 1개만, 이미 있으면 그대로")
    void postProcess_consecutiveHeadingsSingleBlank() {
        String md = "## 상위\n### 하위\n\n본문";
        String out = MarkdownCorrectionService.postProcessMarkdown(md);
        assertThat(out).isEqualTo("## 상위\n\n### 하위\n\n본문");
    }

    @Test
    @DisplayName("postProcessMarkdown — 펜스 안의 '#' 주석/배너는 소제목이 아니므로 빈 줄을 넣지 않는다")
    void postProcess_headingRuleSkipsFenceAndBannerComments() {
        String fenced = "설명\n\n```bash\n## 설치 단계\napt install foo\n```";
        assertThat(MarkdownCorrectionService.postProcessMarkdown(fenced)).isEqualTo(fenced);

        // '### 주석 ###' 형태의 배너 주석은 헤딩으로 보지 않는다(looksLikeChapterHeadingNotComment).
        String banner = "앞 문장\n### 주석 ###\n뒤 문장";
        assertThat(MarkdownCorrectionService.postProcessMarkdown(banner)).isEqualTo(banner);
    }

    @Test
    @DisplayName("postProcessMarkdown — H1과 들여쓰기된 '##'은 소제목 빈 줄 규칙 대상이 아니다")
    void postProcess_headingRuleScopedToTopLevelH2Plus() {
        String h1 = "앞 문장\n# 문서 제목\n뒤 문장";
        assertThat(MarkdownCorrectionService.postProcessMarkdown(h1)).isEqualTo(h1);

        String indented = "- 항목\n  ## 목록 안 내용\n뒤 문장";
        assertThat(MarkdownCorrectionService.postProcessMarkdown(indented)).isEqualTo(indented);
    }

    @Test
    @DisplayName("postProcessMarkdown — 펜스 안의 '-' 한 줄/빈 줄은 코드 내용이므로 건드리지 않는다")
    void postProcess_fenceContentUntouched() {
        String md = "```\n-\n\n-\n```";
        String out = MarkdownCorrectionService.postProcessMarkdown(md);
        assertThat(out).contains("```\n-\n\n-\n```"); // 펜스 내부는 그대로
    }

    // ---------------------------------------------------------------------------------------------
    // PPTX-only shape-group formatting (postProcessMarkdown(md, isPptx=true) / applyPptxShapeFormatting)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("postProcessMarkdown — isPptx=false면 PPTX 전용 규칙이 전혀 적용되지 않는다")
    void postProcess_pptxRulesSkippedWhenNotPptx() {
        String md = "본문\n[도형 그룹]\n1\n1\n[/도형 그룹]\n다음 문단";
        String out = MarkdownCorrectionService.postProcessMarkdown(md, false);
        // 그룹 전후 빈 줄도, 중복 제거도 적용되지 않아야 함 — 원본 그대로(개행만 기존 로직대로 유지)
        assertThat(out).isEqualTo(md);
    }

    @Test
    @DisplayName("ensureBlankAroundShapeGroupMarkers — [도형 그룹] 앞뒤에 빈 줄을 보장한다")
    void ensureBlankAroundShapeGroupMarkers_addsBlankLines() {
        String md = "슬라이드 본문\n[도형 그룹]\n내용\n[/도형 그룹]\n다음 줄";
        String out = MarkdownCorrectionService.ensureBlankAroundShapeGroupMarkers(md);
        assertThat(out).isEqualTo("슬라이드 본문\n\n[도형 그룹]\n내용\n[/도형 그룹]\n\n다음 줄");
    }

    @Test
    @DisplayName("ensureBlankAroundShapeGroupMarkers — 이미 빈 줄이 있으면 중복으로 추가하지 않는다")
    void ensureBlankAroundShapeGroupMarkers_idempotent() {
        String md = "슬라이드 본문\n\n[도형 그룹]\n내용\n[/도형 그룹]\n\n다음 줄";
        String out = MarkdownCorrectionService.ensureBlankAroundShapeGroupMarkers(md);
        assertThat(out).isEqualTo(md);
    }

    @Test
    @DisplayName("ensureBlankAroundShapeGroupMarkers — 번호 붙은 [도형 그룹 2]도 동일하게 처리한다")
    void ensureBlankAroundShapeGroupMarkers_numberedLabel() {
        String md = "본문\n[도형 그룹 2]\n내용\n[/도형 그룹 2]\n뒤 문장";
        String out = MarkdownCorrectionService.ensureBlankAroundShapeGroupMarkers(md);
        assertThat(out).isEqualTo("본문\n\n[도형 그룹 2]\n내용\n[/도형 그룹 2]\n\n뒤 문장");
    }

    @Test
    @DisplayName("ensureImageAnchorBoundaryBlankLines — 그룹 안 이미지 앵커 묶음 앞뒤에 빈 줄을 넣는다")
    void ensureImageAnchorBoundaryBlankLines_wrapsAnchorRun() {
        String md = "[도형 그룹]\n[이미지: a.png]\n[이미지: b.png]\n그룹 내부 텍스트\n[/도형 그룹]";
        String out = MarkdownCorrectionService.ensureImageAnchorBoundaryBlankLines(md);
        assertThat(out).isEqualTo(
                "[도형 그룹]\n\n[이미지: a.png]\n[이미지: b.png]\n\n그룹 내부 텍스트\n[/도형 그룹]");
    }

    @Test
    @DisplayName("ensureImageAnchorBoundaryBlankLines — 그룹 밖 이미지 마커는 건드리지 않는다")
    void ensureImageAnchorBoundaryBlankLines_ignoresImagesOutsideGroup() {
        String md = "본문\n[이미지: a.png]\n뒤 문장";
        String out = MarkdownCorrectionService.ensureImageAnchorBoundaryBlankLines(md);
        assertThat(out).isEqualTo(md);
    }

    @Test
    @DisplayName("ensureBlankBetweenConsecutiveImages — 연속된 [이미지] 사이에 빈 줄을 넣는다")
    void ensureBlankBetweenConsecutiveImages_separatesAdjacentImages() {
        String md = "[이미지: a.png]\n[이미지: b.png]\n[이미지: c.png]";
        String out = MarkdownCorrectionService.ensureBlankBetweenConsecutiveImages(md);
        assertThat(out).isEqualTo("[이미지: a.png]\n\n[이미지: b.png]\n\n[이미지: c.png]");
    }

    @Test
    @DisplayName("ensureBlankBetweenConsecutiveImages — [이미지 설명]이 붙은 이미지도 한 단위로 취급해 사이에 빈 줄을 넣는다")
    void ensureBlankBetweenConsecutiveImages_treatsDescriptionAsPartOfUnit() {
        String md = "[이미지: a.png]\n[이미지 설명: 설명A]\n[이미지: b.png]\n[이미지 설명: 설명B]";
        String out = MarkdownCorrectionService.ensureBlankBetweenConsecutiveImages(md);
        assertThat(out).isEqualTo(
                "[이미지: a.png]\n[이미지 설명: 설명A]\n\n[이미지: b.png]\n[이미지 설명: 설명B]");
    }

    @Test
    @DisplayName("ensureBlankBetweenConsecutiveImages — 이미지가 하나뿐이면 아무것도 바꾸지 않는다")
    void ensureBlankBetweenConsecutiveImages_singleImageUntouched() {
        String md = "본문\n[이미지: a.png]\n다음 문장";
        String out = MarkdownCorrectionService.ensureBlankBetweenConsecutiveImages(md);
        assertThat(out).isEqualTo(md);
    }

    @Test
    @DisplayName("normalizeBulletGaps — 연속된 불릿 사이 빈 줄 1개는 제거된다")
    void normalizeBulletGaps_removesSingleBlankBetweenBullets() {
        String md = "- 항목1\n\n- 항목2";
        String out = MarkdownCorrectionService.normalizeBulletGaps(md);
        assertThat(out).isEqualTo("- 항목1\n- 항목2");
    }

    @Test
    @DisplayName("normalizeBulletGaps — 연속된 불릿 사이 빈 줄 2개 이상은 1개로 축소된다")
    void normalizeBulletGaps_collapsesMultipleBlanksToOne() {
        String md = "- 항목1\n\n\n\n- 항목2";
        String out = MarkdownCorrectionService.normalizeBulletGaps(md);
        assertThat(out).isEqualTo("- 항목1\n\n- 항목2");
    }

    @Test
    @DisplayName("normalizeBulletGaps — 불릿 뒤에 일반 본문이 이어지면 빈 줄 개수를 그대로 둔다")
    void normalizeBulletGaps_leavesNonBulletGapUntouched() {
        String md = "- 항목1\n\n일반 본문";
        String out = MarkdownCorrectionService.normalizeBulletGaps(md);
        assertThat(out).isEqualTo(md);
    }

    @Test
    @DisplayName("dedupSingleTokenLinesInShapeGroups — 그룹 안 중복된 숫자/단어 한 줄은 첫 번째만 남긴다")
    void dedupSingleTokenLinesInShapeGroups_dropsDuplicates() {
        String md = "[도형 그룹]\n1\n합계\n1\n합계\n[/도형 그룹]";
        String out = MarkdownCorrectionService.dedupSingleTokenLinesInShapeGroups(md);
        assertThat(out).isEqualTo("[도형 그룹]\n1\n합계\n[/도형 그룹]");
    }

    @Test
    @DisplayName("dedupSingleTokenLinesInShapeGroups — 중괄호가 포함된 줄은 중복이어도 제외하지 않는다")
    void dedupSingleTokenLinesInShapeGroups_keepsBraceLines() {
        String md = "[도형 그룹]\n{n}\n{n}\n[/도형 그룹]";
        String out = MarkdownCorrectionService.dedupSingleTokenLinesInShapeGroups(md);
        assertThat(out).isEqualTo(md);
    }

    @Test
    @DisplayName("dedupSingleTokenLinesInShapeGroups — 이미지 마커 줄은 중복이어도 절대 제거하지 않는다")
    void dedupSingleTokenLinesInShapeGroups_neverDropsImageMarkers() {
        String md = "[도형 그룹]\n[이미지: a.png]\n[이미지: a.png]\n[/도형 그룹]";
        String out = MarkdownCorrectionService.dedupSingleTokenLinesInShapeGroups(md);
        assertThat(out).isEqualTo(md);
    }

    @Test
    @DisplayName("dedupSingleTokenLinesInShapeGroups — 그룹 밖의 동일한 중복 줄은 건드리지 않는다")
    void dedupSingleTokenLinesInShapeGroups_ignoresOutsideGroup() {
        String md = "합계\n합계\n[도형 그룹]\n내용\n[/도형 그룹]";
        String out = MarkdownCorrectionService.dedupSingleTokenLinesInShapeGroups(md);
        assertThat(out).isEqualTo(md);
    }

    @Test
    @DisplayName("applyPptxShapeFormatting — 다섯 규칙이 순서대로 조합되어 하나의 그룹 블록에 함께 적용된다")
    void applyPptxShapeFormatting_combinesAllRules() {
        String md = "슬라이드 본문\n"
                + "[도형 그룹]\n"
                + "[이미지: a.png]\n"
                + "[이미지: b.png]\n"
                + "1\n"
                + "합계\n"
                + "1\n"
                + "- 항목1\n"
                + "\n"
                + "- 항목2\n"
                + "[/도형 그룹]\n"
                + "다음 문단";
        String out = MarkdownCorrectionService.applyPptxShapeFormatting(md);
        assertThat(out).isEqualTo(
                "슬라이드 본문\n\n"
                        + "[도형 그룹]\n\n"
                        + "[이미지: a.png]\n\n"
                        + "[이미지: b.png]\n\n"
                        + "1\n"
                        + "합계\n"
                        + "- 항목1\n"
                        + "- 항목2\n"
                        + "[/도형 그룹]\n\n"
                        + "다음 문단");
    }

    @Test
    @DisplayName("postProcessMarkdown — isPptx=true면 PPTX 전용 규칙이 일반 정리보다 먼저 적용된다")
    void postProcess_appliesPptxRulesWhenPptx() {
        String md = "본문\n[도형 그룹]\n1\n1\n[/도형 그룹]\n다음 문단";
        String out = MarkdownCorrectionService.postProcessMarkdown(md, true);
        assertThat(out).isEqualTo("본문\n\n[도형 그룹]\n1\n[/도형 그룹]\n\n다음 문단");
    }

    @Test
    @DisplayName("looksLikeTableRow — 표 행(선두 파이프/2개 이상)만 true, 일반 산문은 false")
    void looksLikeTableRow_heuristic() {
        assertThat(MarkdownCorrectionService.looksLikeTableRow("| a | [이미지: x] | b |")).isTrue();
        assertThat(MarkdownCorrectionService.looksLikeTableRow("a | b")).isFalse();      // 산문 속 파이프 1개
        assertThat(MarkdownCorrectionService.looksLikeTableRow("그냥 문장입니다.")).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // 코드 언어 추론 — Java를 SQL로 오분류하지 않도록 (inferCodeLanguage / resolveCodeLanguage)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("inferCodeLanguage — select/delete/update 메서드 호출이 든 Java는 SQL이 아니라 java로 판단")
    void infer_javaMethodCalls_notSql() {
        String java = "public void run() {\n"
                + "    repository.delete(entity);\n"
                + "    var rows = jdbc.select(sql);\n"
                + "    service.update(dto);\n"
                + "}";
        assertThat(service.inferCodeLanguage(java)).isEqualTo("java");
    }

    @Test
    @DisplayName("inferCodeLanguage — 실제 SQL 문(SELECT ... FROM, DELETE FROM)은 sql로 판단")
    void infer_realSql_isSql() {
        assertThat(service.inferCodeLanguage("SELECT id, name\nFROM users\nWHERE age > 20;")).isEqualTo("sql");
        assertThat(service.inferCodeLanguage("DELETE FROM orders WHERE status = 'X';")).isEqualTo("sql");
        assertThat(service.inferCodeLanguage("UPDATE users SET name = 'a' WHERE id = 1;")).isEqualTo("sql");
    }

    @Test
    @DisplayName("resolveCodeLanguage — 잘못 붙은 ```sql 태그가 Java 코드면 java로 교정")
    void resolve_fixesMistaggedSqlOnJava() {
        String java = "@Override\npublic int deleteById(Long id) {\n    return repository.delete(id);\n}";
        assertThat(service.resolveCodeLanguage("sql", java, false)).isEqualTo("java");
    }

    @Test
    @DisplayName("resolveCodeLanguage — 실제 SQL에 붙은 ```sql 태그는 그대로 유지")
    void resolve_keepsCorrectSqlTag() {
        String sql = "SELECT * FROM t WHERE x = 1;";
        assertThat(service.resolveCodeLanguage("sql", sql, false)).isEqualTo("sql");
    }

    @Test
    @DisplayName("resolveCodeLanguage — 이미 java 등 다른 태그가 있으면 건드리지 않는다")
    void resolve_keepsExistingNonSqlTag() {
        assertThat(service.resolveCodeLanguage("python", "print('x')", false)).isEqualTo("python");
        assertThat(service.resolveCodeLanguage("java", "class Foo {}", false)).isEqualTo("java");
    }
}
