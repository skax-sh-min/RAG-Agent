package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.MemoryRepository;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * §10.12 — 맥락에 기댄 <b>짧은 후속 질문</b>을 혼자서도 뜻이 통하는 질문으로 다시 쓴다(condense).
 *
 * <p><b>문제.</b> 검색 축 셋(벡터 · BM25/FTS · 큐레이션)은 전부 <b>질문 원문</b>을 본다. 이력은
 * 답변 단계에서만 쓰이므로 {@code "그 설정은 어디에 있어?"} 같은 질문은 검색어로서 의미가 없고,
 * 사용자에게는 "문서에 있는데 못 찾는다"로 보인다. 하필 그런 질문이 확장도 받지 못한다 —
 * {@code app.search-multiquery-min-length} 미만이면 MultiQuery 를 통째로 건너뛰는데
 * ({@code RetrievalService.shouldExpand()}), <b>짧은 질문이 곧 맥락 의존적인 질문</b>이라
 * 확장도 맥락도 없는 최악의 조합이 정확히 그 구간에 몰린다.
 *
 * <p><b>한 번의 재작성이 세 축을 함께 고친다.</b> 분류기·MultiQuery 에 이력을 따로 넘기는 대신
 * 질문 자체를 다시 써서 {@code AgentState.searchQuestion} 에 싣는다. MultiQuery 만 고치면 벡터
 * 축만 나아지고 BM25·큐레이션은 여전히 원문을 토크나이즈하며, 분류기에 필요한 것도 이력이 아니라
 * <b>독립화된 질문</b>이었다.
 *
 * <p><b>LLM 호출은 늘지 않는다.</b> 게이트가 {@code shouldExpand()} 의 정확한 여집합이라
 * (길이 &lt; 임계값) 한 턴에는 확장이나 독립화 중 <b>하나만</b> 돈다. 라우팅도 확장과 같은
 * {@code MICRO_TEXT} 계층이라, 소형 모델을 등록한 배포는 큰 모델을 건드리지 않는다(§6.21).
 *
 * <p><b>재료는 이전 <em>질문</em>들뿐이고 답변은 넣지 않는다.</b> 세 가지가 동시에 걸린다 —
 * ① 답변은 2~3천 자라 {@code MICRO_TEXT} 계층에 넘기기에 무겁다 ② 답변의 표현이 질문에 섞이면
 * <b>검색이 자기 답변을 다시 찾는</b> 쪽으로 기운다(§10.11 이 큐레이션에서 경계하는 순환과 같은
 * 모양) ③ Direct 턴의 답변은 문서에 근거하지 않은 텍스트인데, §10.13 이후 그것이 캡 없이 이력에
 * 들어간다 — 모델이 지어낸 용어가 검색어가 되고 그 위에 세운 답변이 다시 이력이 되어 용어를
 * 굳힌다(§10.12 열린 항목 (b)). 사용자가 직접 쓴 질문만 재료로 삼으면 그 오염 경로가
 * <b>구조적으로</b> 닫힌다 — 그래서 Direct 턴을 따로 걸러내지 않는다. 대명사의 지시 대상은
 * 대개 직전 질문에 이미 나와 있다.
 *
 * <p><b>실패하면 원문으로 검색한다.</b> 재작성이 없다고 검색을 멈출 이유는 없고, 이 앱이
 * {@code withoutVerdict()}·{@code ProviderContextWindows} 에서 지켜 온 "모르면 아무것도 하지
 * 않는다"와 같은 규칙이다.
 */
@Service
public class QuestionCondenser {

    private static final Logger log = LoggerFactory.getLogger(QuestionCondenser.class);

    /** 재료로 쓸 직전 질문 수. 대명사의 지시 대상은 거의 직전 한두 턴에 있고, 늘릴수록 주제가
     *  바뀐 옛 턴의 용어를 끌어올 위험만 커진다. */
    static final int MATERIAL_TURNS = 3;

    /** 질문 하나당 재료 상한. 이 계층에 넘기는 프롬프트를 작게 유지한다. */
    static final int MAX_MATERIAL_QUESTION_CHARS = 300;

