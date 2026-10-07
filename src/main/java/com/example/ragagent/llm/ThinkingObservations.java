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
     * @param outputTokens      출력 토큰(생각 포함). 서버가 센 값이 있으면 그것, 없으면 스트리밍은 델타 수로 센 추정,
     *                          블로킹은 {@code null}
     * @param outputEstimated   {@code outputTokens} 가 추정인가(서버 보고가 없는 스트리밍 — 델타 수)
     * @param thinkingTokens    생각에 쓴 토큰. 서버가 {@code reasoning_tokens} 를 보고하면 그 값, 아니면 추정
     *                          (llama.cpp 는 보고하지 않는다 — 2026-10-02 확인). 생각한 흔적이 없으면 {@code null}
     * @param thinkingEstimated {@code thinkingTokens} 가 추정인가
     * @param thinkingObserved  생각한 흔적이 있었는가 — 서버가 생각 토큰을 보고했거나, 생각 본문(스트리밍의 생각 델타)이
     *                          따로 왔거나, 출력 토큰이 답변 추정을 크게 넘었을 때({@link #MIN_EXCESS_TOKENS})만
     *                          {@code true}. 근거가 약하면 {@code false} 다 — 블로킹에서는 긴 답변 앞의 짧은 생각을 놓친다
     * @param truncated         {@code finish_reason=length} — 출력 상한에 걸려 잘렸다
     */
    public record Sample(ThinkingWire.Sent sent, Integer outputTokens, boolean outputEstimated, Integer thinkingTokens,
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
     * 한 키의 표본을 {@code /settings} 미리보기가 읽는 숫자로 줄인 것(PLAN §6.29 ⑧).
     *
     * <p>생각 토큰의 분위수는 <b>생각한 표본만</b>으로 센다 — 생각하지 않은 호출(0)까지 넣으면 p50 이 0 으로 내려가
     * "생각 자리가 p50 보다 작은가"라는 잘림 판정이 늘 거짓이 된다. 출력 토큰의 분위수는 서버가 센 값이 있는 표본만이다.
     *
     * @param count            표본 수
     * @param thinkingObserved 생각한 흔적이 있던 표본 수
     * @param truncated        {@code finish_reason=length} 로 끝난 표본 수
     * @param outputP50        출력 토큰 중앙값 — 센 표본이 없으면 {@code null}
     * @param thinkingP50      생각 토큰 중앙값 — 생각 토큰을 잰(보고·추정) 표본이 없으면 {@code null}
     * @param thinkingEstimated 생각 토큰이 추정인 표본이 하나라도 섞였는가 — 화면이 "(추정)" 을 붙인다
     */
    public record Stats(int count, int thinkingObserved, int truncated, Integer outputP50, Integer outputP95,
                        Integer thinkingP50, Integer thinkingP95, boolean thinkingEstimated) {

        public static final Stats EMPTY = new Stats(0, 0, 0, null, null, null, null, false);

        public boolean any() {
            return count > 0;
        }
    }

    /** 표본 한 묶음의 요약 — 순수 함수. 빈 목록이면 {@link Stats#EMPTY}. */
    public static Stats statsOf(List<Sample> samples) {
        if (samples == null || samples.isEmpty()) return Stats.EMPTY;
        List<Integer> outputs = samples.stream().map(Sample::outputTokens).filter(java.util.Objects::nonNull)
                .sorted().toList();
        List<Integer> thinking = samples.stream().map(Sample::thinkingTokens).filter(java.util.Objects::nonNull)
                .sorted().toList();
        return new Stats(
                samples.size(),
                (int) samples.stream().filter(Sample::thinkingObserved).count(),
                (int) samples.stream().filter(Sample::truncated).count(),
                percentile(outputs, 50), percentile(outputs, 95),
                percentile(thinking, 50), percentile(thinking, 95),
                samples.stream().anyMatch(s -> s.thinkingTokens() != null && s.thinkingEstimated()));
    }

    /** 가까운 순위 방식 — 오름차순 목록의 p 분위수. 빈 목록이면 {@code null}. */
    static Integer percentile(List<Integer> sortedAscending, int percent) {
        if (sortedAscending.isEmpty()) return null;
        int rank = (int) Math.ceil(percent / 100.0 * sortedAscending.size());
        return sortedAscending.get(Math.max(0, Math.min(sortedAscending.size() - 1, rank - 1)));
    }

    /** 생성 속도를 믿으려면 필요한 최소 표본 수 — 그보다 적으면 "최악 소요"를 내지 않는다. */
    static final int MIN_SPEED_SAMPLES = 3;

    /** 속도 표본으로 쓰려면 이만큼은 걸리고 이만큼은 내야 한다 — 짧은 호출은 prefill·왕복 지연이 속도를 지배한다. */
    private static final long MIN_SPEED_LATENCY_MS = 500;
    private static final int MIN_SPEED_OUTPUT_TOKENS = 16;

    /**
     * 이 프로바이더의 생성 속도(토큰/초) — 모든 사이트·수준의 표본에서 낸 중앙값. 속도는 사이트가 아니라 서버·모델의 성질이라
     * 키를 가리지 않는다(그래야 처음 켜 본 수준도 숫자를 받는다). 출력 토큰 ÷ 지연이라 prefill 시간이 섞여 실제보다
     * <b>느리게</b> 나오는 하한 추정이고, 그만큼 "최악 소요"는 보수적이다. 표본이 모자라면 {@code null}.
     */
    public Double speedTokensPerSecond(String provider) {
        if (provider == null) return null;
        List<Sample> all = new java.util.ArrayList<>();
        samples.forEach((key, deque) -> {
            if (!provider.equals(key.provider())) return;
            synchronized (deque) {
                all.addAll(deque);
            }
        });
        return speedOf(all);
    }

    /** {@link #speedTokensPerSecond} 의 몸통 — 순수 함수. */
    static Double speedOf(List<Sample> all) {
        List<Double> speeds = all.stream()
                .filter(s -> s.outputTokens() != null && s.outputTokens() >= MIN_SPEED_OUTPUT_TOKENS
                        && s.latencyMs() >= MIN_SPEED_LATENCY_MS)
                .map(s -> s.outputTokens() * 1000.0 / s.latencyMs())
                .sorted().toList();
        if (speeds.size() < MIN_SPEED_SAMPLES) return null;
        int mid = speeds.size() / 2;
        return speeds.size() % 2 == 1 ? speeds.get(mid) : (speeds.get(mid - 1) + speeds.get(mid)) / 2.0;
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
        Generation generation = response == null ? null : response.getResult();
        return sampleOf(sent, nativeUsageOf(response), textOf(generation), reasoningOf(generation),
                finishReasonOf(generation), latencyMs);
    }

    /**
     * 위 규칙의 몸통 — 응답에서 꺼낸 값으로 센다. 체인을 지나는 스트림({@code ThinkingControlChatModel.stream})은 응답
     * 여러 개를 모아 여기로 온다.
     *
     * @param usage 서버가 보고한 사용량. 없으면 {@code null}
     */
    static Sample sampleOf(ThinkingWire.Sent sent, OpenAiApi.Usage usage, String content, String reasoning,
                           String finish, long latencyMs) {
        Integer output = usage == null ? null : usage.completionTokens();
        Integer reported = reportedThinking(usage);
        boolean truncated = isTruncation(finish);

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
        return new Sample(sent, output, false, thinking, estimated, observed, truncated, latencyMs);
    }

    /**
     * 체인을 지나지 않는 스트리밍(채팅 답변 — {@code AnswerStreamer}) 한 번. 블로킹과 근거가 다르다 — 여기서는 생각이
     * <b>직접 보인다</b>: 그 경로는 청크를 그대로 읽으므로 서버의 {@code reasoning_content} 델타가 버려지지 않는다. 생각
     * 델타가 하나라도 왔으면 생각한 것이다 — 이 판정은 추정이 아니다(서버가 생각 본문을 보냈다). 추정인 것은 토큰 수다.
     *
     * <p><b>토큰 수는 델타 수다.</b> llama.cpp 는 토큰마다 델타 하나를 보낸다 — 2026-10-02 실측(b10236 + gemma-4-E2B):
     * 생각 델타 111 + 답 델타 1 에 서버의 {@code predicted_n} 117. 차이는 생각의 시작·끝 표지처럼 델타로 나오지 않는 특수
     * 토큰이라 하한 추정이고, 여러 토큰을 한 델타로 묶어 보내는 서버에서는 더 모자란다. 서버가 {@code usage} 를 함께
     * 보냈으면 출력은 그 값을 쓴다(이 앱은 {@code stream_options} 를 요청하지 않으므로 대개 없다).
     *
     * @param finish 마지막으로 본 {@code finish_reason} 의 이름(대소문자 무관). 없으면 {@code null}
     * @param usage  서버가 보고한 사용량. 없으면 {@code null}
     */
    public static Sample streamSampleOf(ThinkingWire.Sent sent, int contentDeltas, int reasoningDeltas,
                                        String finish, OpenAiApi.Usage usage, long latencyMs) {
        Integer reportedOutput = usage == null ? null : usage.completionTokens();
        Integer reported = reportedThinking(usage);
        Integer thinking = null;
        boolean estimated = false;
        boolean observed = false;
        if (reported != null && reported > 0) {
            thinking = reported;
            observed = true;
        } else if (reasoningDeltas > 0) {
            thinking = reasoningDeltas;
            estimated = true;
            observed = true;
        }
        return new Sample(sent,
                reportedOutput != null ? reportedOutput : Integer.valueOf(contentDeltas + reasoningDeltas),
                reportedOutput == null, thinking, estimated, observed, isTruncation(finish), latencyMs);
    }

    /** 잘림 — {@code finish_reason=length}. 대소문자는 가리지 않는다(Spring AI 는 블로킹에서 {@code LENGTH} 로 준다). */
    private static boolean isTruncation(String finish) {
        return "length".equalsIgnoreCase(finish);
    }

    private static Integer reportedThinking(OpenAiApi.Usage usage) {
        return usage == null || usage.completionTokenDetails() == null
                ? null : usage.completionTokenDetails().reasoningTokens();
    }

    /** 응답 메타데이터의 서버 사용량. Spring AI 가 원본({@code OpenAiApi.Usage})을 실어 두지 않았으면 {@code null}. */
    static OpenAiApi.Usage nativeUsageOf(ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return usage != null && usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage ? nativeUsage : null;
    }

    static String textOf(Generation generation) {
        AssistantMessage message = generation == null ? null : generation.getOutput();
        return message == null ? null : message.getText();
    }

    static String reasoningOf(Generation generation) {
        AssistantMessage message = generation == null ? null : generation.getOutput();
        return message != null && message.getMetadata() != null
                && message.getMetadata().get(REASONING_CONTENT_KEY) instanceof String s ? s : null;
    }

    static String finishReasonOf(Generation generation) {
        return generation == null || generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
    }
}
