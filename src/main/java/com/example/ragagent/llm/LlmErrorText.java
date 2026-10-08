package com.example.ragagent.llm;

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
 * <p><b>왜 메시지만으로는 안 되는가.</b> 블로킹 경로(RestClient)의 오류는 Spring AI 의 오류 처리기가 본문을 메시지에
 * 싣는다({@code "HTTP 400 - {...}"}). 스트리밍 경로 — {@code OpenAiApi.chatCompletionStream()} 의 WebClient — 에는 그
 * 처리기가 없다(Spring AI 1.1.8 의 {@code OpenAiApi} 생성자는 {@code defaultStatusHandler} 를 RestClient 에만 건다 —
 * 2026-10-02 바이트코드 확인). 그래서 {@link WebClientResponseException} 의 메시지는
 * {@code "400 Bad Request from POST …/chat/completions"} 뿐이고, 서버가 쓴 문장은 응답 본문에만 있다. llama.cpp 는
 * 스트리밍 요청의 컨텍스트 초과도 SSE 가 아니라 일반 HTTP 400 본문({@code "request (40016 tokens) exceeds the available
 * context size …"})으로 돌려주므로(같은 날 실측), 메시지만 보던 동안 채팅 답변 스트리밍의 축소 재시도는 실제 서버에서
 * 한 번도 발동하지 않았다.
 */
public final class LlmErrorText {

    private LlmErrorText() {}

    /** 사슬을 따라 메시지와 응답 본문을 줄바꿈으로 이은 것. 아무것도 없으면 빈 문자열. 순환 사슬에서도 끝난다. */
    public static String of(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cur = t; cur != null && seen.add(cur); cur = cur.getCause()) {
            append(sb, cur.getMessage());
            if (cur instanceof WebClientResponseException w) {
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
