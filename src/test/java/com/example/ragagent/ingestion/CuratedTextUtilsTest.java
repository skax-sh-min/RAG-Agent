package com.example.ragagent.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — CuratedTextUtils (§10.10 in PLAN.md)
 *
 * Covers:
 *  - "## 참고" 섹션(마지막 섹션) 제거, 앞부분 내용 보존
 *  - "#" 없는 bare "참고" 제목도 제거, "#"가 있는 헤딩 변형 문구(참고자료/참고 사항 등)도 제거
 *  - 문장 속 "참고"라는 단어는 오탐 없이 보존
 *  - 섹션이 없으면 원문 그대로(strip만 적용)
 *  - null 입력 → 빈 문자열
 *  - stripSummarySection: "## 요약" 섹션(다음 헤딩 전까지) 제거, 헤딩 없으면 원문 그대로,
 *    요약이 마지막 내용이면 끝까지 제거, 헤딩 변형 문구도 인식
 *  - stripStructuralSections: 요약+참고를 함께 제거(stripSummarySection∘stripReferenceSection),
 *    CuratedQaService의 기본 임베딩 텍스트와 동일한 파이프라인
 *  - extractCoreSections: 상세 설명~설정/주의사항만 남기고 요약/참고 제거, 강조 마커 제거,
 *    "## 상세 설명" 헤딩 변형 문구도 인식, 헤딩이 없는 답변(Direct 모드 등)은 빈 문자열
 */
class CuratedTextUtilsTest {

    @Test
    @DisplayName("## 참고 섹션(마지막)을 제거하고 앞부분은 보존한다")
    void stripsTrailingReferenceSection() {
        String answer = """
                ## 요약
                배치는 온라인과 다르게 동작합니다.

                ## 상세 설명
                자세한 설명입니다.

                ## 참고
                 - [NEXCORE_Fwk_9.0-개발가이드_배치_-v1.0-20240307.part.docx | p.1] (2.1.2 온라인 vs 배치 비교)""";

        String result = CuratedTextUtils.stripReferenceSection(answer);

        assertThat(result).contains("## 요약", "## 상세 설명", "자세한 설명입니다.");
        assertThat(result).doesNotContain("참고", "NEXCORE_Fwk_9.0");
    }

    @Test
    @DisplayName("# 없는 bare '참고' 제목도 제거한다")
    void stripsBareReferenceHeading() {
        String answer = "본문 내용입니다.\n\n참고\n - [파일.docx | p.1] (섹션)";

        String result = CuratedTextUtils.stripReferenceSection(answer);

        assertThat(result).isEqualTo("본문 내용입니다.");
    }

    @Test
    @DisplayName("문장 속 '참고'라는 단어는 제거하지 않는다 (줄 전체가 '참고'여야 매칭)")
    void doesNotStripInlineMentionOfWord() {
        String answer = "이 내용을 참고하세요. 추가 설명이 이어집니다.";

        String result = CuratedTextUtils.stripReferenceSection(answer);

        assertThat(result).isEqualTo(answer);
    }

    @Test
    @DisplayName("참고 섹션이 없으면 원문을 그대로 반환한다(strip만 적용)")
    void returnsUnchangedWhenNoReferenceSection() {
        String answer = "  단순 답변 텍스트입니다.  ";

        assertThat(CuratedTextUtils.stripReferenceSection(answer)).isEqualTo("단순 답변 텍스트입니다.");
    }

    @Test
    @DisplayName("null 입력 → 빈 문자열")
    void nullInputReturnsEmpty() {
        assertThat(CuratedTextUtils.stripReferenceSection(null)).isEmpty();
    }

    @Test
    @DisplayName("'#'가 있으면 '참고' 헤딩의 변형 문구도 제거한다 (참고자료/참고 사항 등)")
    void stripsReferenceHeadingVariants() {
        assertThat(CuratedTextUtils.stripReferenceSection("본문.\n\n## 참고자료\n- [파일.docx | p.1]"))
                .isEqualTo("본문.");
        assertThat(CuratedTextUtils.stripReferenceSection("본문.\n\n### 참고 사항\n- [파일.docx | p.1]"))
                .isEqualTo("본문.");
    }

    @Test
    @DisplayName("stripSummarySection — '## 요약' 섹션을 제거하고 다음 헤딩부터는 보존한다")
    void stripSummarySection_removesSummary_keepsRest() {
        String answer = """
                ## 요약
                핵심 한 줄 요약.

                ## 상세 설명
                자세한 설명입니다.

                ## 참고
                 - [파일.docx | p.1]""";

        String result = CuratedTextUtils.stripSummarySection(answer);

        assertThat(result).doesNotContain("## 요약", "핵심 한 줄 요약");
        assertThat(result).startsWith("## 상세 설명");
        assertThat(result).contains("자세한 설명입니다.", "## 참고", "파일.docx");
    }

    @Test
    @DisplayName("stripSummarySection — '## 요약' 헤딩이 없으면 원문을 그대로 반환한다(strip만 적용)")
    void stripSummarySection_noSummaryHeading_returnsUnchanged() {
        String answer = "  안녕하세요! 무엇을 도와드릴까요?  ";

        assertThat(CuratedTextUtils.stripSummarySection(answer)).isEqualTo("안녕하세요! 무엇을 도와드릴까요?");
    }

