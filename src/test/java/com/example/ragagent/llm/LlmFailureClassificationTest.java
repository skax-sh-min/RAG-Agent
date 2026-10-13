package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;

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

        /**
         * 채팅 답변 스트리밍(WebClient)의 모양 — 메시지는 {@code "400 Bad Request from POST …"} 뿐이고 서버의 문장은
         * 응답 본문에만 있다. 본문은 2026-10-02 llama.cpp(b10236)가 스트리밍 요청의 초과에 실제로 돌려준 그대로다.
         * 메시지만 보던 동안 이 모양이 거짓이었고, 그래서 스트리밍 경로의 축소 재시도가 한 번도 발동하지 않았다.
         */
        @Test
        @DisplayName("WebClient 오류는 메시지에 본문이 없다 — 응답 본문까지 읽어야 알아본다(스트리밍 경로의 실제 모양)")
        void webClientErrorBodyIsRead() {
            var e = org.springframework.web.reactive.function.client.WebClientResponseException.create(
                    400, "Bad Request", new org.springframework.http.HttpHeaders(),
                    ("{\"error\":{\"code\":400,\"message\":\"request (40016 tokens) exceeds the available context size"
                            + " (32768 tokens), try increasing it\",\"type\":\"exceed_context_size_error\","
                            + "\"n_prompt_tokens\":40016,\"n_ctx\":32768}}").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8);

            assertThat(e.getMessage()).as("전제 — 메시지에는 마커가 없다").doesNotContain("context");
            assertThat(LlmRouter.isContextOverflow(e)).isTrue();
            assertThat(LlmRouter.isContextOverflow(new RuntimeException("wrapped", e))).isTrue();
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

    /** 다른 HTTP 클라이언트의 전용 타입 — 클래스 이름 끝({@code ConnectTimeoutException})으로 알아본다. */
    static class ConnectTimeoutException extends IOException {
        ConnectTimeoutException(String message) { super(message); }
    }

    /** Reactor Netty 의 응답 전 연결 끊김 — 같은 이유로 클래스 이름으로 알아본다. */
    static class PrematureCloseException extends IOException {
        PrematureCloseException(String message) { super(message); }
    }

    @Nested
    @DisplayName("연결 타임아웃 — 서버가 떠 있지 않다는 뜻이라 차단하고 넘긴다(읽기 타임아웃과 결론이 반대)")
    class ConnectTimeout {

        @ParameterizedTest
        @ValueSource(strings = {
                "Connect timed out",                         // JDK 21 (HttpURLConnection — 이 앱의 블로킹 클라이언트)
                "connect timed out",                         // 옛 JDK
                "Connect to 10.0.0.5:1234 timed out"          // Apache HttpClient 의 SocketTimeoutException 모양
        })
        @DisplayName("SocketTimeoutException 의 메시지가 연결을 말하면 연결 타임아웃이다")
        void jdkMessagesClassify(String message) {
            assertThat(LlmRouter.isConnectTimeout(new SocketTimeoutException(message))).isTrue();
            // 실제로는 RestClient 가 ResourceAccessException 으로 감싸 올린다 — 원인 사슬을 탄다
            assertThat(LlmRouter.isConnectTimeout(
                    new RuntimeException("I/O error on POST request", new SocketTimeoutException(message)))).isTrue();
        }

        @Test
        @DisplayName("읽기 타임아웃은 아니다 — 서버는 연결을 받았고 일하는 중일 수 있다(차단하면 멀쩡한 서버를 막는다)")
        void readTimeoutIsNot() {
            assertThat(LlmRouter.isConnectTimeout(new SocketTimeoutException("Read timed out"))).isFalse();
            assertThat(LlmRouter.isConnectTimeout(new SocketTimeoutException())).as("메시지가 없으면 모른다 — 차단하지 않는 쪽")
                    .isFalse();
            assertThat(LlmRouter.isConnectTimeout(new InterruptedIOException("interrupted"))).isFalse();
        }

        @Test
        @DisplayName("다른 클라이언트의 전용 타입도 알아본다 — JDK HttpClient · Netty · Apache")
        void otherClientsTypes() {
            assertThat(LlmRouter.isConnectTimeout(new java.net.http.HttpConnectTimeoutException("HTTP connect timed out"))).isTrue();
            assertThat(LlmRouter.isConnectTimeout(new ConnectTimeoutException("connection timed out: /10.0.0.5:1234"))).isTrue();
            assertThat(LlmRouter.isConnectTimeout(
                    new RuntimeException("x", new ConnectTimeoutException("connection timed out")))).isTrue();
        }

        @Test
        @DisplayName("연결 거부는 연결 '타임아웃'이 아니다 — 그건 원래부터 차단·전환 대상이라 따로 알아보지 않는다")
        void connectionRefusedIsNotATimeout() {
            assertThat(LlmRouter.isConnectTimeout(new ConnectException("Connection refused"))).isFalse();
            assertThat(LlmRouter.isConnectTimeout(new RuntimeException("Connection refused"))).isFalse();
        }
    }

    @Nested
    @DisplayName("연결 계열 실패 — 스트리밍의 장애 전환이 '이 서버는 지금 안 된다'의 근거로 쓴다")
    class ConnectionFailure {

        @Test
        @DisplayName("연결 거부·DNS·경로 없음·포트 도달 불가·연결 타임아웃·응답 전 연결 끊김은 연결 계열이다")
        void serverUnreachableShapes() {
            assertThat(LlmRouter.isConnectionFailure(new ConnectException("Connection refused"))).isTrue();
            assertThat(LlmRouter.isConnectionFailure(new UnknownHostException("gpu-a"))).isTrue();
            assertThat(LlmRouter.isConnectionFailure(new NoRouteToHostException("No route to host"))).isTrue();
            assertThat(LlmRouter.isConnectionFailure(new PortUnreachableException("ICMP Port Unreachable"))).isTrue();
            assertThat(LlmRouter.isConnectionFailure(new SocketTimeoutException("Connect timed out"))).isTrue();
            assertThat(LlmRouter.isConnectionFailure(
                    new PrematureCloseException("Connection prematurely closed BEFORE response"))).isTrue();
        }

        @Test
        @DisplayName("WebClient 가 요청을 보내다 난 I/O 오류(WebClientRequestException)는 그 자체로 연결 계열이다 — 스트리밍의 실제 모양")
        void webClientRequestException() {
            var e = new WebClientRequestException(new ConnectException("Connection refused: getsockopt"),
                    HttpMethod.POST, URI.create("http://127.0.0.1:1234/v1/chat/completions"), new HttpHeaders());

            assertThat(LlmRouter.isConnectionFailure(e)).isTrue();
        }

        @Test
        @DisplayName("읽기 타임아웃·SSL·알 수 없는 오류·클라이언트 끊김은 연결 계열이 아니다 — 차단하면 오탐이다")
        void everythingElseIsNot() {
            assertThat(LlmRouter.isConnectionFailure(new SocketTimeoutException("Read timed out"))).isFalse();
            assertThat(LlmRouter.isConnectionFailure(new javax.net.ssl.SSLHandshakeException("PKIX path building failed"))).isFalse();
            assertThat(LlmRouter.isConnectionFailure(new IllegalStateException("boom"))).isFalse();
            // 사용자가 중지를 눌렀을 때 emitter.send() 가 터지는 모양 — 서버 탓이 아니다
            assertThat(LlmRouter.isConnectionFailure(new UncheckedIOException(new IOException("Broken pipe")))).isFalse();
            assertThat(LlmRouter.isConnectionFailure(
                    new UncheckedIOException(new InterruptedIOException("토큰 스트림이 중단됐다")))).isFalse();
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
