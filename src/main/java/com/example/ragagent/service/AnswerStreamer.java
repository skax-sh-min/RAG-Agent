package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmCurlLogger;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingWire;
import org.slf4j.Logger;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 채팅 답변을 토큰 단위로 받아 오는 <b>유일한</b> 자리 (PLAN §6.29 3단계) — {@code AnswerService}(RAG 답변 ·
 * PROGRESSIVE 2차)와 {@code DirectAnswerService}(Direct · meta 답변)가 {@code provider.stream()=true} 일 때 여기로 온다.
 *
 * <p><b>왜 체인 밖에 있는가.</b> 두 경로는 {@code OpenAiChatModel.internalStream()} 의 버퍼링을 피하려고
 * {@code OpenAiApi.chatCompletionStream()} 을 직접 부르므로 {@code ChatModel} 데코레이터 체인(생각 수준을 싣는
 * {@code ThinkingControlChatModel}, curl 로그)을 통째로 지나지 않는다. 채팅 답변의 주 경로가 여기라, 체인이 하던 일
 * 중 이 경로에 필요한 것을 <b>같은 함수로</b> 한다:
 * <ol>
 *   <li><b>생각 수준</b> — 사이트의 수준({@code props.llmSafe().thinkingLevel(site)}, 매 호출)을 이 프로바이더의 필드로
 *       바꿔({@link ProviderThinkingDialects#wireFor}) 요청에 싣는다({@link ThinkingControl#applyTo}). 블로킹과 같은
 *       변환, 같은 거부 기억이다.</li>
 *   <li><b>생각을 활동으로 센다</b> — 생각이 켜지면 llama.cpp 는 생각을 {@code reasoning_content} 델타로 흘리고 그동안
 *       {@code content} 는 비어 있다(2026-10-02 실측: 한 단어 답 앞에 생각 델타 111개). 예전처럼 {@code content} 만
 *       읽으면 생각이 긴 답변은 그동안 아무 일도 없는 것처럼 보이고, 유휴 워치독({@code app.sse-idle-timeout-seconds})이
 *       정상 답변을 끊는다. 생각 본문은 화면에 내보내지 않는다 — 답변이 아니고, 검색 문서 원문이 섞여 있다.</li>
 *   <li><b>거부 재시도</b> — 서버가 실은 필드를 거부하면 기억하고 빼서 <b>한 번</b> 다시 보낸다. 서버에서 청크가 하나라도
 *       왔다면(= 요청을 받아들였다) 다시 보내지 않는다 — 화면에 앞부분이 두 번 찍힌다.</li>
 *   <li><b>관측</b> — 델타 수와 마지막 {@code finish_reason} 을 {@link ThinkingObservations} 에 남긴다. 숫자만 남긴다.</li>
 *   <li><b>curl 로그</b> — 실제로 나간 본문(생각 필드 포함)을 호출부 로거로 찍는다.</li>
 * </ol>
 *
 * <p>사용량 집계·축소 재시도·동시성 퍼밋은 여기 일이 아니다 — 호출부가 하던 그대로 한다(축소 재시도는 프롬프트를
 * 조립하는 {@code AnswerService} 만 할 수 있다).
 */
@Component
public class AnswerStreamer {

    /**
     * 로그를 남길 자리 — 호출부의 로거·태그·대화. 스트림 로그와 curl 로그가 예전처럼 그 서비스의 이름으로 남아
     * ({@code [Answer]}/{@code [DirectAnswer]}) 어느 경로인지 구분된다. {@code LlmCurlLogger.log} 가 호출부 로거를
     * 받는 것과 같은 이유다.
     */
    record Trace(Logger log, String tag, String threadId, RoutingMode routingMode) {}

    private final ProviderThinkingDialects dialects;
    private final ThinkingObservations observations;
    private final Function<ThinkingSite, ThinkingLevel> levels;

    @Autowired
    public AnswerStreamer(ProviderThinkingDialects dialects, ThinkingObservations observations, AppProperties props) {
        // 값이 아니라 함수 — 수준은 핫 편집 대상이다(ThinkingControlChatModel 과 같은 이유).
        this(dialects, observations, site -> props.llmSafe().thinkingLevel(site));
    }

    /** 수준을 함수로 받는다 — 테스트가 설정 계층(정적 오버라이드) 없이 수준을 정할 수 있게. */
    AnswerStreamer(ProviderThinkingDialects dialects, ThinkingObservations observations,
                   Function<ThinkingSite, ThinkingLevel> levels) {
        this.dialects = dialects;
        this.observations = observations;
        this.levels = levels;
    }

    /**
     * 생각 제어 없이 — 아무 필드도 싣지 않는다(dialect 를 모르는 프로바이더에는 싣지 않는 규칙 그대로). 두 서비스의
     * 테스트용 하위호환 생성자가 쓴다. 관측은 아무도 읽지 않는 곳에 쌓인다.
     */
    static AnswerStreamer withoutThinkingControl() {
        return new AnswerStreamer(new ProviderThinkingDialects(), new ThinkingObservations(), ThinkingSite::shippedDefault);
    }

    /**
     * 답변 한 번을 스트리밍한다 — 호출 스레드에서 끝까지 소비하고 돌아온다. 중지·연결 끊김은 {@link CancellableTokenStream}
     * 이 LLM 쪽 구독까지 취소한 뒤 예외를 그대로 올린다(호출부의 예외 처리는 예전과 같다).
     *
     * @param provider   라우터가 이미 고른 프로바이더. 동시성 퍼밋은 호출부가 쥐고 있다
     * @param site       이 답변의 호출 지점 — 생각 수준을 정한다
     * @param tokenSink  답변 토큰({@code content} 델타). 빈 델타는 거른다
     * @param onThinking 생각 델타마다 — 내용은 넘기지 않는다. 유휴 워치독과 "생각 중" 표시가 쓴다
     */
    void stream(LlmProvider provider, ThinkingSite site, String systemPrompt, String userPrompt, double temperature,
                Consumer<String> tokenSink, Runnable onThinking, Trace trace) {
        OpenAiApi.ChatCompletionRequest base = new OpenAiApi.ChatCompletionRequest(List.of(
                new OpenAiApi.ChatCompletionMessage(systemPrompt, OpenAiApi.ChatCompletionMessage.Role.SYSTEM),
                new OpenAiApi.ChatCompletionMessage(userPrompt, OpenAiApi.ChatCompletionMessage.Role.USER)),
                provider.model(), temperature, true);
        ThinkingLevel level = levels.apply(site);
        ThinkingWire wire = dialects.wireFor(provider.name(), level);
        Tally first = new Tally();
        try {
            attempt(provider, site, level, wire, base, first, tokenSink, onThinking, trace, true);
        } catch (RuntimeException e) {
            String field = retryableRejection(e, wire, first, true);
            if (field == null) throw e;
            if (dialects.markRejected(provider.name(), field)) {
                trace.log().warn("[THINKING] provider=[{}] 가 {} 를 거부했다 — 이 프로세스 동안은 싣지 않는다"
                        + "(이 서버에서는 생각 수준을 정하지 못한다): {}", provider.name(), field, e.getMessage());
            }
            attempt(provider, site, level, dialects.wireFor(provider.name(), level), base, new Tally(),
                    tokenSink, onThinking, trace, false);
        }
    }

    private void attempt(LlmProvider provider, ThinkingSite site, ThinkingLevel level, ThinkingWire wire,
                         OpenAiApi.ChatCompletionRequest base, Tally tally, Consumer<String> tokenSink,
                         Runnable onThinking, Trace trace, boolean mayRetry) {
        OpenAiApi.ChatCompletionRequest request = ThinkingControl.applyTo(base, wire);
        Logger log = trace.log();
        log.debug("[THINKING] site={} level={} provider={} sent={}",
                site.id(), level.value(), provider.name(), wire.describe());
        logRequest(provider, request, log);
        long started = System.nanoTime();
        CancellableTokenStream.consume(
                provider.openAiApi().chatCompletionStream(request)
                        // 서버가 청크를 하나라도 보냈다 = 요청을 받아들였다. 소비자보다 앞(업스트림)에서 세야 오류 신호와
                        // 같은 순서로 보인다 — 아직 소비되지 않은 청크가 있어도 거부가 아니다.
                        .doOnNext(chunk -> tally.received = true)
                        .doOnCancel(() -> log.warn("{} Stream cancelled provider={} thread={} route={}",
                                trace.tag(), provider.name(), trace.threadId(), trace.routingMode()))
                        .doOnError(e -> {
                            // 다시 보낼 거부는 오류가 아니다 — 경고 한 줄이 따로 남는다.
                            if (retryableRejection(e, wire, tally, mayRetry) == null) {
                                log.error("{} Stream error provider={}", trace.tag(), provider.name(), e);
                            }
                        })
                        .doFinally(signal -> log.debug("{} Stream finished signal={} provider={} thread={}",
                                trace.tag(), signal, provider.name(), trace.threadId())),
                chunk -> tally.accept(chunk, tokenSink, onThinking));
        // 정상 완료만 남긴다 — 중지·오류로 끊긴 스트림의 수치는 그 수준의 실제 동작이 아니다.
        observations.record(site, provider.name(), level, ThinkingObservations.streamSampleOf(wire.sent(),
                tally.contentDeltas, tally.reasoningDeltas, tally.finish, tally.usage,
                (System.nanoTime() - started) / 1_000_000));
    }

    /** 다시 보낼 거부인가 — 그 필드, 아니면 {@code null}. 오류 로그를 거를 때와 재시도를 정할 때 같은 답이어야 한다. */
    private static String retryableRejection(Throwable e, ThinkingWire wire, Tally tally, boolean mayRetry) {
        return mayRetry && !tally.received ? ThinkingControl.rejectedField(e, wire) : null;
    }

    /**
     * 실제로 나간 본문(생각 필드 포함)을 호출부 로거로 찍는다 — 이 경로는 {@code LoggingChatModel} 을 지나지 않는다.
     * DEBUG 는 엔드포인트+본문, TRACE 는 curl 전체({@link LlmCurlLogger}).
     */
    private static void logRequest(LlmProvider provider, OpenAiApi.ChatCompletionRequest request, Logger log) {
        if (!log.isDebugEnabled()) return;
        try {
            String endpoint = provider.baseUrl().replaceAll("/+$", "") + "/chat/completions";
            String json = LlmCurlLogger.toCurlBodyJson(request);
            LlmCurlLogger.log(log, "LLM", provider.name(), endpoint, provider.apiKey(), json);
        } catch (Exception e) {
            log.debug("[LLM curl] serialization error: {}", e.getMessage());
        }
    }

    /** 한 번의 시도에서 본 것 — 관측 표본의 재료. 숫자만 센다(본문은 남기지 않는다). */
    private static final class Tally {
        /** 업스트림 스레드가 쓰고 호출 스레드가 읽는다. */
        volatile boolean received;
        int contentDeltas;
        int reasoningDeltas;
        String finish;
        OpenAiApi.Usage usage;

        /**
         * 청크 하나를 가른다 — 생각 델타는 세고 알리기만, 답 델타는 호출부로. 한 델타에 둘이 함께 오면(생각이 끝나는
         * 자리에서 그러는 서버가 있다) 생각을 먼저 처리한다.
         */
        void accept(OpenAiApi.ChatCompletionChunk chunk, Consumer<String> tokenSink, Runnable onThinking) {
            if (chunk == null) return;
            if (chunk.usage() != null) usage = chunk.usage();
            if (chunk.choices() == null || chunk.choices().isEmpty()) return;
            OpenAiApi.ChatCompletionChunk.ChunkChoice choice = chunk.choices().get(0);
            if (choice.finishReason() != null) finish = choice.finishReason().name();
            OpenAiApi.ChatCompletionMessage delta = choice.delta();
            if (delta == null) return;
            String reasoning = delta.reasoningContent();
            if (reasoning != null && !reasoning.isEmpty()) {
                reasoningDeltas++;
                onThinking.run();
            }
            String content = delta.content();
            if (content != null && !content.isEmpty()) {
                contentDeltas++;
                tokenSink.accept(content);
            }
        }
    }
}
