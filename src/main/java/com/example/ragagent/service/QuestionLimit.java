package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.ChatForm;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 채팅 질문의 길이 한도 — <b>설정한 상한</b>(기본 {@value PromptInjectionGuard#MAX_CHAT_QUESTION_LEN}자, {@code app.chat.max-question-chars})과
 * <b>이 모델의 창에 실제로 들어가는 양</b> 둘 중 작은 쪽.
 *
 * <p><b>왜 창을 따로 보는가.</b> 프롬프트 축소 사다리({@link PromptLadder})는 문서·이력·직전 답변 요약만 줄이고 <b>현재 질문은 줄이지
 * 못하는 고정비</b>다({@code AnswerService.answerFixedCost}, 검증은 {@code evalFixedCost}). 상한만 올리면 창이 작은 프로바이더에서는 질문 하나가
 * 예산을 넘고, 문서를 최소로 줄여도 컨텍스트 초과가 나서 재시도하다 실패한다. 그래서 대화를 만들기 전에, 이 모델에서 받을 수 있는
 * 길이를 알려 주며 거절한다.
 *
 * <p>창 기준 상한 = 입력 예산({@link PromptBudget#inputBudget}) − 고정 몫({@value #FIXED_ALLOWANCE_TOKENS}토큰: 시스템 프롬프트·검색
 * 경고·섹션 머리말) − 문서 한 개(청크 크기; Direct 는 0). 사다리의 바닥(top-k 의 절반)이 아니라 <b>한 개</b>인 이유는 질문이 길어서 문서가
 * 줄어드는 것을 허용하되(안내 문구가 말한다) 근거가 아예 없는 상태는 막기 위해서다. 검증 호출(질문 + 답변 전문 + 발췌)은 여기서 재지
 * 않는다 — 답변이 아직 없다. 검증 발췌는 늘 첫 문서를 싣고 나머지를 덜어내므로 좁은 창에서의 마지막 방어는 거기다.
 *
 * <p>{@value LongQuestionQuery#SHORT_LIMIT}자 이하는 창을 따지지 않는다 — 오늘과 같은 동작이고, 아주 작은 창에서 평범한 질문이 새로
 * 거절되는 일이 없게 한다. 창을 모르는 프로바이더도 설정 상한만 적용한다("모르면 아무것도 하지 않는다").
 */
@Component
public class QuestionLimit {

    private static final Logger log = LoggerFactory.getLogger(QuestionLimit.class);

    /** 시스템 프롬프트 + 검색 경고 + 섹션 머리말의 넉넉한 몫 — 실제 값은 모드·로케일마다 다르고 보통 이 안쪽이다. */
    static final int FIXED_ALLOWANCE_TOKENS = 2_000;

    /** 설정값의 허용 범위. 하한은 {@link LongQuestionQuery#SHORT_LIMIT} — 그 아래로 내리면 평범한 질문이 막힌다. */
    static final int MIN_CONFIGURED = LongQuestionQuery.SHORT_LIMIT;
    static final int MAX_CONFIGURED = 100_000;

    private final int configuredMax;
    private final LlmRouter llmRouter;
    private final ProviderContextWindows contextWindows;
    private final ThinkingBudget thinkingBudget;
    private final AppProperties props;

    public QuestionLimit(@Value("${app.chat.max-question-chars:" + PromptInjectionGuard.MAX_CHAT_QUESTION_LEN + "}") int configuredMax,
                         LlmRouter llmRouter, ProviderContextWindows contextWindows,
                         ThinkingBudget thinkingBudget, AppProperties props) {
        this.configuredMax = Math.max(MIN_CONFIGURED, Math.min(MAX_CONFIGURED, configuredMax));
        this.llmRouter = llmRouter;
        this.contextWindows = contextWindows;
        this.thinkingBudget = thinkingBudget;
        this.props = props;
    }

    /** 설정한 상한(글자) — 입력창의 글자 수 표시가 읽는다. */
    public int configuredMax() {
        return configuredMax;
    }

    /**
     * 채팅 폼의 질문을 검사한다. 통과하면 질문 그대로, 아니면 사용자에게 보일 문구의 {@link IllegalArgumentException}.
     *
     * @param streaming SSE 경로면 {@code true} — 스트리밍은 {@code maxTokens} 를 보내지 않아 출력 예약이 작다
     */
    public String validate(ChatForm form, boolean streaming) {
        return validate(form.question(), form.isDirectMode(), parseRoutingMode(form.routingMode()),
                form.responseModeOrDefault(), streaming);
    }

    public String validate(String question, boolean directMode, RoutingMode routingMode,
                           ResponseMode responseMode, boolean streaming) {
        PromptInjectionGuard.validate(question, configuredMax);
        if (!LongQuestionQuery.isLong(question)) return question;
        try {
            int allowedTokens = allowedQuestionTokens(directMode, routingMode, responseMode, streaming);
            if (allowedTokens < 0) return question;                   // 창을 모른다 — 설정 상한만
            long needed = TokenEstimator.estimate(question);
            if (needed > allowedTokens) {
                int maxChars = (int) Math.max(0, (long) question.length() * allowedTokens / Math.max(1, needed));
                throw new IllegalArgumentException("질문이 현재 모델의 컨텍스트 창에 비해 너무 깁니다 (이 모델에서는 약 "
                        + maxChars + "자까지, 입력: " + question.length() + "자). 질문을 줄이거나 창이 더 큰 모델을 쓰세요");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            // 한도를 재지 못한다고 질문을 막을 이유는 없다 — 설정 상한은 이미 통과했다.
            log.warn("[QUESTION_LIMIT] 창 기준 한도 계산 실패 — 설정 상한만 적용한다: {}", e.getMessage());
        }
        return question;
    }

    /**
     * 이 턴의 답변을 받을 프로바이더 창 기준으로 질문에 쓸 수 있는 토큰. 창을 모르면 {@code -1}.
     * 어느 프로바이더가 받을지는 라우터가 정하므로 먼저 묻는다({@code AnswerService.budgetFor} 와 같은 가정).
     */
    int allowedQuestionTokens(boolean directMode, RoutingMode routingMode, ResponseMode responseMode, boolean streaming) {
        ThinkingSite site = directMode && responseMode.directThinkingSite() != null
                ? responseMode.directThinkingSite() : responseMode.ragThinkingSite();
        String provider = llmRouter.findProviderName(site.taskType(), site.routingMode(routingMode));
        int window = contextWindows.tokensOrZero(provider);
        if (window <= 0) return -1;
        long budget = new PromptBudget(window, AnswerService.answerReservation(thinkingBudget, site, provider,
                responseMode, streaming, props.llmSafe().maxTokens()).tokens()).inputBudget();
        long documentRoom = directMode ? 0 : props.chunkSizeSafe();
        return (int) Math.max(0, budget - FIXED_ALLOWANCE_TOKENS - documentRoom);
    }

    private static RoutingMode parseRoutingMode(String value) {
        if (value == null || value.isBlank()) return RoutingMode.COST_FIRST;
        try {
            return RoutingMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return RoutingMode.COST_FIRST;
        }
    }
}