    /**
     * 재작성 결과의 길이 상한. 넘으면 재작성을 <b>버린다</b> — 잘라 쓰지 않는다. 이 길이는 모델이
     * 질문 한 줄이 아니라 설명·목록을 내놓았다는 뜻이고, 그 앞부분만 잘라 검색어로 쓰면 원문보다
     * 나쁜 질의가 된다.
     */
    static final int MAX_CONDENSED_CHARS = 400;

    /**
     * 출력 예약. 한 줄짜리 응답에 프로바이더의 {@code app.llm.max-tokens} 전체를 예약하면, 좁은
     * 창에서 {@code n_ctx} 를 넘기는 것은 프롬프트가 아니라 그 예약이다(§6.26 — {@code
     * AnswerService.MAX_EVAL_OUTPUT_TOKENS} 와 같은 이유).
     */
    static final int MAX_OUTPUT_TOKENS = 256;

    /**
     * 재작성 결과와 그 호출의 토큰 사용량.
     *
     * <p>토큰을 함께 돌려주는 이유는 이 호출이 그래프 <b>바깥</b>에서(초기 상태를 만들 때) 일어나기
     * 때문이다 — {@code AgentState} 를 아직 들고 있는 쪽이 없어 {@code accumulateTokens} 를 스스로
     * 부를 수 없다. 호출부가 초기 상태에 실어 주면 이 호출도 턴의 LLM 호출 수·토큰에 정직하게
     * 잡힌다({@code ClassifierService.classifyOnly()} 는 그 값을 버려서 호출 수가 하나 덜 세어진다 —
     * 같은 실수를 반복하지 않는다).
     */
    public record Condensed(String searchQuestion, int inputTokens, int outputTokens) {}

    private final LlmRouter llmRouter;
    private final MemoryService memoryService;
    private final MessageSource messageSource;
    private final AppProperties props;

    public QuestionCondenser(LlmRouter llmRouter, MemoryService memoryService,
                             MessageSource messageSource, AppProperties props) {
        this.llmRouter = llmRouter;
        this.memoryService = memoryService;
        this.messageSource = messageSource;
        this.props = props;
    }

    /**
     * 이 질문이 독립화 대상인가 — <b>이력을 읽기 전에</b> 답할 수 있어야 한다.
     *
     * <p>호출부({@code AgentService}/{@code StreamingAgentService})가 이력 로딩과 분류를 병렬로
     * 돌리는데, 분류는 독립화된 질문을 기다려야 한다. 게이트가 순수하면 <b>긴 질문에서는 분류가
     * 즉시 출발</b>해 오늘의 병렬성이 그대로 유지되고, 실제로 직렬화되는 것은 독립화가 필요한
     * 짧은 질문뿐이다.
     *
     * <p>스위치를 {@code search-multiquery-enabled} 와 공유한다. 둘 다 "검색 전에 질의를 LLM 으로
     * 한 번 손보는" 같은 성질의 호출이고, 길이 임계값도 이미 그 쌍의 경계다 — 여기가
     * {@code shouldExpand()} 의 여집합이라 한 턴에 둘 중 하나만 돈다. 별도 키를 만드는 판단은
     * 오탐이 실제로 잦은지 본 뒤로 미뤘다(§10.12 열린 항목 (a)).
     */
    public boolean gateOpen(String question) {
        if (question == null || question.isBlank()) return false;
        if (!props.searchMultiqueryEnabledSafe()) return false;
        return question.strip().length() < props.searchMultiqueryMinLengthSafe();
    }

