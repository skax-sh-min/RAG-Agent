package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    @BeforeEach
    void setUp() {
        LlmRouter llmRouter = mock(LlmRouter.class);
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