    @Test
    @DisplayName("stripSummarySection — 요약이 마지막 내용이면(다음 헤딩 없음) 끝까지 제거한다")
    void stripSummarySection_summaryIsOnlyContent_removesToEnd() {
        String answer = "## 요약\n핵심 한 줄 요약.";

        assertThat(CuratedTextUtils.stripSummarySection(answer)).isEmpty();
    }

    @Test
    @DisplayName("stripSummarySection — 헤딩 변형 문구도 제거한다 ('## 요약 및 결론' 등)")
    void stripSummarySection_headingVariant() {
        String answer = "## 요약 및 결론\n핵심 요약.\n\n## 상세 설명\n자세한 설명.";

        String result = CuratedTextUtils.stripSummarySection(answer);

        assertThat(result).doesNotContain("요약");
        assertThat(result).startsWith("## 상세 설명");
    }

    @Test
    @DisplayName("stripSummarySection — null 입력 → 빈 문자열")
    void stripSummarySection_nullInput_returnsEmpty() {
        assertThat(CuratedTextUtils.stripSummarySection(null)).isEmpty();
    }

    @Test
    @DisplayName("stripStructuralSections — 요약과 참고를 함께 제거하고 나머지는 보존한다")
    void stripStructuralSections_removesSummaryAndReference() {
        String answer = """
                ## 요약
                핵심 한 줄 요약.

                ## 상세 설명
                자세한 설명입니다.

                ## 참고
                 - [파일.docx | p.1]""";

        String result = CuratedTextUtils.stripStructuralSections(answer);

        assertThat(result).startsWith("## 상세 설명");
        assertThat(result).contains("자세한 설명입니다.");
        assertThat(result).doesNotContain("## 요약", "핵심 한 줄 요약", "## 참고", "파일.docx");
    }

    @Test
    @DisplayName("stripStructuralSections — 두 헤딩 모두 없는 답변(Direct 모드 등)은 원문 그대로 반환한다")
    void stripStructuralSections_noHeadings_returnsUnchanged() {
        String answer = "안녕하세요! 무엇을 도와드릴까요?";

        assertThat(CuratedTextUtils.stripStructuralSections(answer)).isEqualTo(answer);
    }

    @Test
    @DisplayName("extractCoreSections — '## 상세 설명' 헤딩의 변형 문구도 인식한다 ('상세 설명 및 배경' 등)")
    void extractCoreSections_detailHeadingVariant() {
        String answer = "## 요약\n요약.\n\n## 상세 설명 및 배경\n자세한 설명입니다.";

        String result = CuratedTextUtils.extractCoreSections(answer);

        assertThat(result).startsWith("## 상세 설명 및 배경");
        assertThat(result).contains("자세한 설명입니다.");
        assertThat(result).doesNotContain("## 요약");
    }

    @Test
    @DisplayName("extractCoreSections — 상세 설명부터 끝까지(참고 제외)만 남기고 요약은 버린다")
    void extractCoreSections_keepsDetailOnward_dropsSummaryAndReferences() {
        String answer = """
                ## 요약
                핵심 한 줄 요약.

                ## 상세 설명
                자세한 설명입니다.

                ## 예시/코드
                ```java
                System.out.println("hi");
                ```

                ## 설정/주의사항
                주의할 점입니다.

                ## 참고
                 - [파일.docx | p.1] (섹션)""";

        String result = CuratedTextUtils.extractCoreSections(answer);

        assertThat(result).startsWith("## 상세 설명");
        assertThat(result).contains("자세한 설명입니다.", "## 예시/코드", "System.out.println",
                "## 설정/주의사항", "주의할 점입니다.");
        assertThat(result).doesNotContain("## 요약", "핵심 한 줄 요약", "## 참고", "파일.docx");
    }

    @Test
    @DisplayName("extractCoreSections — '**'/'__' 강조 마커를 제거하되 내부 텍스트는 보존한다")
    void extractCoreSections_stripsEmphasisMarkers() {
        String answer = """
                ## 요약
                요약.

                ## 상세 설명
                **중요한** 내용과 __강조된__ 문구가 있습니다.""";

        String result = CuratedTextUtils.extractCoreSections(answer);

        assertThat(result).contains("중요한 내용과 강조된 문구가 있습니다.");
        assertThat(result).doesNotContain("**", "__");
    }

    @Test
    @DisplayName("extractCoreSections — '## 상세 설명' 헤딩이 없으면 빈 문자열 (Direct 모드 답변 등)")
    void extractCoreSections_noDetailHeading_returnsEmpty() {
        String answer = "안녕하세요! 무엇을 도와드릴까요?";

        assertThat(CuratedTextUtils.extractCoreSections(answer)).isEmpty();
    }

    @Test
    @DisplayName("extractCoreSections — null 입력 → 빈 문자열")
    void extractCoreSections_nullInput_returnsEmpty() {
        assertThat(CuratedTextUtils.extractCoreSections(null)).isEmpty();
    }
}