    /**
     * 짧은 후속 질문 → 자립적인 질문. {@link #gateOpen} 이 열렸을 때만 부른다.
     *
     * @return 재작성된 질문. 재료가 없거나 · 모델이 원문을 그대로 돌려줬거나 · 호출/파싱이
     *         실패하면 {@link Optional#empty()} — 그 경우 호출부는 <b>원문으로</b> 검색한다
     */
    public Optional<Condensed> condense(String userId, String threadId, String question, Locale locale) {
        String material = buildMaterial(userId, threadId);
        if (material.isBlank()) {
            // 첫 질문이다 — 기댈 맥락이 없으므로 짧다는 것이 곧 후속 질문이라는 뜻이 아니다.
            return Optional.empty();
        }
        try {
            String systemPrompt = messageSource.getMessage("prompt.retrieval.condense", null, locale)
                    .replace("{history}", material)
                    .replace("{query}", PromptInjectionGuard.wrap(question));
            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                    TaskType.MICRO_TEXT, RoutingMode.COST_FIRST,
                    model -> model.call(new Prompt(
                            List.of(new SystemMessage(systemPrompt), new UserMessage(question)),
                            options())));
            String rewritten = parse(result.text());
            if (rewritten == null || rewritten.equalsIgnoreCase(question.strip())) {
                // 모델이 "이미 자립적이다"라고 판단한 정상 경로다 — 이 갈래가 실제로 자주 나와야
                // 짧지만 자립적인 질문(예: SSE 타임아웃?)이 이력에 오염되지 않는다.
                log.debug("[CONDENSE] 재작성 없음(자립적이거나 파싱 실패) thread={} question={}",
                        threadId, question);
                return Optional.empty();
            }
            log.info("[CONDENSE] 짧은 후속 질문 독립화 thread={} 원문=[{}] 검색어=[{}]",
                    threadId, question, rewritten);
            return Optional.of(new Condensed(rewritten, result.inputTokens(), result.outputTokens()));
        } catch (Exception e) {
            // 검색을 멈출 이유가 아니다 — 원문으로 검색한다.
            log.warn("[CONDENSE] 독립화 실패 — 원문으로 검색한다 thread={}: {}", threadId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 재료 = 최근 {@value #MATERIAL_TURNS} 턴의 <b>질문</b>, 오래된 것부터.
     *
     * <p>싫어요 턴을 걸러내지 않는다. 그 필터는 "이 답변을 다음 맥락에 넣지 말라"는 뜻이고
     * ({@code ConversationSummarizerService.dedupeTurns}), 여기서 쓰는 것은 답변이 아니라
     * 사용자가 직접 쓴 질문이다 — 답변이 나빴다는 것이 그 질문에 있던 대명사의 지시 대상까지
     * 없애지는 않는다.
     */
    private String buildMaterial(String userId, String threadId) {
        List<MemoryRepository.Turn> turns;
        try {
            turns = memoryService.getRecentTurns(userId, threadId);
        } catch (Exception e) {
            log.debug("[CONDENSE] 이전 질문 조회 실패 thread={}: {}", threadId, e.getMessage());
            return "";
        }
        if (turns == null || turns.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (int i = Math.max(0, turns.size() - MATERIAL_TURNS); i < turns.size(); i++) {
            String q = turns.get(i).question();
            if (q == null || q.isBlank()) continue;
            String one = q.strip().replaceAll("\\s+", " ");
            if (one.length() > MAX_MATERIAL_QUESTION_CHARS) {
                one = one.substring(0, MAX_MATERIAL_QUESTION_CHARS) + "…";
            }
            lines.add("- " + one);
        }
        return String.join("\n", lines);
    }

    /**
     * 한 줄만 남긴다. 모델이 머리말이나 따옴표를 붙이는 경우가 흔해서, 첫 번째 비어 있지 않은 줄을
     * 취한 뒤 감싼 따옴표를 벗긴다. 길이를 넘기면 <b>잘라 쓰지 않고 버린다</b>
     * ({@link #MAX_CONDENSED_CHARS} 참고).
     */
    static String parse(String response) {
        if (response == null || response.isBlank()) return null;
        String line = response.lines()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("");
        // 감싼 따옴표만 벗긴다 — 안쪽 따옴표는 질문의 일부일 수 있다.
        if (line.length() >= 2
                && ((line.startsWith("\"") && line.endsWith("\""))
                 || (line.startsWith("'") && line.endsWith("'"))
                 || (line.startsWith("“") && line.endsWith("”")))) {
            line = line.substring(1, line.length() - 1).strip();
        }
        if (line.isEmpty() || line.length() > MAX_CONDENSED_CHARS) return null;
        return line;
    }

    /** 분류기와 같은 일반 temperature — 재작성은 창의 작업이 아니다. 핫이라 매 호출 다시 읽는다. */
    private OpenAiChatOptions options() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature());
        int configured = props.llmSafe().maxTokens();
        // 0 이하 = "프로바이더 기본값 유지" (AnswerService.evalOptions 와 같은 규약).
        if (configured > 0) builder.maxTokens(Math.min(configured, MAX_OUTPUT_TOKENS));
        return builder.build();
    }
}
