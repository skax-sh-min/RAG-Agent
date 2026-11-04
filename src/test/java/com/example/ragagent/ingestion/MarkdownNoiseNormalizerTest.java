package com.example.ragagent.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for MarkdownNoiseNormalizer (§10.1-보완).
 */
class MarkdownNoiseNormalizerTest {

    @Test
    @DisplayName("장식줄(반복 기호)은 완전히 제거된다")
    void normalize_removesDecorativeLines() {
        String text = "본문 첫 줄\n----------\n본문 둘째 줄\n======\n###########\n본문 셋째 줄";

        String result = MarkdownNoiseNormalizer.normalize(text);

        assertThat(result).doesNotContain("----------").doesNotContain("======").doesNotContain("###########");
        assertThat(result).contains("본문 첫 줄", "본문 둘째 줄", "본문 셋째 줄");
    }

    @Test
    @DisplayName("표 구분행(|---|---|)은 장식줄로 오인되어 제거되지 않는다")
    void normalize_preservesTableSeparatorRow() {
        String table = "| 이름 | 값 |\n|---|---|\n| a | 1 |";

        String result = MarkdownNoiseNormalizer.normalize(table);

        assertThat(result).isEqualTo(table);
    }

    @Test
    @DisplayName("코드펜스 내부는 장식처럼 보이는 줄이 있어도 무변경이다")
    void normalize_leavesCodeFenceContentUntouched() {
        String text = "설명\n```\n------\n**bold in code**\n```\n뒷내용";

        String result = MarkdownNoiseNormalizer.normalize(text);

        assertThat(result).contains("```\n------\n**bold in code**\n```");
    }

    @Test
    @DisplayName("헤딩 라인은 실제 텍스트가 있으므로 무변경이다")
    void normalize_leavesHeadingLineUntouched() {
        String text = "## 설정 방법\n본문";

        String result = MarkdownNoiseNormalizer.normalize(text);

        assertThat(result).contains("## 설정 방법");
    }

    @Test
    @DisplayName("볼드/이탤릭/밑줄 마커는 제거되고 안쪽 텍스트는 보존된다")
    void normalize_stripsEmphasisMarkersKeepingInnerText() {
        assertThat(MarkdownNoiseNormalizer.normalize("**중요**한 내용")).isEqualTo("중요한 내용");
        assertThat(MarkdownNoiseNormalizer.normalize("*기울임* 텍스트")).isEqualTo("기울임 텍스트");
        assertThat(MarkdownNoiseNormalizer.normalize("<u>밑줄</u> 텍스트")).isEqualTo("밑줄 텍스트");
    }

    @Test
    @DisplayName("리스트 마커는 보존되고, 항목 내부의 강조만 제거된다")
    void normalize_preservesListMarkerWhileStrippingInnerEmphasis() {
        String result = MarkdownNoiseNormalizer.normalize("* 이것은 *중요*합니다");

        assertThat(result).isEqualTo("* 이것은 중요합니다");
    }

    @Test
    @DisplayName("혼합 케이스 — 장식줄 제거 + 강조 제거 + 표/코드/헤딩 보존이 한 문서에서 동시에 동작한다")
    void normalize_mixedContentIntegration() {
        String text = """
                ## 제목
                ======
                **굵은** 설명과 *기울임* 텍스트.

                | 열1 | 열2 |
                |---|---|
                | a | b |

                ```
                ----
                코드 그대로
                ```
                """;

        String result = MarkdownNoiseNormalizer.normalize(text);

        assertThat(result).contains("## 제목", "굵은 설명과 기울임 텍스트.", "|---|---|", "----\n코드 그대로");
        assertThat(result).doesNotContain("======", "**굵은**", "*기울임*");
    }

    @Test
    @DisplayName("null/빈 입력은 빈 문자열을 반환한다")
    void normalize_nullOrEmpty_returnsEmptyString() {
        assertThat(MarkdownNoiseNormalizer.normalize(null)).isEmpty();
        assertThat(MarkdownNoiseNormalizer.normalize("")).isEmpty();
    }

    @Test
    @DisplayName("장식/강조가 없는 반복 문자 텍스트는 변경 없이 그대로 반환된다")
    void normalize_plainRepeatedLetterText_isNoOp() {
        String text = "A".repeat(220);

        assertThat(MarkdownNoiseNormalizer.normalize(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("연속 개행 3개 이상은 2개로 축소된다")
    void normalize_collapsesExcessiveBlankLines() {
        String text = "첫 줄\n\n\n\n둘째 줄";

        String result = MarkdownNoiseNormalizer.normalize(text);

        assertThat(result).isEqualTo("첫 줄\n\n둘째 줄");
    }

    // ── normalizeForPrompt — LLM 프롬프트 전용 (임베딩/FTS 입력은 normalize 그대로) ──────────

    @Test
    @DisplayName("프롬프트용: 이미지 마커의 경로는 빠지고 마커와 바로 뒤의 설명 줄은 남는다")
    void forPrompt_stripsImagePathButKeepsMarkerAndDescription() {
        String text = "본문\n[이미지: images/af60c92d802a89aa/s4_img1.png]\n[이미지 설명: 배포 구성도]\n끝";

        assertThat(MarkdownNoiseNormalizer.normalizeForPrompt(text))
                .isEqualTo("본문\n[이미지]\n[이미지 설명: 배포 구성도]\n끝");
    }

    @Test
    @DisplayName("프롬프트용: 표 셀 안의 이미지 마커도 같은 줄에서 경로만 빠진다")
    void forPrompt_stripsImagePathInsideTableCell() {
        String text = "| 구분 | 그림 |\n|---|---|\n| A | [이미지: images/x/a.png] |";

        assertThat(MarkdownNoiseNormalizer.normalizeForPrompt(text))
                .isEqualTo("| 구분 | 그림 |\n|---|---|\n| A | [이미지] |");
    }

    @Test
    @DisplayName("프롬프트용: [페이지: N] 만 있는 줄은 사라지고, 앞뒤 빈 줄은 하나로 접힌다")
    void forPrompt_dropsPageMarkerLines() {
        String text = "첫 문단\n\n[페이지: 12]\n\n둘째 문단";

        assertThat(MarkdownNoiseNormalizer.normalizeForPrompt(text)).isEqualTo("첫 문단\n\n둘째 문단");
    }

    @Test
    @DisplayName("프롬프트용: 문장 속 [페이지: N] 언급은 줄 전체가 마커가 아니므로 남는다")
    void forPrompt_keepsPageMentionInsideSentence() {
        String text = "자세한 내용은 [페이지: 3] 을 참고";

        assertThat(MarkdownNoiseNormalizer.normalizeForPrompt(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("프롬프트용: 코드 펜스 안의 마커 문자열은 건드리지 않는다")
    void forPrompt_leavesFenceInteriorAlone() {
        String text = "```\n[이미지: images/x/a.png]\n[페이지: 3]\n```";

        assertThat(MarkdownNoiseNormalizer.normalizeForPrompt(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("normalize 는 그대로다 — 임베딩/FTS 입력과 청크 해시가 바뀌면 안 된다")
    void normalize_stillKeepsImagePathAndPageMarker() {
        String text = "[페이지: 2]\n[이미지: images/x/a.png]";

        assertThat(MarkdownNoiseNormalizer.normalize(text)).isEqualTo(text);
    }
}
