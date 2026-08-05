package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionReuseServiceTest {

    @Test
    @DisplayName("지시어만 있는 질문은 추천 제외 대상이다")
    void directiveOnlyQuestion_excluded() {
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("이거 어떻게 해?"))
                .isTrue();
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("그거 알려줘"))
                .isTrue();
    }

    @Test
    @DisplayName("지시어가 있어도 구체 신호가 있으면 제외하지 않는다")
    void directiveWithConcreteSignal_notExcluded() {
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("이거 오류코드 404는 뭐야?"))
                .isFalse();
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("그거 application.properties 설정값 알려줘"))
                .isFalse();
    }

    @Test
    @DisplayName("지시어 없는 일반 질문은 추천 대상이다")
    void normalQuestion_notExcluded() {
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("Spring Boot에서 sqlite 연결 방법"))
                .isFalse();
    }
}
