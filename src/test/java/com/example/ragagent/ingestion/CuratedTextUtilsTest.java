package com.example.ragagent.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — CuratedTextUtils (§10.10 in PLAN.md)
 *
 * Covers:
 *  - "## 참고" 섹션(마지막 섹션) 제거, 앞부분 내용 보존
 *  - "#" 없는 bare "참고" 제목도 제거
 *  - 문장 속 "참고"라는 단어는 오탐 없이 보존
 *  - 섹션이 없으면 원문 그대로(strip만 적용)
 *  - null 입력 → 빈 문자열
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
}
