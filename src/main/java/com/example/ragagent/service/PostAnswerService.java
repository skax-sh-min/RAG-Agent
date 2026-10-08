package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.repository.MemoryRepository;
import com.example.ragagent.security.PromptInjectionGuard;
import com.example.ragagent.web.MdcPropagation;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 답변이 끝난 턴을 한 번의 LLM 호출로 보강한다 — <b>재사용할 수 있게 다듬은 질문</b>
 * ({@code conversation_turns.clarified_question}, V6)과 <b>이어서 물어볼 만한 질문</b>(추가 질문, 저장하지 않는다).
 *
 * <p><b>왜 필요한가.</b> 질문 재사용(§ 질문 추천/재사용)은 과거 질문의 원문만 본다. "이거 어떻게 설정해?"
 * 같은 질문은 지시어뿐이라 추천에서 빠지고({@code QuestionReuseService.isDirectiveOnlyQuestion}),
 * "설정 방법 알려줘"처럼 모호한 질문은 "설정"이 든 입력마다 엉뚱하게 뜨며, 재사용하면 그 원문이 새 대화에
 * 그대로 복사돼 그 대화의 기록이 의미를 잃는다. 그래서 턴마다 "이 답변이 답하는 질문"을 혼자서도 뜻이
 * 통하는 한 문장으로 만들어 원문 <b>옆에</b> 둔다 — 원문은 사용자가 쓴 문장이라 덮어쓰지 않는다.
 *
 * <p><b>답변 요약을 재료로 쓴다 — 독립화와 반대로.</b> {@link QuestionCondenser} 는 답변을 일부러 재료에서
 * 뺐다(답변의 표현이 검색어가 되면 검색이 자기 답변을 다시 찾고, Direct 답변이 지어낸 용어가 검색어로
 * 굳는다). 여기서는 목적이 다르다: 이 문장은 검색어가 아니라 재사용 추천에서 남이 고르는 문장이라, 답변이
 * 실제로 다룬 범위를 말해야 맞다. 대신 <b>검색 경로에는 절대 넣지 않는다</b>(독립화 재료·MultiQuery·답변
 * 프롬프트 어디에도) — 그쪽의 오염 경로는 그대로 닫혀 있다. 답변 전문이 아니라 "## 요약" 섹션만 넘기므로
 * 입력이 작다(소형 모델 계층 {@code MICRO_TEXT}). 출력이 짧아 <b>생각을 끄고</b> 부른다
 * ({@code app.llm.thinking.post-answer}, 출하값 끔 — {@code ThinkingControlChatModel}) — 추론 모델은 한 줄 앞에 수백 토큰을 생각해서, 켠 채로는 로컬 서버에서 턴마다
 * 10~17초의 백그라운드 생성이었고 끄면 1초 안팎(추가 질문까지 받으면 3초 안팎)이다. 스위치를 받지 않는 서버를
 * 위해 출력 예약은 넉넉히 둔다({@link #MAX_OUTPUT_TOKENS}).
 *
 * <p><b>무엇을 부르는가 — 설정 둘과 턴의 성격으로 갈린다</b>({@link #afterTurn}).
 * <ul>
 *   <li>추가 질문이 켜져 있고 답변이 근거를 가진 턴(출처가 있거나 Direct)이면 <b>한 번의 호출로 둘 다</b> 받는다
 *       ({@code prompt.postanswer.extras}, JSON). 이때는 S·C·Direct 턴도 다듬은 질문을 저장한다 — 재사용 후보가
 *       아니라 화면 표시용이다(재사용 판정은 모드·출처 술어가 하므로 이 값으로 후보가 되지 않는다).</li>
 *   <li>추가 질문이 꺼져 있으면 재사용 후보 턴(N·RAG·출처 있음)만 다듬은 질문 한 줄을 받는다
 *       ({@code prompt.postanswer.clarify}).</li>
 *   <li>검색 0건 정형 답변·인사(RAG 인데 출처 없음)는 둘 다 부르지 않는다 — 다듬을 답도, 이어 물을 주제도 없다.</li>
 * </ul>
 *
 * <p><b>추가 질문은 저장하지 않는다.</b> 화면이 답변 직후 {@link #awaitExtras}(엔드포인트
 * {@code GET /ui/threads/{threadId}/turns/{turnId}/extras})로 기다렸다 받아 가장 최근 답변 아래에만 보여 주는
 * 일회성 제안이라, 결과는 {@link #EXTRAS_TTL} 동안 메모리에만 둔다(그 사이 대화를 다시 열면 다시 보인다).
 * 기다림은 턴 저장 시점에 먼저 등록된다 — 화면의 요청이 호출보다 먼저 와도 결과를 놓치지 않는다. 결과는 그
 * 턴을 만든 사용자·대화에만 내준다.
 *
 * <p><b>저장값의 뜻.</b> 다듬은 질문 · 원문 그대로(원문으로 충분했거나 결과를 버렸다 — "시도함") · NULL
 * (호출이 실패했거나 본문이 빈·읽을 수 없는 응답이 왔다 — 나중에 다시 시도할 수 있다. 빈 응답을 "시도함"으로
 * 적으면 출력 예산을 추론에 다 쓴 모델이 모든 턴을 다시는 시도하지 않을 턴으로 만든다). 결과를 버리는 경우는
 * 셋이다: 한 줄이 아니거나 {@link #MAX_CLARIFIED_CHARS} 를 넘을 때, 내용어가 하나도 없을 때, 그리고
 * <b>원문에 없던 낱말 가운데 이전 질문·답변 요약에서 온 것이 하나도 없을 때</b>({@link #accept}). 마지막
 * 것이 두 가지를 함께 막는다 — 이미 혼자서 뜻이 통하는 질문을 말만 바꿔 다시 쓴 출력(실측: "원문을 그대로
 * 출력할 것"이 규칙에 있어도 소형 모델은 "WAL 모드는 어떻게 켜나요?"를 "WAL 모드를 켜는 방법은
 * 무엇인가요?"로 고쳤다 — 받아 주면 거의 모든 질문 아래에 같은 말이 한 줄 더 붙는다)과, 재료와 무관한
 * 출력(문서에 섞여 들어온 지시문에 휘둘린 경우).
 *
 * <p>턴 저장 뒤 가상 스레드에서 돈다 — 사용자는 기다리지 않고, 실패해도 이미 저장된 답변에는 아무 일도
 * 없다. 설정 {@code llm.clarified-question-enabled}·{@code llm.follow-up-questions-enabled}(둘 다 기본 켬,
 * {@code /settings} 에서 끈다)는 매 턴 다시 읽는다.
 */
@Service
public class PostAnswerService {

    private static final Logger log = LoggerFactory.getLogger(PostAnswerService.class);

    /** 재료로 쓸 직전 질문 수 — 독립화와 같다. 지시 대상은 거의 직전 한두 턴에 있다. */
    static final int MATERIAL_TURNS = 3;

    /** 이전 질문 하나당 재료 상한. */
    static final int MAX_MATERIAL_QUESTION_CHARS = 300;

    /** 답변 요약 재료 상한 — 요약 섹션이 없는 답변은 앞부분을 이만큼 쓴다. */
    static final int MAX_SUMMARY_CHARS = 1_200;

    /** 다듬은 질문의 길이 상한 — 재사용 추천이 보여주는 상한과 같다(넘으면 어차피 추천에 안 뜬다). */
    static final int MAX_CLARIFIED_CHARS = QuestionReuseService.MAX_SUGGESTION_QUESTION_LENGTH;

    /** 추가 질문 수와 하나의 길이 상한 — 입력창에 넣어 그대로 보낼 수 있는 한 문장이어야 한다. */
    static final int FOLLOW_UP_COUNT = 3;
    static final int MAX_FOLLOW_UP_CHARS = QuestionReuseService.MAX_SUGGESTION_QUESTION_LENGTH;

    /**
     * 추가 질문의 재료로 싣는 출처 수와 발췌 길이 — 답변이 근거로 삼은 문서가 무엇을 더 다루는지 보여 주면 모델이
     * "문서에서 답을 찾을 수 있는" 다음 질문을 고른다(실측: 출처 발췌에만 있던 오류 코드·설정 이름이 추가 질문에
     * 나왔다). 짧게 두는 것은 소형 모델 계층의 입력을 작게 유지하기 위해서다.
     */
    static final int MAX_SOURCES = 4;
    static final int MAX_SOURCE_PREVIEW_CHARS = 150;

    /**
     * 출력 예약 — 한 줄짜리 응답에 프로바이더의 max-tokens 전체를 예약하지 않는다. 그런데도 넉넉한 이유는
     * {@code AnswerService.MAX_EVAL_OUTPUT_TOKENS} 와 같다: 추론(thinking) 모델은 답을 내기 전에 이 예산을 먼저
     * 쓴다. 이 호출은 생각을 끄고 부르지만({@link #options()}) 그 스위치를 받지 않는 서버(LM Studio 의 OpenAI
     * 호환 경로, 원격 프로바이더)에서는 모델이 여전히 생각한다 — 실측으로 llama.cpp 의 gemma-4-E2B 는 생각을 켠
     * 채 400~700 토큰을 {@code reasoning_content} 로 쓰고 나서야 한 줄을 냈고, 256 에서는 8건 모두 본문이 빈 채
     * {@code finish_reason=length} 로 끝났다(= 기능이 아무것도 하지 않는다). 생각을 끄면 실제로 쓰는 것은 한 줄
     * 20토큰, 추가 질문까지 200토큰 안팎이고, 예약은 쓰지 않으면 비용이 없다.
     */
    static final int MAX_OUTPUT_TOKENS = 2_048;

    /** 추가 질문을 메모리에 두는 시간 — 저장하지 않는 일회성 제안이라, 대화를 잠시 떠났다 돌아오는 정도만 덮는다. */
    static final Duration EXTRAS_TTL = Duration.ofMinutes(10);

    /** 화면이 기다릴 수 있는 상한 — 생각을 끄지 못하는 서버에서 추가 질문까지 받는 데 걸리는 시간을 덮는다. */
    public static final Duration MAX_EXTRAS_WAIT = Duration.ofSeconds(25);

    /** 모델이 흔히 붙이는 머리말("질문:", "Rewritten question:")을 벗긴다. */
    private static final Pattern LABEL_PREFIX = Pattern.compile(
            "^(?:다듬은 질문|재작성된 질문|질문|rewritten question|clarified question|question)\\s*[:：]\\s*",
            Pattern.CASE_INSENSITIVE);

    /** 추가 질문 앞에 모델이 붙이는 목록 기호(1. / 1) / - / • / ·). */
    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*•·]|\\d{1,2}[.)])\\s*");

    /** 백필이 프롬프트 언어를 고르는 기준 — 질문에 한글 음절이 하나라도 있는가. */
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");

    /**
     * 화면에 보낼 결과 — {@code clarifiedQuestion} 은 원문과 다를 때만(버블에 둘째 줄을 붙일 때만) 있고,
     * {@code followUps} 는 비어 있을 수 있다.
     */
    public record Extras(String clarifiedQuestion, List<String> followUps) {
        static final Extras NONE = new Extras(null, List.of());

        public Extras {
            followUps = followUps == null ? List.of() : List.copyOf(followUps);
        }

        @JsonIgnore
        public boolean isEmpty() {
            return clarifiedQuestion == null && followUps.isEmpty();
        }
    }

    /** 합친 호출의 응답 모양 — 스키마는 {@link BeanOutputConverter} 가 사용자 메시지로 덧붙인다. */
    record ExtrasOutput(String clarifiedQuestion, List<String> followUps) {}

    /** 턴 저장 때 먼저 등록되는 기다림 — 결과는 그 턴을 만든 사용자·대화에만 내준다. */
    private record Pending(String userId, String threadId, CompletableFuture<Extras> result) {}

    private final LlmRouter llmRouter;
    private final MemoryService memoryService;
    private final MessageSource messageSource;
    private final AppProperties props;
    private final SettingsService settingsService;
    private final BeanOutputConverter<ExtrasOutput> extrasConverter = new BeanOutputConverter<>(ExtrasOutput.class);
    private final Cache<Long, Pending> pending = Caffeine.newBuilder()
            .expireAfterWrite(EXTRAS_TTL)
            .maximumSize(1_000)
            .build();

    public PostAnswerService(LlmRouter llmRouter, MemoryService memoryService, MessageSource messageSource,
                             AppProperties props, SettingsService settingsService) {
        this.llmRouter = llmRouter;
        this.memoryService = memoryService;
        this.messageSource = messageSource;
        this.props = props;
        this.settingsService = settingsService;
    }

    /**
     * 턴 저장 직후 {@code TurnPersistence} 가 부른다. 할 일이 있으면 기다림을 먼저 등록하고 가상 스레드에서 한 번
     * 호출한다(클래스 주석의 세 갈래).
     *
     * @param directMode 요청의 Direct 여부 — 그 턴은 검색을 돌리지 않아 재사용 후보가 아니다
     */
    public void afterTurn(long turnId, String userId, String threadId, String question,
                          boolean directMode, Locale locale, AgentState result) {
        boolean clarifyOn = settingsService.clarifiedQuestionEnabled();
        boolean withFollowUps = settingsService.followUpQuestionsEnabled() && canSuggestFollowUps(result, directMode);
        boolean clarifyOnly = !withFollowUps && clarifyOn && isReuseCandidate(result, directMode);
        if (!withFollowUps && !clarifyOnly) return;

        CompletableFuture<Extras> done = new CompletableFuture<>();
        pending.put(turnId, new Pending(userId, threadId, done));
        Thread.ofVirtual().start(MdcPropagation.wrap(() -> {
            Extras extras = Extras.NONE;
            try {
                extras = withFollowUps
                        ? clarifyWithFollowUps(turnId, userId, threadId, question, result, locale, clarifyOn)
                        : clarify(turnId, userId, threadId, question, result.answer(), locale);
            } catch (RuntimeException e) {
                log.warn("[CLARIFY] 답변 뒤 보강 실패 turnId={}: {}", turnId, e.toString());
            } finally {
                done.complete(extras);
            }
        }));
    }

    /**
     * 이 턴의 답변 뒤 결과를 기다린다 — 등록된 기다림이 없거나(대상이 아니었거나 {@link #EXTRAS_TTL} 이 지났다)
     * 다른 사용자·대화의 턴이거나 {@code wait} 안에 끝나지 않으면 비어 있다. {@code wait} 은
     * {@link #MAX_EXTRAS_WAIT} 로 잘리고, 0 이면 기다리지 않고 지금 있는 것만 본다(대화를 다시 열 때).
     */
    public Optional<Extras> awaitExtras(long turnId, String userId, String threadId, Duration wait) {
        Pending p = pending.getIfPresent(turnId);
        if (p == null || !Objects.equals(p.userId(), userId) || !Objects.equals(p.threadId(), threadId)) {
            return Optional.empty();
        }
        long waitMs = Math.max(0, Math.min(wait == null ? 0 : wait.toMillis(), MAX_EXTRAS_WAIT.toMillis()));
        try {
            Extras extras = waitMs == 0
                    ? p.result().getNow(null)
                    : p.result().get(waitMs, TimeUnit.MILLISECONDS);
            return Optional.ofNullable(extras);
        } catch (TimeoutException | ExecutionException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * 재사용 후보가 될 수 있는 턴인가 — 재사용 쿼리({@code QuestionReuseRepository})가 거르는 조건 가운데
     * 저장 시점에 알 수 있는 것과 같다: 재사용을 허용하는 모드 · Direct 아님 · 출처 있음. 싫어요는 저장
     * 시점에 아직 없고, 나중에 붙어도 원문 옆의 한 줄이 남을 뿐이다.
     */
    static boolean isReuseCandidate(AgentState result, boolean directMode) {
        if (result == null || directMode || result.directMode()) return false;
        if (result.responseMode() == null || !result.responseMode().allowsReuse()) return false;
        return result.sources() != null && !result.sources().isEmpty();
    }

    /**
     * 추가 질문을 제안할 만한 턴인가 — 답변이 있고, 그 답변이 문서를 근거로 했거나(출처 있음) Direct 로 직접
     * 답했을 때. RAG 인데 출처가 없는 턴은 검색 0건 정형 답변이거나 인사·메타 질문이라 이어 물을 주제가 없다.
     * 응답 모드는 보지 않는다 — S 의 짧은 답 뒤에도, C 로 만든 결과물 뒤에도 다음 질문은 있다.
     */
    static boolean canSuggestFollowUps(AgentState result, boolean directMode) {
        if (result == null || result.answer() == null || result.answer().isBlank()) return false;
        if (directMode || result.directMode()) return true;
        return result.sources() != null && !result.sources().isEmpty();
    }

    /** 추가 질문이 꺼져 있을 때의 길 — 다듬은 질문 한 줄만 받는다. 화면에는 다듬은 질문이 원문과 다를 때만 실린다. */
    Extras clarify(long turnId, String userId, String threadId, String question, String answer, Locale locale) {
        Clarified result = clarifyOnce(turnId, userId, threadId, question, answer, locale);
        return result.outcome() == Outcome.CLARIFIED ? new Extras(result.question(), List.of()) : Extras.NONE;
    }

    /**
     * 과거 턴 하나를 다듬는다 — {@code /admin} 백필({@link ClarifiedQuestionBackfill})의 한 건. 라이브 경로의 한 줄
     * 다듬기와 같은 호출·판정·저장이고, 재료(이전 질문)는 그 턴 <b>시점</b>의 것이다. 턴에는 로케일이 저장돼 있지
     * 않아 질문에 한글이 있으면 한국어 프롬프트, 없으면 영어 프롬프트를 쓴다 — 프롬프트 언어를 질문과 맞추지
     * 않으면 소형 모델은 프롬프트의 언어로 답한다(실측: 한국어 프롬프트에 영어 질문 → 한국어 문장).
     */
    Outcome backfill(long turnId, String userId, String threadId, String question, String answer) {
        Locale locale = question != null && HANGUL.matcher(question).find() ? Locale.KOREAN : Locale.ENGLISH;
        return clarifyOnce(turnId, userId, threadId, question, answer, locale).outcome();
    }

    /** 한 줄 다듬기 한 번의 결과 — 저장은 이미 끝났다(실패면 아무것도 저장하지 않았다). */
    enum Outcome { CLARIFIED, KEPT_ORIGINAL, FAILED }

    private record Clarified(Outcome outcome, String question) {}

    private Clarified clarifyOnce(long turnId, String userId, String threadId, String question, String answer,
                                  Locale locale) {
        if (question == null || question.isBlank()) return new Clarified(Outcome.FAILED, null);
        List<String> earlier = earlierQuestions(userId, threadId, turnId);
        String history = historyBlock(earlier);
        String summary = summaryOf(answer);
        String none = noneFor(locale);
        String raw;
        try {
            String systemPrompt = messageSource.getMessage("prompt.postanswer.clarify", null, locale)
                    .replace("{history}", history.isBlank() ? none : history)
                    .replace("{summary}", summary.isBlank() ? none : summary)
                    .replace("{query}", PromptInjectionGuard.wrap(question));
            raw = llmRouter.executeWithTracking(ThinkingSite.POST_ANSWER.taskType(), ThinkingSite.POST_ANSWER.fixedRoutingMode(),
                    BackgroundUsage.POSTANSWER_PREFIX,
                    model -> model.call(new Prompt(
                            List.of(new SystemMessage(systemPrompt), new UserMessage(question)), options())));
        } catch (Exception e) {
            // 호출이 실패했다 — NULL 로 남겨 두면 백필이 다시 시도할 수 있다.
            log.warn("[CLARIFY] 질문 다듬기 호출 실패 turnId={}: {}", turnId, e.getMessage());
            return new Clarified(Outcome.FAILED, null);
        }
        if (raw == null || raw.isBlank()) {
            // 본문 없는 응답 — 대개 추론 모델이 출력 예산을 생각에 다 쓴 경우다. 원문을 "시도함"으로 적지
            // 않고 NULL 로 남긴다(호출 실패와 같은 취급).
            log.warn("[CLARIFY] 질문 다듬기 응답이 비었다 turnId={} — 출력 예산({} 토큰)을 추론에 다 썼을 수 있다",
                    turnId, MAX_OUTPUT_TOKENS);
            return new Clarified(Outcome.FAILED, null);
        }
        String clarified = saveClarified(turnId, question, parse(raw), history + "\n" + summary, raw);
        return clarified == null ? new Clarified(Outcome.KEPT_ORIGINAL, null) : new Clarified(Outcome.CLARIFIED, clarified);
    }

    /**
     * 추가 질문이 켜져 있을 때의 길 — 한 번의 호출로 다듬은 질문과 추가 질문 셋을 함께 받는다. 다듬은 질문은
     * {@code clarifyOn} 일 때만 저장하고 화면에 싣는다. 응답을 읽지 못하면 아무것도 저장하지 않는다(NULL 로 남아
     * 다시 시도할 수 있다).
     */
    Extras clarifyWithFollowUps(long turnId, String userId, String threadId, String question, AgentState result,
                                Locale locale, boolean clarifyOn) {
        if (question == null || question.isBlank()) return Extras.NONE;
        List<String> earlier = earlierQuestions(userId, threadId, turnId);
        String history = historyBlock(earlier);
        String summary = summaryOf(result.answer());
        String sources = sourcesOf(result.sources());
        String none = noneFor(locale);
        String raw;
        try {
            String systemPrompt = messageSource.getMessage("prompt.postanswer.extras", null, locale)
                    .replace("{history}", history.isBlank() ? none : history)
                    .replace("{sources}", sources.isBlank() ? none : sources)
                    .replace("{summary}", summary.isBlank() ? none : summary)
                    .replace("{query}", PromptInjectionGuard.wrap(question));
            raw = llmRouter.executeWithTracking(ThinkingSite.POST_ANSWER.taskType(), ThinkingSite.POST_ANSWER.fixedRoutingMode(),
                    BackgroundUsage.POSTANSWER_PREFIX,
                    model -> model.call(new Prompt(List.of(new SystemMessage(systemPrompt),
                            new UserMessage(extrasConverter.getFormat())), options())));
        } catch (Exception e) {
            log.warn("[CLARIFY] 답변 뒤 호출 실패 turnId={}: {}", turnId, e.getMessage());
            return Extras.NONE;
        }
        if (raw == null || raw.isBlank()) {
            log.warn("[CLARIFY] 답변 뒤 응답이 비었다 turnId={} — 출력 예산({} 토큰)을 추론에 다 썼을 수 있다",
                    turnId, MAX_OUTPUT_TOKENS);
            return Extras.NONE;
        }
        ExtrasOutput out;
        try {
            out = extrasConverter.convert(raw);
        } catch (Exception malformed) {
            log.warn("[CLARIFY] 답변 뒤 응답을 읽지 못했다 turnId={}: {}", turnId, malformed.getMessage());
            return Extras.NONE;
        }
        if (out == null) return Extras.NONE;

        String clarified = clarifyOn
                ? saveClarified(turnId, question, parse(out.clarifiedQuestion()), history + "\n" + summary, raw)
                : null;
        List<String> followUps = cleanFollowUps(out.followUps(), question, earlier);
        log.info("[FOLLOW-UP] turnId={} 추가 질문 {}개", turnId, followUps.size());
        return new Extras(clarified, followUps);
    }

    /** 다듬은 질문을 판정해 저장한다 — 받아들였으면 그 문장(= 원문과 다르다), 아니면 원문을 "시도함"으로 적고 null. */
    private String saveClarified(long turnId, String question, String candidate, String material, String raw) {
        String clarified = accept(question, candidate, material);
        memoryService.saveClarifiedQuestion(turnId, clarified != null ? clarified : question.strip());
        if (clarified != null) {
            log.info("[CLARIFY] 질문 다듬음 turnId={} 원문=[{}] 다듬은 질문=[{}]", turnId, question, clarified);
        } else {
            log.debug("[CLARIFY] 원문 유지 turnId={} 원문=[{}] 응답=[{}]", turnId, question, raw);
        }
        return clarified;
    }

    /**
     * 이 턴 <b>앞의</b> 최근 {@value #MATERIAL_TURNS} 질문, 오래된 것부터 — 그 턴 시점의 것이다(방금 끝난 턴이면
     * 직전 질문들, 백필이면 대화 중간 그 자리의 이전 질문들. {@link MemoryRepository#findQuestionsBefore}).
     */
    private List<String> earlierQuestions(String userId, String threadId, long turnId) {
        List<String> questions;
        try {
            questions = memoryService.getQuestionsBefore(userId, threadId, turnId, MATERIAL_TURNS);
        } catch (Exception e) {
            log.debug("[CLARIFY] 이전 질문 조회 실패 thread={}: {}", threadId, e.getMessage());
            return List.of();
        }
        if (questions == null) return List.of();
        return questions.stream()
                .filter(q -> q != null && !q.isBlank())
                .map(q -> q.strip().replaceAll("\\s+", " "))
                .toList();
    }

    private static String historyBlock(List<String> earlier) {
        List<String> lines = new ArrayList<>();
        for (String one : earlier) {
            lines.add("- " + (one.length() > MAX_MATERIAL_QUESTION_CHARS
                    ? one.substring(0, MAX_MATERIAL_QUESTION_CHARS) + "…" : one));
        }
        return String.join("\n", lines);
    }

    /** 답변의 "## 요약" 섹션 — 없으면(Direct 형식 등) 답변 앞부분. */
    static String summaryOf(String answer) {
        if (answer == null || answer.isBlank()) return "";
        String summary = CuratedTextUtils.extractSummarySection(answer);
        String text = summary.isBlank() ? answer.strip() : summary;
        return text.length() > MAX_SUMMARY_CHARS ? text.substring(0, MAX_SUMMARY_CHARS) + "…" : text;
    }

    /** 답변이 근거로 삼은 문서 — 위치 표시와 짧은 발췌, 검색 순위대로 {@value #MAX_SOURCES}개까지. */
    static String sourcesOf(List<SourceRef> sources) {
        if (sources == null || sources.isEmpty()) return "";
        Set<String> lines = new LinkedHashSet<>();
        for (SourceRef s : sources) {
            if (lines.size() >= MAX_SOURCES) break;
            String label = s.label() == null ? "" : s.label().strip();
            String preview = s.preview() == null ? ""
                    : MarkdownNoiseNormalizer.normalize(s.preview()).replaceAll("\\s+", " ").strip();
            if (preview.length() > MAX_SOURCE_PREVIEW_CHARS) {
                preview = preview.substring(0, MAX_SOURCE_PREVIEW_CHARS) + "…";
            }
            if (label.isEmpty() && preview.isEmpty()) continue;
            lines.add(preview.isEmpty() ? "- " + label : "- " + label + ": " + preview);
        }
        return String.join("\n", lines);
    }

    /**
     * 첫 번째 비어 있지 않은 줄만 — 감싼 따옴표와 흔한 머리말을 벗긴다. 길이를 넘기면 잘라 쓰지 않고
     * 버린다({@code QuestionCondenser.parse} 와 같은 규칙: 그 길이는 질문 한 줄이 아니라 설명을 냈다는 뜻이다).
     */
    static String parse(String response) {
        if (response == null || response.isBlank()) return null;
        String line = response.lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
        line = unquote(LABEL_PREFIX.matcher(line).replaceFirst("").strip());
        if (line.isEmpty() || line.length() > MAX_CLARIFIED_CHARS) return null;
        return line;
    }

    /**
     * 추가 질문을 화면에 낼 모양으로 — 한 줄만, 목록 기호·감싼 따옴표를 벗기고, 너무 긴 것·지금 질문이나 이전
     * 질문과 같은 것·서로 겹치는 것을 버린 뒤 {@value #FOLLOW_UP_COUNT}개까지. 입력창에 그대로 들어가 보내질
     * 문장이라 잘라 쓰지 않는다.
     */
    static List<String> cleanFollowUps(List<String> raw, String question, List<String> earlier) {
        if (raw == null || raw.isEmpty()) return List.of();
        Set<String> seen = new HashSet<>();
        seen.add(normalize(question));
        if (earlier != null) earlier.forEach(q -> seen.add(normalize(q)));
        List<String> out = new ArrayList<>();
        for (String one : raw) {
            if (out.size() >= FOLLOW_UP_COUNT) break;
            if (one == null) continue;
            String line = one.lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
            line = unquote(LIST_MARKER.matcher(line).replaceFirst("").strip());
            if (line.length() < 2 || line.length() > MAX_FOLLOW_UP_CHARS) continue;
            if (!seen.add(normalize(line))) continue;
            out.add(line);
        }
        return List.copyOf(out);
    }

    private static String unquote(String line) {
        if (line.length() >= 2
                && ((line.startsWith("\"") && line.endsWith("\""))
                 || (line.startsWith("'") && line.endsWith("'"))
                 || (line.startsWith("“") && line.endsWith("”")))) {
            return line.substring(1, line.length() - 1).strip();
        }
        return line;
    }

    /**
     * 다듬은 질문을 받아들일지 — 받아들이면 그 문장, 아니면 {@code null}(원문을 쓴다).
     *
     * <p>원문과 같으면 바꿀 것이 없고, 내용어가 없으면 다른 대화에서 쓸 수 있는 질문이 아니다. 그리고
     * <b>원문에 없던 내용어 가운데 재료(이전 질문·답변 요약)에서 온 것이 하나는 있어야 한다</b> — 그것이
     * 지시어나 생략된 대상을 실제로 풀었다는 흔적이다. 없으면 둘 중 하나다: 이미 혼자서 뜻이 통하는 질문을
     * 말만 바꿔 쓴 것(그러면 원문으로 충분하다)이거나, 재료에 없는 낱말을 들여온 것(재료에서 나온 문장이
     * 아니다). 낱말 비교는 접두·포함 관계로 느슨하게 한다 — 어미 절단이 형태소 분석이 아니라서
     * {@code 발생}/{@code 발생한다} 처럼 같은 낱말이 다른 모양으로 남는다.
     */
    static String accept(String original, String candidate, String material) {
        if (candidate == null) return null;
        if (!differs(original, candidate)) return null;
        Set<String> mine = contentWords(candidate);
        if (mine.isEmpty()) return null;
        Set<String> own = contentWords(original);
        Set<String> known = contentWords(material);
        boolean resolvesSomething = mine.stream()
                .filter(k -> !matchesAny(k, own))
                .anyMatch(k -> matchesAny(k, known));
        return resolvesSomething ? candidate : null;
    }

    /**
     * 문장의 내용어 전부 — {@link QuestionKeywords} 의 규칙 그대로이되 개수 상한이 없다. 상한(추천 검색의
     * AND 술어 수)을 그대로 쓰면 이전 질문 세 개와 요약을 이은 재료에서 앞의 여섯 낱말만 남아, 요약에만 있는
     * 이름으로 지시어를 푼 문장을 재료와 무관하다고 버린다.
     */
    private static Set<String> contentWords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : QuestionKeywords.tokenize(text)) {
            String kw = QuestionKeywords.keyword(raw);
            if (kw != null) out.add(kw);
        }
        return out;
    }

    private static boolean matchesAny(String word, Set<String> words) {
        return words.stream().anyMatch(w -> w.contains(word) || word.contains(w));
    }

    /**
     * 원문과 다듬은 질문이 화면에 둘 다 보일 만큼 다른가 — 공백·끝 문장부호·대소문자만 다르면 같다.
     * 질문 버블과 "전체 질문 보기"가 이 판정으로 둘째 줄을 붙인다(규칙은 여기 하나).
     */
    public static boolean differs(String original, String clarified) {
        if (clarified == null || clarified.isBlank()) return false;
        return !normalize(original).equals(normalize(clarified));
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.strip().replaceAll("\\s+", " ").replaceAll("[\\s?？.!。]+$", "").toLowerCase(Locale.ROOT);
    }

    private static String noneFor(Locale locale) {
        return Locale.KOREAN.getLanguage().equals(locale == null ? null : locale.getLanguage()) ? "(없음)" : "(none)";
    }

    /**
     * 제목 생성과 같은 인덱싱/백그라운드 temperature — 다듬기는 창의 작업이 아니다. 핫이라 매 호출 다시 읽는다.
     * 생각은 끈다(클래스 주석) — 실을지는 받는 프로바이더가 정한다.
     */
    private OpenAiChatOptions options() {
        OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                .temperature(props.llmSafe().indexingTemperature()), ThinkingSite.POST_ANSWER);
        int base = baseReservation(props.llmSafe().maxTokens());
        if (base > 0) builder.maxTokens(base);
        return builder.build();
    }

    /**
     * 이 호출의 <b>기본</b> 출력 예약 — {@link #MAX_OUTPUT_TOKENS} 를 설정 상한으로 누른 것. 0 = 싣지 않는다(프로바이더
     * 기본값). 요청 옵션과 {@code /settings} 의 생각 수준 미리보기가 같은 함수를 지난다(§6.29 ⑦-바).
     */
    static int baseReservation(int configuredMaxTokens) {
        return configuredMaxTokens > 0 ? Math.min(configuredMaxTokens, MAX_OUTPUT_TOKENS) : 0;
    }
}
