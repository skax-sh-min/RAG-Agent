package com.example.ragagent.golden;

import com.example.ragagent.model.ChatResponse;

import java.util.Arrays;
import java.util.List;

/**
 * ChatResponse 의 외부 계약을 "구조" 단위로 캡처.
 *
 * 응답 내용(자연어)이 아니라 형태(섹션, source 수, 토큰 누적 여부 등)만 비교 →
 * 프롬프트/모델 변경에 강건한 회귀 감시.
 */
public record AnswerShape(
        boolean hasAnswer,
        List<String> sectionsInAnswer,
        int sourcesCount,
        int imageRefsCount,
        boolean tokensRecorded,
        int llmCallCount,
        String questionType,
        boolean premiumUpgraded,
        boolean dualPresent
) {
    public static AnswerShape of(ChatResponse r) {
        return new AnswerShape(
                r.answer() != null && !r.answer().isBlank(),
                extractSections(r.answer()),
                r.sources() == null ? 0 : r.sources().size(),
                r.imageRefs() == null ? 0 : r.imageRefs().size(),
                r.totalInputTokens() > 0,
                r.llmCallCount(),
                r.questionType(),
                r.premiumUpgraded() != null,
                r.dualLocalAnswer() != null);
    }

    /** "## 요약", "## 상세 설명" 등 H2 헤더만 추출. */
    private static List<String> extractSections(String md) {
        if (md == null || md.isBlank()) return List.of();
        return Arrays.stream(md.split("\\R"))
                .filter(line -> line.startsWith("## "))
                .map(line -> line.substring(3).trim())
                .toList();
    }
}
