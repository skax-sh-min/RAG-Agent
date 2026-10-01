package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingOffChatModel;
import com.example.ragagent.repository.MemoryRepository;
import com.example.ragagent.security.PromptInjectionGuard;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 답변이 끝난 턴을 한 번의 LLM 호출로 보강한다 — 지금은 <b>재사용할 수 있게 다듬은 질문</b>
 * ({@code conversation_turns.clarified_question}, V6).
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
 * 입력이 작다(소형 모델 계층 {@code MICRO_TEXT}). 출력은 한 줄이라 <b>생각을 끄고</b> 부른다
 * ({@code ThinkingOffChatModel}) — 추론 모델은 한 줄 앞에 수백 토큰을 생각해서, 켠 채로는 로컬 서버에서 턴마다
 * 10~17초의 백그라운드 생성이었고 끄면 1초 안팎이다. 스위치를 받지 않는 서버를 위해 출력 예약은 넉넉히 둔다
 * ({@link #MAX_OUTPUT_TOKENS}).
 *
 * <p><b>대상은 재사용 후보가 될 수 있는 턴뿐이다</b>({@link #isReuseCandidate}) — 출처가 있는 RAG 답변이고
 * 응답 모드가 재사용을 허용할 때(지금은 N). S·C·Direct·인사·검색 0건은 재사용 후보가 아니라서 이 호출이
 * 표시 말고는 쓸모가 없다. 같은 호출에 추가 질문을 함께 받게 되면(다음 단계) 그 턴들도 표시용으로 만든다.
 *
 * <p><b>저장값의 뜻.</b> 다듬은 질문 · 원문 그대로(원문으로 충분했거나 결과를 버렸다 — "시도함") · NULL
 * (호출이 실패했거나 본문이 빈 응답이 왔다 — 나중에 다시 시도할 수 있다. 빈 응답을 "시도함"으로 적으면
 * 출력 예산을 추론에 다 쓴 모델이 모든 턴을 다시는 시도하지 않을 턴으로 만든다). 결과를 버리는 경우는
 * 셋이다: 한 줄이 아니거나 {@link #MAX_CLARIFIED_CHARS} 를 넘을 때, 내용어가 하나도 없을 때, 그리고
 * <b>원문에 없던 낱말 가운데 이전 질문·답변 요약에서 온 것이 하나도 없을 때</b>({@link #accept}). 마지막
 * 것이 두 가지를 함께 막는다 — 이미 혼자서 뜻이 통하는 질문을 말만 바꿔 다시 쓴 출력(실측: "원문을 그대로
 * 출력할 것"이 규칙에 있어도 소형 모델은 "WAL 모드는 어떻게 켜나요?"를 "WAL 모드를 켜는 방법은
 * 무엇인가요?"로 고쳤다 — 받아 주면 거의 모든 질문 아래에 같은 말이 한 줄 더 붙는다)과, 재료와 무관한
 * 출력(문서에 섞여 들어온 지시문에 휘둘린 경우).
 *
 * <p>턴 저장 뒤 가상 스레드에서 돈다 — 사용자는 기다리지 않고, 실패해도 이미 저장된 답변에는 아무 일도
 * 없다. 설정 {@code llm.clarified-question-enabled}(기본 켬, {@code /settings} 에서 끈다)는 매 턴 다시 읽는다.
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

    /**
     * 출력 예약 — 한 줄짜리 응답에 프로바이더의 max-tokens 전체를 예약하지 않는다. 그런데도 한 줄에 비해
     * 넉넉한 이유는 {@code AnswerService.MAX_EVAL_OUTPUT_TOKENS} 와 같다: 추론(thinking) 모델은 답을 내기
     * 전에 이 예산을 먼저 쓴다. 이 호출은 생각을 끄고 부르지만({@link #options()}) 그 스위치를 받지 않는 서버
     * (LM Studio 의 OpenAI 호환 경로, 원격 프로바이더)에서는 모델이 여전히 생각한다 — 실측으로 llama.cpp 의
     * gemma-4-E2B 는 생각을 켠 채 400~700 토큰을 {@code reasoning_content} 로 쓰고 나서야 한 줄을 냈고,
     * 256 에서는 8건 모두 본문이 빈 채 {@code finish_reason=length} 로 끝났다(= 기능이 아무것도 하지 않는다).
     * 생각을 끄면 실제로 쓰는 것은 20토큰 안팎이고, 예약은 쓰지 않으면 비용이 없다.
     */
    static final int MAX_OUTPUT_TOKENS = 2_048;

    /** 모델이 흔히 붙이는 머리말("질문:", "Rewritten question:")을 벗긴다. */
    private static final Pattern LABEL_PREFIX = Pattern.compile(
            "^(?:다듬은 질문|재작성된 질문|질문|rewritten question|clarified question|question)\\s*[:：]\\s*",
            Pattern.CASE_INSENSITIVE);

    private final LlmRouter llmRouter;
    private final MemoryService memoryService;
    private final MessageSource messageSource;
    private final AppProperties props;
    private final SettingsService settingsService;

    public PostAnswerService(LlmRouter llmRouter, MemoryService memoryService, MessageSource messageSource,
                             AppProperties props, SettingsService settingsService) {
        this.llmRouter = llmRouter;
        this.memoryService = memoryService;
        this.messageSource = messageSource;
        this.props = props;
        this.settingsService = settingsService;
    }

    /**
     * 턴 저장 직후 {@code TurnPersistence} 가 부른다. 대상이고 켜져 있으면 가상 스레드에서 한 번 호출한다.
     *
     * @param directMode 요청의 Direct 여부 — 그 턴은 검색을 돌리지 않아 재사용 후보가 아니다
     */
    public void afterTurn(long turnId, String userId, String threadId, String question,
                          boolean directMode, Locale locale, AgentState result) {
        if (!settingsService.clarifiedQuestionEnabled()) return;
        if (!isReuseCandidate(result, directMode)) return;
        Thread.ofVirtual().start(MdcPropagation.wrap(() ->
                clarify(turnId, userId, threadId, question, result.answer(), locale)));
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

    void clarify(long turnId, String userId, String threadId, String question, String answer, Locale locale) {
        if (question == null || question.isBlank()) return;
        String history = buildHistory(userId, threadId, turnId);
        String summary = summaryOf(answer);
        String raw;
        String none = Locale.KOREAN.getLanguage().equals(locale == null ? null : locale.getLanguage())
                ? "(없음)" : "(none)";
        try {
            String systemPrompt = messageSource.getMessage("prompt.postanswer.clarify", null, locale)
                    .replace("{history}", history.isBlank() ? none : history)
                    .replace("{summary}", summary.isBlank() ? none : summary)
                    .replace("{query}", PromptInjectionGuard.wrap(question));
            raw = llmRouter.executeWithTracking(TaskType.MICRO_TEXT, RoutingMode.COST_FIRST,
                    BackgroundUsage.POSTANSWER_PREFIX,
                    model -> model.call(new Prompt(
                            List.of(new SystemMessage(systemPrompt), new UserMessage(question)), options())));
        } catch (Exception e) {
            // 호출이 실패했다 — NULL 로 남겨 두면 나중의 백필이 다시 시도할 수 있다.
            log.warn("[CLARIFY] 질문 다듬기 호출 실패 turnId={}: {}", turnId, e.getMessage());
            return;
        }
        if (raw == null || raw.isBlank()) {
            // 본문 없는 응답 — 대개 추론 모델이 출력 예산을 생각에 다 쓴 경우다. 원문을 "시도함"으로 적지
            // 않고 NULL 로 남긴다(호출 실패와 같은 취급).
            log.warn("[CLARIFY] 질문 다듬기 응답이 비었다 turnId={} — 출력 예산({} 토큰)을 추론에 다 썼을 수 있다",
                    turnId, MAX_OUTPUT_TOKENS);
            return;
        }
        String clarified = accept(question, parse(raw), history + "\n" + summary);
        memoryService.saveClarifiedQuestion(turnId, clarified != null ? clarified : question.strip());
        if (clarified != null) {
            log.info("[CLARIFY] 질문 다듬음 turnId={} 원문=[{}] 다듬은 질문=[{}]", turnId, question, clarified);
        } else {
            log.debug("[CLARIFY] 원문 유지 turnId={} 원문=[{}] 응답=[{}]", turnId, question, raw);
        }
    }

    /** 재료 = 이 턴 <b>앞의</b> 최근 {@value #MATERIAL_TURNS} 질문, 오래된 것부터(지금 턴은 이미 저장돼 있어 뺀다). */
    private String buildHistory(String userId, String threadId, long turnId) {
        List<MemoryRepository.Turn> turns;
        try {
            turns = memoryService.getRecentTurns(userId, threadId);
        } catch (Exception e) {
            log.debug("[CLARIFY] 이전 질문 조회 실패 thread={}: {}", threadId, e.getMessage());
            return "";
        }
        if (turns == null || turns.isEmpty()) return "";
        List<String> earlier = turns.stream()
                .filter(t -> t.id() != turnId)
                .map(MemoryRepository.Turn::question)
                .filter(q -> q != null && !q.isBlank())
                .toList();
        List<String> lines = new ArrayList<>();
        for (int i = Math.max(0, earlier.size() - MATERIAL_TURNS); i < earlier.size(); i++) {
            String one = earlier.get(i).strip().replaceAll("\\s+", " ");
            if (one.length() > MAX_MATERIAL_QUESTION_CHARS) one = one.substring(0, MAX_MATERIAL_QUESTION_CHARS) + "…";
            lines.add("- " + one);
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

    /**
     * 첫 번째 비어 있지 않은 줄만 — 감싼 따옴표와 흔한 머리말을 벗긴다. 길이를 넘기면 잘라 쓰지 않고
     * 버린다({@code QuestionCondenser.parse} 와 같은 규칙: 그 길이는 질문 한 줄이 아니라 설명을 냈다는 뜻이다).
     */
    static String parse(String response) {
        if (response == null || response.isBlank()) return null;
        String line = response.lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
        line = LABEL_PREFIX.matcher(line).replaceFirst("").strip();
        if (line.length() >= 2
                && ((line.startsWith("\"") && line.endsWith("\""))
                 || (line.startsWith("'") && line.endsWith("'"))
                 || (line.startsWith("“") && line.endsWith("”")))) {
            line = line.substring(1, line.length() - 1).strip();
        }
        if (line.isEmpty() || line.length() > MAX_CLARIFIED_CHARS) return null;
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

    /**
     * 제목 생성과 같은 인덱싱/백그라운드 temperature — 다듬기는 창의 작업이 아니다. 핫이라 매 호출 다시 읽는다.
     * 생각은 끈다(클래스 주석) — 실을지는 받는 프로바이더가 정한다.
     */
    private OpenAiChatOptions options() {
        OpenAiChatOptions.Builder builder = ThinkingOffChatModel.requestOff(OpenAiChatOptions.builder()
                .temperature(props.llmSafe().indexingTemperature()));
        int configured = props.llmSafe().maxTokens();
        if (configured > 0) builder.maxTokens(Math.min(configured, MAX_OUTPUT_TOKENS));
        return builder.build();
    }
}
