package com.example.ragagent.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 생각 수준을 정한 호출이 실제로 어떻게 끝났는가 — (사이트, 프로바이더, 수준)마다 최근 {@value #CAPACITY}회.
 *
 * <p>{@code /settings} 미리보기(5단계)의 "관측"·"최악 소요"·잘림 배지가 여기서 나온다. 1단계부터 쌓는 이유는 화면이
 * 생길 때 이미 숫자가 있게 하기 위해서다.
 *
 * <p><b>관측만 하고 아무것도 바꾸지 않는다.</b> 생각 여유를 이 숫자로 자동 보정하지 않는다 — 표본 수에 따라 같은
 * 요청이 다른 예산을 받으면 재현이 안 된다({@link TokenEstimateCalibration} 과 같은 원칙). <b>숫자만 남긴다</b> —
 * 프롬프트와 생각 본문에는 검색 문서 본문이 들어 있다. 메모리에만 두고 재시작하면 비는 이유는, 수준을 바꿔 가며
 * 실험할 때 이전 상태의 통계가 섞이지 않게 하기 위해서다.
 *
 * <p><b>키가 "나간 값"이 아니라 "수준"인 이유</b>: llama.cpp 에서는 낮게·중간·높게가 같은 값으로 나가지만, 4단계부터
 * 예약(생각 여유)이 달라 잘림 통계가 갈린다. 나간 값은 표본마다 따로 남긴다({@link Sample#sent()}).
 */
@Component
public class ThinkingObservations {

    /** 키마다 남기는 표본 수. */
    public static final int CAPACITY = 50;

    /**
     * 응답 메시지 메타데이터에서 생각 본문을 찾는 키. <b>Spring AI 1.1.8 은 블로킹 응답에서 서버의
     * {@code reasoning_content} 를 버린다</b>(2026-10-02 확인 — 메타데이터에 이 키가 없다). 그래서 블로킹에서는 아래
     * {@link #MIN_EXCESS_TOKENS} 의 출력 토큰 초과로 판정한다. 키를 남겨 두는 이유는 이것이 실리는 경우(다른 버전·경로)에는
     * 더 강한 근거이기 때문이다.
     */
    static final String REASONING_CONTENT_KEY = "reasoningContent";

    /**
     * 생각 본문도 생각 토큰 보고도 없을 때, 출력 토큰이 답변 추정보다 이만큼 이상, <b>그리고</b> 답변 추정보다 많이 남으면
     * (= 두 배 초과) 생각한 것으로 센다. 이 앱에서 생각을 정하는 호출은 대부분 짧은 응답이라 "한 줄 앞의 수백 토큰"은
     * 확실히 잡고, 긴 답변 앞의 짧은 생각은 놓친다 — 잘못 "생각했다"고 하는 쪽이 더 해롭다("끔으로 보냈는데 생각했다"는
     * 판정이 이 값에 달려 있다). 추정의 오차(한글 1자 = 1토큰 가정)가 두 배까지 벌어지는 일은 드물다.
     */
    static final int MIN_EXCESS_TOKENS = 64;

    public record Key(ThinkingSite site, String provider, ThinkingLevel level) {}

    /**
     * 호출 한 번.
     *
     * @param outputTokens      서버가 센 출력 토큰(생각 포함). 보고가 없으면 {@code null}
     * @param thinkingTokens    생각에 쓴 토큰. 서버가 {@code reasoning_tokens} 를 보고하면 그 값, 아니면 추정
     *                          (llama.cpp 는 보고하지 않는다 — 2026-10-02 확인). 생각한 흔적이 없으면 {@code null}
     * @param thinkingEstimated {@code thinkingTokens} 가 추정인가
     * @param thinkingObserved  생각한 흔적이 있었는가 — 서버가 생각 토큰을 보고했거나, 생각 본문이 따로 왔거나, 출력
     *                          토큰이 답변 추정을 크게 넘었을 때({@link #MIN_EXCESS_TOKENS})만 {@code true}. 근거가
     *                          약하면 {@code false} 다 — 긴 답변 앞의 짧은 생각은 놓친다
     * @param truncated         {@code finish_reason=length} — 출력 상한에 걸려 잘렸다
     */
    public record Sample(ThinkingWire.Sent sent, Integer outputTokens, Integer thinkingTokens,
                         boolean thinkingEstimated, boolean thinkingObserved, boolean truncated, long latencyMs) {}

    private final Map<Key, Deque<Sample>> samples = new ConcurrentHashMap<>();

    public void record(ThinkingSite site, String provider, ThinkingLevel level, Sample sample) {
        if (site == null || provider == null || level == null || sample == null) return;
        Deque<Sample> deque = samples.computeIfAbsent(new Key(site, provider, level), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(sample);
            while (deque.size() > CAPACITY) deque.removeFirst();
        }
    }

    /** 오래된 것부터. */
    public List<Sample> samples(ThinkingSite site, String provider, ThinkingLevel level) {
        Deque<Sample> deque = samples.get(new Key(site, provider, level));
        if (deque == null) return List.of();
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    public Map<Key, List<Sample>> snapshot() {
        Map<Key, List<Sample>> copy = new HashMap<>();
        samples.forEach((key, deque) -> {
            synchronized (deque) {
                copy.put(key, List.copyOf(deque));
            }
        });
        return copy;
    }

    /**
     * 블로킹 응답 하나를 표본으로. 생각한 흔적은 근거가 강한 것부터 본다:
     * <ol>
     *   <li>서버가 보고한 {@code reasoning_tokens} — 그대로 쓴다(OpenAI 계열).</li>
     *   <li>생각 본문이 따로 왔다 — 생각 토큰은 (출력 토큰 − 답변 추정), 출력 토큰이 없으면 본문 추정. 앞쪽이 나은
     *       이유는 서버가 센 출력 토큰에 생각이 이미 들어 있기 때문이다 — 영어 생각 본문을 글자 4개당 1토큰으로 세면
     *       실측(227토큰 중 생각 ~225)의 2/3 쯤으로 모자란다.</li>
     *   <li>둘 다 없다 — 이 앱의 블로킹 경로가 실제로 여기다(Spring AI 1.1.8 이 생각 본문을 버리고, llama.cpp 는
     *       생각 토큰을 보고하지 않는다). 출력 토큰이 답변 추정을 크게 넘을 때만 그 초과분을 생각으로 센다
     *       ({@link #MIN_EXCESS_TOKENS}).</li>
     * </ol>
     */
    static Sample sampleOf(ThinkingWire.Sent sent, ChatResponse response, long latencyMs) {
        Integer output = null;
        Integer reported = null;
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null && usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage) {
            output = nativeUsage.completionTokens();
            if (nativeUsage.completionTokenDetails() != null) {
                reported = nativeUsage.completionTokenDetails().reasoningTokens();
            }
        }

        Generation generation = response == null ? null : response.getResult();
        AssistantMessage message = generation == null ? null : generation.getOutput();
        String content = message == null ? null : message.getText();
        String reasoning = message != null && message.getMetadata() != null
                && message.getMetadata().get(REASONING_CONTENT_KEY) instanceof String s ? s : null;
        String finish = generation == null || generation.getMetadata() == null
                ? null : generation.getMetadata().getFinishReason();
        boolean truncated = "length".equalsIgnoreCase(finish);

        Integer thinking = null;
        boolean estimated = false;
        boolean observed = false;
        if (reported != null && reported > 0) {
            thinking = reported;
            observed = true;
        } else if (reasoning != null && !reasoning.isBlank()) {
            observed = true;
            estimated = true;
            thinking = output != null
                    ? (int) Math.max(0, output - TokenEstimator.estimate(content))
                    : (int) TokenEstimator.estimate(reasoning);
        } else if (output != null) {
            // 블로킹 경로의 실제 모양(Spring AI 가 생각 본문을 버린다) — 서버가 센 출력에는 생각이 들어 있으므로,
            // 답변으로 설명되지 않는 몫이 크면 그것이 생각이다.
            long answer = TokenEstimator.estimate(content);
            long excess = output - answer;
            if (excess >= MIN_EXCESS_TOKENS && excess > answer) {
                observed = true;
                estimated = true;
                thinking = (int) excess;
            }
        }
        return new Sample(sent, output, thinking, estimated, observed, truncated, latencyMs);
    }
}
