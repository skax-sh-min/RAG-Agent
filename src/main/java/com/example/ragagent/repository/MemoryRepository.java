package com.example.ragagent.repository;

import com.example.ragagent.model.ResponseMode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MemoryRepository {

    /**
     * 요약 캐시가 없을 때 쓰는 원본 이력.
     *
     * <p>{@code askingDirect} 는 <b>지금 묻는 턴</b>이 Direct 인가다 (§10.13) — 그 턴에는
     * {@code [검색된 문서]} 블록이 없어 이력에 줄 자리가 넓고, 이전 턴의 답변도 다르게 렌더된다
     * ({@code HistoryPolicy.renderAnswer}). 요약 경로
     * ({@code ConversationSummarizerService.buildContext})가 같은 값을 같은 규칙으로 쓰므로,
     * 두 경로가 캐시 유무에 따라 다른 맥락을 만들지 않는다.
     */
    String getHistory(String userId, String threadId, int maxChars, boolean askingDirect);

    /** RAG 턴 기준 — §10.13 이전과 동작이 같다. */
    default String getHistory(String userId, String threadId, int maxChars) {
        return getHistory(userId, threadId, maxChars, false);
    }

    /** Returns the generated turn id (conversation_turns.id). {@code responseMode}: the turn's
     *  S/N response mode ({@code ResponseMode.name()}), null-safe (nullable column). Legacy
     *  {@code "M"}/{@code "L"} rows parse back to {@code N} (see ResponseMode.parse).
     *  {@code selectedTags}: the search-scope tags this question was asked under (comma-joined,
     *  null/blank = 전체 검색) — read back by {@code CuratedQaService.onLike} so a 👍-promoted
     *  answer inherits the scope it was actually answered in. */
    long addTurn(String userId, String threadId, String question, String answer,
                 String askedAt, int inputTokens, int outputTokens,
                 int elapsedMs, String provider, int llmCalls, String responseMode,
             String selectedTags, boolean directMode, Long reusedFromTurnId);

        default long addTurn(String userId, String threadId, String question, String answer,
             String askedAt, int inputTokens, int outputTokens,
             int elapsedMs, String provider, int llmCalls, String responseMode,
             String selectedTags, Long reusedFromTurnId) {
        return addTurn(userId, threadId, question, answer, askedAt, inputTokens, outputTokens,
            elapsedMs, provider, llmCalls, responseMode, selectedTags, false, reusedFromTurnId);
        }

    default long addTurn(String userId, String threadId, String question, String answer,
                 String askedAt, int inputTokens, int outputTokens,
                 int elapsedMs, String provider, int llmCalls, String responseMode,
                 String selectedTags) {
        return addTurn(userId, threadId, question, answer, askedAt, inputTokens, outputTokens,
            elapsedMs, provider, llmCalls, responseMode, selectedTags, false, null);
    }

        default long addTurn(String userId, String threadId, String question, String answer,
             String askedAt, int inputTokens, int outputTokens,
             int elapsedMs, String provider, int llmCalls, String responseMode,
             String selectedTags, boolean directMode) {
        return addTurn(userId, threadId, question, answer, askedAt, inputTokens, outputTokens,
            elapsedMs, provider, llmCalls, responseMode, selectedTags, directMode, null);
        }

    void clearHistory(String userId, String threadId);

    /**
     * Deletes a single turn and everything keyed to it, scoped by {@code userId} so a caller
     * can never reach another user's turn. Same table set as {@link #clearHistory} (turn_source_ref,
     * turn_image_ref, conversation_turns), narrowed to one turn.
     *
     * <p>A later turn that reused this one keeps a now-dangling {@code reused_from_turn_id}; every
     * read of that column is a LEFT JOIN that already falls back to "참조 원문 삭제됨", so the
     * pointer is deliberately left alone rather than cascaded.
     *
     * @return true when a turn row was actually removed (false = wrong user, thread, or already gone)
     */
    boolean deleteTurn(String userId, String threadId, long turnId);


    /** Persist image refs shown with a turn (answer thumbnails in chat UI). */
    void saveTurnImageRefs(long turnId, String userId, String threadId, List<String> imageRefs);

    /** Active image refs by turn id, for restoring chat history. */
    Map<Long, List<String>> getTurnImageRefs(String userId, String threadId);

    /** Hides one image from a turn in this conversation (soft delete). */
    void excludeTurnImageRef(String userId, String threadId, long turnId, String imageRef);

    /** Returns all turns for the thread in chronological order (oldest first). */
    List<Turn> getTurns(String userId, String threadId);

    /**
     * Same as {@link #getTurns}, but capped to the most recent {@code app.memory.fetch-limit-turns}
     * (the same bound {@link #getHistory} applies) — for callers that feed turns into an LLM call
     * (e.g. summarization) and must not let cost grow unbounded with conversation length. Still
     * returned in chronological order (oldest first).
     */
    List<Turn> getRecentTurns(String userId, String threadId);

    /** Current feedback value for ownership check + audit "from". Empty = turn not found / not owned. */
    Optional<FeedbackRow> getFeedback(String userId, String threadId, long turnId);

    /** Single turn lookup (question/answer + metadata) for callers that don't need the whole
     * thread — e.g. curated-Q&A promotion on like. Empty = turn not found / not owned. */
    Optional<Turn> getTurn(String userId, String threadId, long turnId);

    /** {@code feedback}: {@code "LIKE" | "DISLIKE" | null}. No-op if the turn isn't owned by userId/threadId. */
    void updateFeedback(String userId, String threadId, long turnId, String feedback);

    /**
     * 3단계 — stores the turn's per-source retrieval diagnostics as a JSON array (see
     * {@code RetrievalMetricsView}). Written right after the turn insert, in the same
     * post-insert slot as {@link #saveTurnImageRefs}. Diagnostic only: a failure here must never
     * cost the user their answer, so callers swallow, and {@code null}/blank is a no-op.
     */
    void saveRetrievalMetrics(long turnId, String metricsJson);

    /**
     * Recent turns that actually carry diagnostics, newest first — backs the {@code /admin}
     * tuning panel. Deliberately <b>not</b> user-scoped: it is an operator view of how retrieval
     * is behaving across the deployment, gated by {@code /admin/**}'s ROLE_ADMIN like every other
     * panel there.
     */
    List<MetricsRow> findRecentRetrievalMetrics(String userId, String threadId,
                                                int offset, int limit);

    /** Unfiltered — the pre-§6.25 behaviour, preserved as the one-line delegation so every
     *  existing caller keeps producing exactly the same list, order and count. */
    default List<MetricsRow> findRecentRetrievalMetrics(int offset, int limit) {
        return findRecentRetrievalMetrics(null, null, offset, limit);
    }

    /**
     * Total turns carrying diagnostics — for the panel's pagination and its "전체 N턴" badge.
     *
     * <p>Takes the <b>same two filters</b> as {@link #findRecentRetrievalMetrics}: filter the list
     * without filtering the count and the badge starts describing a set the operator isn't looking
     * at. The pagination buttons are size-based, so nothing would visibly break — which is exactly
     * why the mismatch would go unnoticed.
     */
    int countRetrievalMetrics(String userId, String threadId);

    default int countRetrievalMetrics() {
        return countRetrievalMetrics(null, null);
    }

    /** Owners that actually have diagnostics — the panel's user dropdown. Deliberately a
     *  different set from {@code ThreadAdminRepository.distinctUserIds()} (all conversation
     *  owners): offering a user with no diagnostics yields an empty list the moment it's picked. */
    List<String> distinctRetrievalMetricsUserIds();

    /**
     * Raw diagnostics blobs for the given turns, keyed by turn id — backs restoring the numbers
     * when a chat thread is reopened. Turns without diagnostics are simply absent from the map.
     */
    Map<Long, String> findRetrievalMetricsByTurnIds(List<Long> turnIds);

    /**
     * 한 턴의 검증 결과({@code VerificationSnapshot}) JSON 을 저장한다 — {@code saveRetrievalMetrics}
     * 와 같은 사후 UPDATE 다. {@code addTurn} 파라미터로 밀어 넣지 않는 이유는 그 시그니처가 이미
     * 13개이고, 검증 결과는 답변 저장 이후에야(그리고 없을 수도 있게) 확정되는 값이기 때문이다.
     */
    void saveVerification(long turnId, String verificationJson);

    /**
     * 대화 기록 화면이 배지를 되살리는 데 쓰는 조회 — 값이 있는 턴만 담긴다
     * ({@code findRetrievalMetricsByTurnIds} 와 같은 형태). {@code Turn} 레코드에 필드를 더하지
     * 않은 이유는 그 레코드가 요약·컨텍스트 조립 경로에서도 쓰여서, 표시 전용 값을 실으면 세 개의
     * SELECT 와 무관한 소비자들이 함께 움직이기 때문이다.
     */
    Map<Long, String> findVerificationsByTurnIds(List<Long> turnIds);

    /**
     * One row of the {@code /admin} diagnostics panel; {@code metricsJson} is parsed by the service.
     *
     * <p>{@code userId}/{@code threadId}/{@code threadTitle} (§6.25) are what let a diagnostics row
     * say <em>whose</em> question it was and which conversation it came from — the panel is a flat
     * cross-user list, and without them a row cannot be traced back to anything.
     * {@code threadTitle} is null for a turn whose {@code thread_meta} row is gone.
     */
    record MetricsRow(long turnId, String askedAt, String question, String responseMode,
                      String provider, String metricsJson,
                      String userId, String threadId, String threadTitle) {

        /** Pre-§6.25 shape — used where the extra three are genuinely unknown, e.g.
         *  {@code RetrievalMetricsService.enrich()} re-parsing a single stored blob. */
        public MetricsRow(long turnId, String askedAt, String question, String responseMode,
                          String provider, String metricsJson) {
            this(turnId, askedAt, question, responseMode, provider, metricsJson, null, null, null);
        }
    }

    record Turn(long id, String question, String answer,
                String askedAt, String answeredAt,
                int inputTokens, int outputTokens,
                int elapsedMs, String provider, int llmCalls,
                String feedback, String responseMode, String selectedTags,
                boolean directMode) {

        /**
         * 이 턴에서 좋아요가 <b>지식 제안을 열어 주는가</b> — 대화 기록 렌더러가 읽는 값이다
         * (§10.11).
         *
         * <p>{@code false}면 좋아요 버튼이 비활성으로 뜨고 사유가 툴팁에 붙는다
         * ({@link #submissionBlockedMessageKey()}). 그 표시가 없으면 사용자는 버튼을 눌러 두고
         * 공유 지식에 기여했다고 믿게 되는데 실제로는 아무 일도 일어나지 않는다.
         *
         * <p>레코드 컴포넌트가 아니라 파생 메서드인 것은 {@code SourceRef.staleBadge()}와 같은
         * 이유다: 저장된 사실이 아니라 저장된 값에서 계산되는 표시 규칙이고, 규칙의 출처는
         * {@code ResponseMode} 하나여야 한다. 템플릿이 {@code ${turn.proposable}}로 읽으므로
         * SpEL 접근 가능 여부를 {@code TurnProposableTest}가 고정한다.
         */
        public boolean proposable() {
            return ResponseMode.parse(responseMode).allowsSubmission();
        }

        /**
         * 질문 앞에 붙일 표기 — <b>두 글자</b>다. 앞이 검색 축({@code R} RAG / {@code D} Direct),
         * 뒤가 답변의 성격({@code S}/{@code N}/{@code C}).
         *
         * <p><b>왜 두 축을 다 적는가.</b> 예전에는 성격만 적어서(`[N]`) 같은 질문을 문서로 물었는지
         * 모델 지식으로 물었는지가 화면 어디에도 없었다. 두 축은 직교하므로 — 사용자는 "N 대신
         * Direct"를 고르는 것이 아니라 "Direct 이면서 N"을 고른다 — 한 글자로 뭉치면 그 사실이
         * 표기에서 사라지고, 특히 {@code DS}와 {@code DN}은 프롬프트도 후처리도 다른 별개의 조합이다
         * ({@code prompt.direct.system.s} 는 {@code ## 요약} 한 섹션 1,500자를 요구하고
         * {@code SummaryOnlyGuard} 가 그것을 강제한다).
         *
         * <p>{@code C} 는 RAG 전용이라({@code allowsDirect()=false}) {@code RC} 의 {@code R} 은 정보를
         * 나르지 않지만 그대로 둔다 — 두 글자들 사이에 혼자 {@code [C]} 가 있으면 렌더링 오류로 읽힌다.
         *
         * <p>저장된 원본 문자열을 그대로 쓰지 않는 이유는 컬럼이 nullable 이고 구 {@code "M"}/{@code "L"}
         * 값이 그대로 남아 있기 때문이다. {@link ResponseMode#parse}를 거치면 그 값들이 실제 동작과
         * 같은 모드(N)로 읽히므로, 화면에 뜨는 표기와 그 턴이 실제로 어떻게 답했는지가 어긋나지 않는다.
         *
         * <p><b>주의</b>: {@code direct_mode} 는 "무엇을 요청했나"이지 "검색이 실제로 돌았나"가 아니다.
         * RAG 로 물어도 분류기가 {@code meta} 로 판정하면 검색을 건너뛰므로, 출처 없는 {@code R} 턴이
         * 있을 수 있다. 그리고 컬럼이 생기기 전 턴은 전부 {@code R} 로 읽힌다({@code DEFAULT 0}) —
         * 구분할 방법이 없고, RAG 가 기본이었으므로 안전한 쪽이다.
         */
        public String responseModeLabel() {
            return (directMode ? "D" : "R") + ResponseMode.parse(responseMode).name();
        }

        /**
         * 좋아요가 막힌 사유의 메시지 키 — {@link #proposable()}이 true면 {@code null}.
         *
         * <p>불린만으로는 툴팁을 쓸 수 없다. "안 됩니다"만 말하고 사유를 감추면 버그로 읽힌다
         * ({@code ResponseMode.submissionBlockedMessageKey}).
         */
        public String submissionBlockedMessageKey() {
            return ResponseMode.parse(responseMode).submissionBlockedMessageKey();
        }
    }

    /** Wraps a nullable feedback value so "found with NULL feedback" is distinguishable from "not found". */
    record FeedbackRow(String feedback) {}
}
