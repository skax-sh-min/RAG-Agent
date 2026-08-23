package com.example.ragagent.service;

import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요약 전용 안전망의 동작 계약 (PLAN §6.24 Step 1-c).
 *
 * <p>핵심은 "발동하지 않는 경우"다 — 예전 구현은 규약을 지킨 답변까지 매번 다시 써서 문단 구조를
 * 바꾸고 스트리밍 화면과 저장본을 갈라놓았다.
 */
class SummaryOnlyGuardTest {

    @Test
    @DisplayName("N 모드는 어떤 답변이든 손대지 않는다")
    void standardModeIsUntouched() {
        String answer = "## 요약\n요약\n\n## 상세 설명\n본문";
        assertThat(SummaryOnlyGuard.apply(answer, ResponseMode.N)).isSameAs(answer);
    }

    @Test
    @DisplayName("규약을 지킨 S 답변은 한 글자도 바뀌지 않는다(빈 줄·목록 구조 보존)")
    void compliantSummaryIsReturnedUnchanged() {
        String answer = """
                ## 요약
                핵심은 세 가지다.

                - 첫째 항목
                - 둘째 항목

                마지막 문장.""";
        // 예전 구현은 여기서 빈 줄을 전부 지우고 7줄로 잘라 목록과 문단이 붙어버렸다.
        assertThat(SummaryOnlyGuard.apply(answer, ResponseMode.S)).isSameAs(answer);
    }

    @Test
    @DisplayName("헤딩이 없으면 내용은 그대로 두고 '## 요약'만 앞에 붙인다")
    void headinglessAnswerGetsTheSummaryHeadingOnly() {
        String result = SummaryOnlyGuard.apply("본문만 있는 답변\n\n둘째 문단", ResponseMode.S);

        assertThat(result).isEqualTo("## 요약\n본문만 있는 답변\n\n둘째 문단");
    }

    @Test
    @DisplayName("추가 섹션이 있으면 첫 섹션만 남기고 나머지를 버린다")
    void extraSectionsAreDropped() {
        String answer = """
                ## 요약
                한 줄 요약.

                본문 이어짐.

                ## 상세 설명
                버려질 내용

                ## 참고
                - [파일.md] (섹션)""";

        String result = SummaryOnlyGuard.apply(answer, ResponseMode.S);

        assertThat(result).isEqualTo("## 요약\n한 줄 요약.\n\n본문 이어짐.");
        assertThat(result).doesNotContain("상세 설명", "참고", "버려질 내용");
    }

    @Test
    @DisplayName("첫 섹션 본문은 잘리지 않는다 — 길어도 글자 수/줄 수로 자르지 않는다")
    void firstSectionBodyIsNeverTruncated() {
        StringBuilder sb = new StringBuilder("## 요약\n");
        for (int i = 1; i <= 20; i++) sb.append("항목 ").append(i).append('\n');
        String answer = sb.toString().strip();

        // 규약(4~7줄)을 넘겼지만 섹션은 하나뿐 → 분량은 프롬프트의 몫이므로 가드는 개입하지 않는다.
        assertThat(SummaryOnlyGuard.apply(answer, ResponseMode.S)).isSameAs(answer);
    }

    @Test
    @DisplayName("코드 펜스 안의 '# 주석'은 헤딩으로 세지 않는다")
    void hashesInsideCodeFencesAreNotHeadings() {
        String answer = """
                ## 요약
                설정은 아래와 같다.

                ```bash
                # 주석 한 줄
                ## 또 다른 주석
                export PORT=8080
                ```""";

        assertThat(SummaryOnlyGuard.apply(answer, ResponseMode.S)).isSameAs(answer);
    }

    @Test
    @DisplayName("null/공백은 그대로 통과한다")
    void nullAndBlankPassThrough() {
        assertThat(SummaryOnlyGuard.apply(null, ResponseMode.S)).isNull();
        assertThat(SummaryOnlyGuard.apply("   ", ResponseMode.S)).isEqualTo("   ");
    }
}
