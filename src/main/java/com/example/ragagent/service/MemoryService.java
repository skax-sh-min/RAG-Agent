package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.model.VerificationSnapshot;
import com.example.ragagent.repository.MemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Multi-turn conversation memory keyed by userId + thread_id.
 * Equivalent to LangGraph MemorySaver in the Python version.
 * Delegates storage to MemoryRepository (default: SQLite).
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /**
     * Not injected: this class is constructed directly in several tests, and widening its
     * constructor for a diagnostic serializer would ripple through all of them. A default
     * ObjectMapper is enough here — {@link SourceRef} is a plain record with explicit
     * {@code @JsonProperty} names and needs no app-specific configuration — and it is thread-safe.
     */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AppProperties props;
    private final MemoryRepository repository;
    private final LlmRouter llmRouter;
    private final ProviderContextWindows contextWindows;

    // Single source of truth for "LLM max tokens" (app.llm.max-tokens / LLM_MAX_TOKENS, default
    // 6000) — used to read the separate, dead spring.ai.openai.chat.options.max-tokens property
    // (default 8000), which config'd nothing (Spring AI's autoconfigured ChatModel bean is skipped
    // since LlmConfig.primaryChatModel() already satisfies its @ConditionalOnMissingBean).
    @org.springframework.beans.factory.annotation.Autowired
    public MemoryService(MemoryRepository repository, AppProperties props,
                         LlmRouter llmRouter, ProviderContextWindows contextWindows) {
        this.props = props;
        this.repository = repository;
        this.llmRouter = llmRouter;
        this.contextWindows = contextWindows;
    }

    /** 이력 예산의 창 인지 부분을 쓰지 않는 호출부(테스트)를 위한 축약 — 예산은 고정값으로 떨어진다. */
    public MemoryService(MemoryRepository repository, AppProperties props) {
        this(repository, props, null, null);
    }

    public String getHistory(String userId, String threadId) {
        return repository.getHistory(userId, threadId, maxConversationChars());
    }

    /**
     * §10.13 — 예산과 "지금 묻는 턴이 Direct 인가"를 호출부가 정해 주는 형태. 요약 경로
     * ({@code ConversationSummarizerService.buildContext})와 <b>같은 두 값</b>을 받아야 두 경로가
     * 캐시 유무에 따라 다른 맥락을 만들지 않는다.
     */
    public String getHistory(String userId, String threadId, int maxChars, boolean askingDirect) {
        return repository.getHistory(userId, threadId, maxChars, askingDirect);
    }

    /**
     * Char budget applied to conversation history (LLM_MAX_TOKENS × 0.5, floor 1,000).
     * Exposed so the summary path ({@code ConversationSummarizerService.buildContext()}) can
     * respect the exact same ceiling as this fallback path — single source of truth (§6.11).
     *
     * <p><b>매 호출 재계산한다</b> — 다만 이것은 버그 수정이 아니라 방어다. {@code app.llm.max-tokens}
     * 는 오늘 기준 <b>핫 편집 대상이 아니다</b>({@code SettingsKeys.HOT_EDITABLE} 에 없고
     * {@code llmSafe()} 에도 오버라이드 조회가 없다 — 프로바이더 빈 생성 시점에 구워지므로 재기동해야
     * 바뀐다). 그래서 생성자에서 굳혀도 값이 낡지는 않았다.
     *
     * <p>그럼에도 매번 읽는 이유는 이 값을 파생시켜 쓰는 곳이 늘었기 때문이다 — 컨텍스트 입력 예산
     * ({@code AnswerService.fitToBudget()})과 인덱싱 출력 상한({@code IndexingOutputCap})이 같은
     * {@code max-tokens} 에서 나온다. 그중 하나만 생성자에 굳어 있으면, 나중에 이 값이 핫 편집으로
     * 열리는 순간 <b>둘이 서로 다른 상한을 믿는 상태</b>가 조용히 만들어진다. 계산 비용이 없으므로
     * 그 가능성을 미리 닫아 둔다.
     */
    public int maxConversationChars() {
        return Math.max(1_000, props.llmSafe().maxTokens() / 2);
    }

    /**
     * §10.13 — <b>이 턴의</b> 이력 예산. 규칙은 "Direct 라서"가 아니라 "문서 자리가 비어서"다:
     * <pre>{@code   이력 상한 = 입력 예산 − 문서가 차지할 자리}</pre>
     *
     * <p>Direct 답변의 프롬프트에는 {@code [검색된 문서]} 블록이 <b>통째로 없다</b>. 기본 설정에서
     * 그 자리는 {@code topK 10 × chunk-size 1,500} ≈ 15,000자인데, 이력 상한은 모드와 무관하게
     * 5,000자로 고정이었다 — 창의 큰 부분이 놀고, 정작 이력만으로 답해야 하는 모드가 이력을 가장
     * 적게 받았다.
     *
     * <p><b>RAG 턴에는 적용하지 않는다</b>(오늘의 고정값 그대로). 이력은 검색보다 <b>먼저</b>
     * 로딩되므로 그 시점에 문서가 몇 개 올지 모르고, Direct 만 검색이 아예 돌지 않아 0 이 확정이다.
     * RAG 쪽 불확실성은 검색이 끝난 뒤 도는 {@code AnswerService.fitToBudget()} 이 이미 담당한다.
     *
     * <p><b>{@code meta} 분류는 여기서 Direct 로 세지 않는다</b> — RAG 로 물어도 분류기가
     * {@code meta} 로 판정하면 검색을 건너뛰지만, 그 판정은 이력 로딩과 <b>병렬로</b> 돌아 아직
     * 나오지 않았다. 아는 것만 쓴다.
     *
     * @param streaming 이 턴이 스트리밍으로 답하는가 — 출력 예약이 달라진다
     *                  ({@code AnswerService.outputReservation})
     */
    public int maxConversationChars(boolean askingDirect, ResponseMode mode,
                                    RoutingMode routingMode, boolean streaming, String question) {
        int fallback = maxConversationChars();
        if (!askingDirect || llmRouter == null || contextWindows == null) return fallback;
        int window = contextWindows.tokensOrZero(llmRouter.findProviderName(TaskType.TEXT, routingMode));
        return HistoryPolicy.budgetChars(window,
                AnswerService.outputReservation(mode, streaming, props.llmSafe().maxTokens()),
                0,   // Direct — 검색이 돌지 않으므로 문서가 가져갈 자리가 없다
                TokenEstimator.estimate(question), fallback);
    }

    /** Returns the generated turn id (conversation_turns.id). {@code selectedTags} is the
     *  comma-joined search scope this question was asked under — see
     *  {@link MemoryRepository#addTurn}. */
    public long addTurn(String userId, String threadId, String question, String answer,
                        String askedAt, int inputTokens, int outputTokens,
                        int elapsedMs, String provider, int llmCalls, String responseMode,
                        String selectedTags) {
        return repository.addTurn(userId, threadId, question, answer,
                askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls, responseMode,
                selectedTags);
    }

        public long addTurn(String userId, String threadId, String question, String answer,
                String askedAt, int inputTokens, int outputTokens,
                int elapsedMs, String provider, int llmCalls, String responseMode,
                String selectedTags, boolean directMode) {
        return repository.addTurn(userId, threadId, question, answer,
            askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls, responseMode,
            selectedTags, directMode);
        }

    public long addTurn(String userId, String threadId, String question, String answer,
                        String askedAt, int inputTokens, int outputTokens,
                        int elapsedMs, String provider, int llmCalls, String responseMode,
                        String selectedTags, Long reusedFromTurnId) {
        return repository.addTurn(userId, threadId, question, answer,
                askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls, responseMode,
                selectedTags, reusedFromTurnId);
    }

        public long addTurn(String userId, String threadId, String question, String answer,
                String askedAt, int inputTokens, int outputTokens,
                int elapsedMs, String provider, int llmCalls, String responseMode,
                String selectedTags, boolean directMode, Long reusedFromTurnId) {
        return repository.addTurn(userId, threadId, question, answer,
            askedAt, inputTokens, outputTokens, elapsedMs, provider, llmCalls, responseMode,
            selectedTags, directMode, reusedFromTurnId);
        }

    public void clearHistory(String userId, String threadId) {
        repository.clearHistory(userId, threadId);
    }


    /** Deletes one turn (question + answer) and its per-turn rows. See {@link MemoryRepository#deleteTurn}. */
    public boolean deleteTurn(String userId, String threadId, long turnId) {
        return repository.deleteTurn(userId, threadId, turnId);
    }

    public void saveTurnImageRefs(long turnId, String userId, String threadId, List<String> imageRefs) {
        repository.saveTurnImageRefs(turnId, userId, threadId, imageRefs);
    }

    /**
     * 3단계 — persists the turn's retrieval diagnostics (유사도·검색기여도·축별 순위·응답 참여도)
     * so tuning can be reviewed after the fact instead of only in the moment.
     *
     * <p>Sources with no numbers at all are skipped, and a turn left with nothing to say writes
     * nothing — the {@code /admin} panel lists only rows that actually carry data, so a DB-reuse
     * or meta turn never shows up as an empty row.
     *
     * <p><b>Never throws.</b> This runs after the answer has already been produced; a
     * serialization or write failure must cost the diagnostic, not the turn.
     */
    public void saveRetrievalMetrics(long turnId, List<SourceRef> sources) {
        if (sources == null || sources.isEmpty()) return;
        try {
            List<SourceRef> withMetrics = sources.stream()
                    .filter(s -> s.similarity() != null || s.retrievalShare() != null
                              || s.axisRanks() != null || s.answerShare() != null)
                    .toList();
            if (withMetrics.isEmpty()) return;
            repository.saveRetrievalMetrics(turnId, objectMapper.writeValueAsString(withMetrics));
        } catch (Exception e) {
            log.warn("[METRICS] 검색 진단 수치 저장 실패 turnId={} — 무시하고 진행: {}", turnId, e.toString());
        }
    }

    /**
     * 이 턴의 검증 결과를 저장한다 — 대화 기록의 배지가 새로고침 후에도 남으려면 저장돼 있어야 한다
     * (PLAN §6.24 Step 4-b). 담을 것이 하나도 없는 턴(검증 미실행 + 안내 없음)은 저장하지 않아
     * 컬럼이 {@code NULL} 로 남는다 — 그게 곧 "배지 없음"이다.
     *
     * <p>{@code saveRetrievalMetrics} 와 같이 <b>실패해도 삼킨다</b>. 검증 결과 표시는 진단·안내
     * 값이라, 직렬화 사고가 이미 완성된 답변의 저장을 되돌려서는 안 된다.
     */
    public void saveVerification(long turnId, VerificationSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;
        try {
            repository.saveVerification(turnId, objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.warn("[VERIFY] 검증 결과 저장 실패 turnId={} — 무시하고 진행: {}", turnId, e.toString());
        }
    }

    /**
     * 대화 기록 화면이 배지를 되살리는 데 쓰는 조회.
     *
     * <p><b>파싱은 명시적으로 관대하다</b> — 이 행들은 자신을 쓴 코드보다 오래 살아남으므로,
     * {@code VerificationSnapshot} 에 필드가 하나 추가되면 과거 기록 전체가 안 읽히게 된다
     * ({@code RetrievalMetricsService} 와 같은 규약). 깨진 값은 그 턴만 건너뛴다 — 화면 전체를
     * 죽이지 않는다.
     */
    public Map<Long, VerificationSnapshot> getVerifications(List<Long> turnIds) {
        Map<Long, VerificationSnapshot> out = new LinkedHashMap<>();
        repository.findVerificationsByTurnIds(turnIds).forEach((turnId, json) -> {
            try {
                out.put(turnId, lenientMapper().readValue(json, VerificationSnapshot.class));
            } catch (Exception e) {
                log.debug("[VERIFY] 검증 결과 파싱 실패 turnId={} — 이 턴만 건너뜀: {}", turnId, e.toString());
            }
        });
        return out;
    }

    /** 관대한 파서 — 위 javadoc 의 이유로 알 수 없는 필드를 무시한다. */
    private ObjectMapper lenientMapper() {
        return objectMapper.copy()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public List<MemoryRepository.MetricsRow> findRecentRetrievalMetrics(
            String userId, String threadId, int offset, int limit) {
        return repository.findRecentRetrievalMetrics(userId, threadId, offset, limit);
    }

    /** §6.25 — owners that have diagnostics, for the panel's user filter. */
    public List<String> distinctRetrievalMetricsUserIds() {
        return repository.distinctRetrievalMetricsUserIds();
    }

    public int countRetrievalMetrics(String userId, String threadId) {
        return repository.countRetrievalMetrics(userId, threadId);
    }

    public List<MemoryRepository.MetricsRow> findRecentRetrievalMetrics(int offset, int limit) {
        return repository.findRecentRetrievalMetrics(offset, limit);
    }

    public int countRetrievalMetrics() {
        return repository.countRetrievalMetrics();
    }

    public Map<Long, String> findRetrievalMetricsByTurnIds(List<Long> turnIds) {
        return repository.findRetrievalMetricsByTurnIds(turnIds);
    }

    public Map<Long, List<String>> getTurnImageRefs(String userId, String threadId) {
        return repository.getTurnImageRefs(userId, threadId);
    }

    public void excludeTurnImageRef(String userId, String threadId, long turnId, String imageRef) {
        repository.excludeTurnImageRef(userId, threadId, turnId, imageRef);
    }

    public List<MemoryRepository.Turn> getTurns(String userId, String threadId) {
        return repository.getTurns(userId, threadId);
    }

    /**
     * Same as {@link #getTurns}, capped to the most recent {@code app.memory.fetch-limit-turns}
     * turns. Use this instead of {@link #getTurns} for anything that feeds the result into an LLM
     * call (e.g. {@code ConversationSummarizerService}) — {@link #getTurns} is unbounded and only
     * safe for UI-only uses (like restoring a thread's full message history on page load).
     */
    public List<MemoryRepository.Turn> getRecentTurns(String userId, String threadId) {
        return repository.getRecentTurns(userId, threadId);
    }

    /** Single turn lookup — used by {@link CuratedQaService} to snapshot question/answer on like. */
    public Optional<MemoryRepository.Turn> getTurn(String userId, String threadId, long turnId) {
        return repository.getTurn(userId, threadId, turnId);
    }

    public Optional<MemoryRepository.FeedbackRow> getFeedback(String userId, String threadId, long turnId) {
        return repository.getFeedback(userId, threadId, turnId);
    }

    public void updateFeedback(String userId, String threadId, long turnId, String feedback) {
        repository.updateFeedback(userId, threadId, turnId, feedback);
    }
}
