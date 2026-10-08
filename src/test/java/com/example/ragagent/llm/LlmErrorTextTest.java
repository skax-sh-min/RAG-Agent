package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버가 한 말을 읽는 단일 출처({@link LlmErrorText}). 메시지에 본문이 없는 HTTP 오류(스트리밍 경로의 WebClient)에서
 * 본문까지 읽는지가 핵심이다 — 그게 빠지면 컨텍스트 초과·필드 거부 판정이 그 경로에서 조용히 거짓이 된다.
 */
class LlmErrorTextTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("WebClient 오류 — 메시지와 응답 본문을 함께 읽는다")
    void readsTheWebClientResponseBody() {
        var e = WebClientResponseException.create(400, "Bad Request", new HttpHeaders(),
                utf8("{\"error\":\"Unrecognized request argument supplied: chat_template_kwargs\"}"),
                StandardCharsets.UTF_8);

        String said = LlmErrorText.of(e);

        assertThat(e.getMessage()).doesNotContain("chat_template_kwargs");
        assertThat(said).contains("400 Bad Request").contains("chat_template_kwargs");
    }

    @Test
    @DisplayName("RestClient 오류의 본문도 읽는다 — 메시지가 본문을 잘라 실어도 놓치지 않는다")
    void readsTheRestClientResponseBody() {
        var e = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                utf8("{\"error\":\"" + "x".repeat(400) + " chat_template_kwargs\"}"), StandardCharsets.UTF_8);

        assertThat(LlmErrorText.of(e)).contains("chat_template_kwargs");
    }

    @Test
    @DisplayName("원인 사슬을 따라 내려가고, 순환 사슬에서도 끝난다")
    void walksTheCauseChainAndStopsOnCycles() {
        var inner = WebClientResponseException.create(400, "Bad Request", new HttpHeaders(),
                utf8("prompt is too long"), StandardCharsets.UTF_8);
        RuntimeException outer = new RuntimeException("call failed", new IllegalStateException("middle", inner));
        assertThat(LlmErrorText.of(outer)).contains("call failed").contains("middle").contains("prompt is too long");

        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(LlmErrorText.of(a)).isEqualTo("a\nb");
    }

    @Test
    @DisplayName("말이 없으면 빈 문자열 — null 이 아니다")
    void nothingSaidIsEmpty() {
        assertThat(LlmErrorText.of(null)).isEmpty();
        assertThat(LlmErrorText.of(new RuntimeException())).isEmpty();
    }
}
