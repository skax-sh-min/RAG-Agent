package com.example.ragagent.llm;

import com.openai.errors.OpenAIServiceException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 실패한 LLM 호출에서 <b>서버가 한 말</b> — 예외 사슬의 메시지 전부와, 메시지에 본문이 실리지 않는 HTTP 오류의 응답 본문.
 *
 * <p>오류를 서버 문구로 가르는 판정(컨텍스트 초과 {@link LlmRouter#isContextOverflow}, 생각 필드 거부
 * {@link ThinkingControl#rejectedField})이 읽는 단일 출처다.
 *
 * <p>채팅·임베딩 호출의 오류는 OpenAI SDK 의 {@link OpenAIServiceException}(상태 코드·헤더·파싱된 본문을 가진다)이고,
 * 스트리밍은 그것이 {@code CompletionException} 에 싸여 온다.
 *
 * <p><b>왜 메시지만으로는 안 되는가.</b> SDK 가 메시지에 싣는 것은 본문 전체가 아니라 {@code message} 나 {@code error} 값뿐일 수
 * 있어(예: {@code {"error":"terminated"}} → {@code 500: "terminated"}) 파싱된 본문을 함께 읽는다. llama.cpp 는 스트리밍 요청의
 * 컨텍스트 초과도 SSE 가 아니라 일반 HTTP 400 본문({@code "request (40016 tokens) exceeds the available context size …"})으로
 * 돌려준다(2026-10-02 실측).
 *
 * <p>{@link WebClientResponseException}·{@link RestClientResponseException} 갈래는 응답 본문을 가진 다른 HTTP 오류를 위한 것이다.
 * WebClient 는 OpenAI 경로가 더는 만들지 않는 잔재이고, {@code RestClientResponseException} 은 {@code LlmPing}·Chroma 의
 * 날것 HTTP 가 쓴다.
 */
public final class LlmErrorText {

    private LlmErrorText() {}

    /** 사슬을 따라 메시지와 응답 본문을 줄바꿈으로 이은 것. 아무것도 없으면 빈 문자열. 순환 사슬에서도 끝난다. */
    public static String of(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cur = t; cur != null && seen.add(cur); cur = cur.getCause()) {
            append(sb, cur.getMessage());
            if (cur instanceof OpenAIServiceException o) {
                // Spring AI 2.0 의 SDK 예외는 메시지에 본문(또는 본문의 error 값)이 실려 오지만 서버 구현에 따라 다르다 —
                // 파싱된 본문도 함께 본다.
                append(sb, String.valueOf(o.body()));
            } else if (cur instanceof WebClientResponseException w) {
                append(sb, w.getResponseBodyAsString());
            } else if (cur instanceof RestClientResponseException r) {
                append(sb, r.getResponseBodyAsString());
            }
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String text) {
        if (text == null || text.isEmpty()) return;
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(text);
    }
}
