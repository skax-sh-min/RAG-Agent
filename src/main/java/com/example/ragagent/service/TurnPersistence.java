package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.model.TagUtils;
import com.example.ragagent.model.VerificationSnapshot;

import java.util.List;
import java.util.Locale;

/**
 * 한 턴이 끝났을 때 저장되는 것 전부 — 대화 기록, 이미지 참조, 검색 진단, 검증 스냅샷,
 * 재사용 출처, 요약 선계산. <b>여섯 가지가 이 순서로</b> 일어난다.
 *
 * <p><b>왜 클래스로 뺐는가.</b> 채팅에는 진입점이 둘이고({@link AgentService} 블로킹,
 * {@link StreamingAgentService} SSE) 두 곳이 이 여섯 줄을 그대로 복제하고 있었다. 다른 것은
 * 요청 쪽 값을 어디서 읽느냐뿐이다 — 한쪽은 {@code ChatRequest}, 다른 쪽은 폼. 나머지는 전부
 * {@link AgentState} 에서 나온다.
 *
 * <p>복제의 대가는 이미 치렀다: 저장되는 응답 모드를 <b>요청의 것이 아니라 결과의 것</b>으로
 * 바꿔야 했을 때(그래프가 모드를 바꾸는 경우가 생겼다 — 검색 0건 → {@code S}) 같은 수정을
 * 양쪽에 똑같이 해야 했고, 한쪽만 고쳤다면 스트리밍으로 온 턴만 조용히 옛 값으로 저장됐을
 * 것이다. 화면에는 아무 차이도 안 보인다.
 *
 * <p><b>왜 스프링 빈이 아닌가.</b> 두 서비스가 이미 세 협력자를 필드로 들고 있어서, 그것으로
 * 직접 만들면 생성자 시그니처가 그대로다 — 두 클래스가 각각 테스트용 하위호환 생성자를 여럿
 * 들고 있어, 빈으로 만들면 그 전부와 테스트의 구성 지점까지 함께 흔들린다. 이 클래스가 하는
 * 일은 협력자를 새로 들이는 것이 아니라 <b>이미 있는 것들 위의 절차 하나</b>를 한곳에 두는
 * 것뿐이라, 배선을 늘릴 이유가 없다.
 */
final class TurnPersistence {

    /**
     * 저장할 턴의 <b>요청 쪽</b> 값. 이것만 진입점마다 다르고 나머지는 {@link AgentState} 에서 나온다.
     *
     * @param askedAt   질문 시각 — 그래프를 돌리기 <b>전에</b> 찍은 값이다(응답 시각이 아니다)
     * @param elapsedMs 그래프 실행에 걸린 시간
     */
    record Turn(String userId, String threadId, String question, List<String> selectedTags,
                boolean directMode, Locale locale, String askedAt, long elapsedMs) {}

    private final MemoryService memoryService;
    private final ConversationSummarizerService summarizerService;
    /** 없을 수 있다 — 질문 재사용을 배선하지 않은 구성/테스트에서는 그 단계만 빠진다. */
    private final QuestionReuseService questionReuseService;

    TurnPersistence(MemoryService memoryService,
                    ConversationSummarizerService summarizerService,
                    QuestionReuseService questionReuseService) {
        this.memoryService = memoryService;
        this.summarizerService = summarizerService;
        this.questionReuseService = questionReuseService;
    }

    /**
     * 답변이 있는 턴을 저장한다.
     *
     * <p><b>응답 모드는 요청이 아니라 결과에서 읽는다</b> — 그래프가 모드를 바꾸는 경우가 있고
     * (검색 0건 → {@code S}, {@code AnswerService.answerWithoutDocuments}), 저장된 값이 대화
     * 버블의 두 글자 표기와 좋아요 가능 여부를 정한다. 아래 검증 스냅샷도 같은 값을 읽으므로
     * 둘이 갈리면 한 턴이 자기 모드에 대해 두 가지를 말하게 된다.
     *
     * @return 저장된 turn id. 답변이 비어 아무것도 저장하지 않았으면 {@code null}
     */
    Long save(Turn turn, AgentState result) {
        if (result.answer() == null || result.answer().isBlank()) return null;

        long turnId = memoryService.addTurn(
                turn.userId(), turn.threadId(), turn.question(), result.answer(),
                turn.askedAt(), result.totalInputTokens(), result.totalOutputTokens(),
                (int) turn.elapsedMs(), result.usedProvider(), result.llmCallCount(),
                result.responseMode().name(), TagUtils.toMetaValue(turn.selectedTags()),
                turn.directMode());

        memoryService.saveTurnImageRefs(turnId, turn.userId(), turn.threadId(), result.imageRefs());
        memoryService.saveRetrievalMetrics(turnId, result.sources());
        memoryService.saveVerification(turnId, new VerificationSnapshot(
                result.grounded(), result.responseMode().generative(),
                result.evalReason(), result.envNote(), result.inventedSymbols(),
                result.budgetNote(),
                result.wasCondensed() ? result.searchQuestion() : null));
        if (questionReuseService != null) {
            questionReuseService.recordTurnSources(turnId, turn.userId(), turn.threadId(),
                    result.retrievedDocs(), result.sources());
        }
        summarizerService.precomputeAfterTurn(turn.userId(), turn.threadId(), turnId, turn.locale());
        return turnId;
    }
}
