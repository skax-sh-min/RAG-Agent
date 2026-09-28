package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * § 차단하지 않을 실패를 가르는 판정 — {@code LlmRouter} 의 마커 목록을 고정한다.
 *
 * <p><b>이 테스트가 존재하는 이유.</b> 세 판정은 전부 <b>서버가 만든 영어 메시지의 부분 문자열</b>로
 * 이뤄져 있다. 이 앱이 통제하지 않는 문자열이라, llama.cpp / LM Studio 가 버전이 오르며 문구를 바꾸면
 * 그 실패는 조용히 "차단해야 할 실패"로 재분류된다. 그리고 그때 나타나는 증상이 정확히 이 판정들이
 * 막으려고 만들어진 사고다:
 * <ul>
 *   <li>컨텍스트 초과가 30초 차단을 부르고, 그 창 안의 <b>작아서 통과했을</b> 요청까지
 *       {@code "All providers exhausted"} 로 함께 죽는다.</li>
 *   <li>서버 재시작이 "재시작했는데도 계속 안 된다"가 된다 — 차단이 서버가 올라온 뒤까지 남는다.</li>
 *   <li>mmproj 없는 모델의 이미지 요청 하나가 같은 프로바이더의 무관한 TEXT 작업까지 멈춘다.</li>
 * </ul>
 * 실패하는 테스트도, "재분류했다"고 말해 주는 신호도 없이 조용히 되돌아간다. 그래서 외부 모양에
 * 의존하는 다른 규칙들과 같은 방식으로 못박는다({@code AnswerEvalPromptTest} 가 실제 프롬프트 번들을,
 * {@code QuestionKeywordsTest} 가 접미사 목록을 고정하는 것과 같다).
 *
 * <p>여기가 깨지면 고칠 곳은 {@code LlmRouter} 의 마커 목록이며, <b>지우지 말고 더할 것</b> —
 * 옛 버전의 서버가 아직 돌고 있을 수 있다.
 */
class LlmFailureClassificationTest {

    private static Throwable wrapped(String message) {
        // 실제로는 NonTransientAiException 안에 감싸여 오므로 원인 사슬을 타야 한다.
        return new RuntimeException("call failed", new IllegalStateException(message));
    }

    @Nested
    @DisplayName("컨텍스트 초과 — 차단하면 안 된다(결정적 오류라 기다려도 똑같이 실패한다)")
    class ContextOverflow {

        @ParameterizedTest
        @ValueSource(strings = {
                "the context size has been exceeded",
                "context_length_exceeded",
                "This model's maximum context length is 8192 tokens",
                "the request exceeds the available context size",
                "input will exceed context size",
                "Prompt is too long: 12000 tokens > 8192"
        })
        @DisplayName("알려진 마커가 들어 있으면 컨텍스트 초과로 본다")
        void knownMarkersClassify(String message) {
            assertThat(LlmRouter.isContextOverflow(new RuntimeException(message))).isTrue();
        }

        @Test
        @DisplayName("대소문자 무관 + 원인 사슬을 타고 내려간다")
        void caseInsensitiveAndNested() {
            assertThat(LlmRouter.isContextOverflow(wrapped("CONTEXT_LENGTH_EXCEEDED"))).isTrue();
            assertThat(LlmRouter.isContextOverflow(
                    new RuntimeException("a", new RuntimeException("b", new RuntimeException("prompt is too long")))))
                    .isTrue();
        }

        @Test
        @DisplayName("평범한 장애는 컨텍스트 초과가 아니다 — 이쪽은 차단해야 한다")
        void ordinaryFailuresAreNot() {
            assertThat(LlmRouter.isContextOverflow(new RuntimeException("Connection refused"))).isFalse();
            assertThat(LlmRouter.isContextOverflow(new RuntimeException("500 Internal Server Error"))).isFalse();
            assertThat(LlmRouter.isContextOverflow(new RuntimeException(null, null))).isFalse();
            assertThat(LlmRouter.isContextOverflow(new RuntimeException())).isFalse();
        }
    }

    @Nested
    @DisplayName("서버가 in-flight 요청을 끊음 — 차단하면 서버가 올라온 뒤까지 남는다")
    class RequestTerminated {

        @ParameterizedTest
        @ValueSource(strings = {
                "400 - {\"error\":\"terminated\"}",
                "400 - {\"error\": \"terminated\"}"
        })
        @DisplayName("응답 본문의 JSON 조각으로 판정한다")
        void jsonMarkersClassify(String message) {
            assertThat(LlmRouter.isRequestTerminatedByServer(new RuntimeException(message))).isTrue();
            assertThat(LlmRouter.isRequestTerminatedByServer(wrapped(message))).isTrue();
        }

        @Test
        @DisplayName("'terminated' 한 단어로는 판정하지 않는다 — 진짜 네트워크 장애는 차단해야 한다")
        void bareWordIsNotEnough() {
            // 마커를 JSON 조각으로 둔 이유가 이것이다. 이 줄이 true 로 바뀌면 진짜 연결 장애가
            // 차단을 건너뛰어, 죽은 서버에 매 요청이 연결 타임아웃을 각자 물게 된다.
            assertThat(LlmRouter.isRequestTerminatedByServer(
                    new RuntimeException("connection terminated by peer"))).isFalse();
            assertThat(LlmRouter.isRequestTerminatedByServer(
                    new RuntimeException("stream terminated unexpectedly"))).isFalse();
        }
    }

    @Nested
    @DisplayName("이미지 입력 미지원(mmproj) — 차단 대신 기억해서 이미지 작업만 건너뛴다")
    class VisionUnsupported {

        @ParameterizedTest
        @ValueSource(strings = {
                "image input is not supported by this model",
                "failed to load mmproj",
                "MMPROJ file is required for vision"
        })
        @DisplayName("알려진 마커가 들어 있으면 이미지 미지원으로 본다")
        void knownMarkersClassify(String message) {
            assertThat(LlmRouter.isVisionUnsupported(new RuntimeException(message))).isTrue();
            assertThat(LlmRouter.isVisionUnsupported(wrapped(message))).isTrue();
        }

        @Test
        @DisplayName("이미지와 무관한 실패는 아니다")
        void unrelatedFailuresAreNot() {
            assertThat(LlmRouter.isVisionUnsupported(new RuntimeException("image generation failed"))).isFalse();
            assertThat(LlmRouter.isVisionUnsupported(new RuntimeException("Connection refused"))).isFalse();
        }
    }

    @Test
    @DisplayName("세 판정은 서로 배타적이다 — 한 메시지가 둘로 읽히면 분기 순서가 결과를 정한다")
    void theThreeClassifiersDoNotOverlap() {
        String[] overflow = {"context_length_exceeded", "prompt is too long"};
        String[] terminated = {"400 - {\"error\":\"terminated\"}"};
        String[] vision = {"image input is not supported", "mmproj"};

        for (String m : overflow) {
            assertThat(LlmRouter.isRequestTerminatedByServer(new RuntimeException(m))).isFalse();
            assertThat(LlmRouter.isVisionUnsupported(new RuntimeException(m))).isFalse();
        }
        for (String m : terminated) {
            assertThat(LlmRouter.isContextOverflow(new RuntimeException(m))).isFalse();
            assertThat(LlmRouter.isVisionUnsupported(new RuntimeException(m))).isFalse();
        }
        for (String m : vision) {
            assertThat(LlmRouter.isContextOverflow(new RuntimeException(m))).isFalse();
            assertThat(LlmRouter.isRequestTerminatedByServer(new RuntimeException(m))).isFalse();
        }
    }
}
