package com.example.ragagent.repository;

import com.example.ragagent.model.ResponseMode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MemoryRepository {
    String getHistory(String userId, String threadId, int maxChars);

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
                String feedback, String responseMode, String selectedTags) {

        /**
         * 이 턴에 좋아요가 <b>실제로 무언가를 하는가</b> — 대화 기록 렌더러가 읽는 값이다.
         *
         * <p>LIKE의 유일한 소비자가 큐레이션 승격이라, {@code allowsCuration()}이 false인 모드
         * (S·C)에서는 {@code CuratedQaService.onLike()}가 행조차 만들지 않고 즉시 돌아온다. 그런데
         * 피드백 값 자체는 저장되므로 버튼은 눌린 채 남고, 사용자는 공유 지식에 기여했다고 믿게
         * 된다 — 화면 어디에도 아무 일도 없었다는 신호가 없었다.
         *
         * <p>레코드 컴포넌트가 아니라 파생 메서드인 것은 {@code SourceRef.staleBadge()}와 같은
         * 이유다: 저장된 사실이 아니라 저장된 값에서 계산되는 표시 규칙이고, 규칙의 출처는
         * {@code ResponseMode} 하나여야 한다. 템플릿이 {@code ${turn.curatable}}로 읽으므로
         * SpEL 접근 가능 여부를 {@code TurnCuratableTest}가 고정한다.
         */
        public boolean curatable() {
            return ResponseMode.parse(responseMode).allowsCuration();
        }

        /**
         * 질문 앞에 붙일 응답 모드 표기(`S`/`N`/`C`) — 대화 기록 렌더러가 읽는다.
         *
         * <p>저장된 원본 문자열을 그대로 쓰지 않는 이유는 컬럼이 nullable 이고 구 {@code "M"}/{@code "L"}
         * 값이 그대로 남아 있기 때문이다. {@link ResponseMode#parse}를 거치면 그 값들이 실제 동작과
         * 같은 모드(N)로 읽히므로, 화면에 뜨는 표기와 그 턴이 실제로 어떻게 답했는지가 어긋나지 않는다.
         */
        public String responseModeLabel() {
            return ResponseMode.parse(responseMode).name();
        }

        /**
         * 좋아요가 막힌 사유의 메시지 키 — {@link #curatable()}이 true면 {@code null}.
         *
         * <p>불린만으로는 툴팁을 쓸 수 없다. 사유가 모드마다 다르기 때문이다 — S는 임베딩할 본문이
         * 남지 않아서, C는 모델 생성물이 다음 턴의 "문서"가 되는 것을 막기 위해서다
         * ({@code ResponseMode.curationBlockedMessageKey}).
         */
        public String curationBlockedMessageKey() {
            return ResponseMode.parse(responseMode).curationBlockedMessageKey();
        }
    }

    /** Wraps a nullable feedback value so "found with NULL feedback" is distinguishable from "not found". */
    record FeedbackRow(String feedback) {}
}
