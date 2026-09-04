package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 큐레이션 Q&A 의 <b>질문</b>을 본문에서 더 구체적으로 다시 쓰자고 <b>제안</b>한다.
 *
 * <p><b>왜 질문인가.</b> 큐레이션 항목의 검색 텍스트는 {@code 질문 + 본문}이고, 질문은
 * <b>모든 청크에 반복 부여</b>된다({@code CuratedQaService.createFromSubmission}). 즉 문서 청크에서
 * {@code MetaKey#CHUNK_CONTEXT} 가 하는 일 — "이 조각이 무엇에 관한 것인지"를 임베딩 입력 앞에
 * 붙이는 것 — 을 여기서는 질문이 대신한다. 그래서 이 축의 검색 품질은 사실상 질문 문장 하나에
 * 달려 있는데, 정작 그 값은 좋아요한 턴의 원 질문이거나 사용자가 급히 적은 제목이라
 * {@code "그거 어떻게 해?"} 처럼 검색어로 쓸모없는 경우가 흔하다.
 *
 * <p><b>제안만 한다 — 저장하지 않는다.</b> 이 클래스는 문자열 하나를 돌려줄 뿐이고, 반영 여부는
 * 관리자가 화면에서 정한다. 질문을 자동으로 갈아치우면 그 항목이 어떤 질의에 걸릴지가 사람 몰래
 * 바뀌고, 큐레이션은 <b>사람의 검토</b>가 유일한 관문이라는 §10.11 의 전제와도 어긋난다.
 *
 * <p><b>배경 호출이다.</b> 관리자가 버튼을 눌러야만 돌고 채팅 경로가 아니므로 동시성 게이트를
 * 타지 않는다({@code executeWithTracking}) — {@code AdminService.reindexChunk} 의
 * {@code regenerateKeywords} 가 {@code KeywordExtractor} 를 부르는 것과 같은 성격이다.
 * 사용량은 {@link BackgroundUsage#QUESTION_PREFIX} 로 따로 잡힌다.
 */
@Service
public class CuratedQuestionSuggester {

    private static final Logger log = LoggerFactory.getLogger(CuratedQuestionSuggester.class);

    /**
     * 본문을 프롬프트에 넣을 때의 상한. 질문 한 줄을 뽑는 데 본문 전체가 필요하지 않고, 큐레이션
     * 본문은 승인 시 여러 청크로 나뉠 만큼 길 수 있다. 앞부분을 쓰는 이유는 이 축의 본문이
     * 대개 결론부터 쓰는 Q&A 형식이라 앞이 주제를 가장 잘 담기 때문이다.
     */
    static final int MAX_ANSWER_CHARS = 4_000;

    /** 제안 결과의 길이 상한. 넘으면 <b>버린다</b> — 질문 한 줄이 아니라 설명을 낸 것이고,
     *  앞부분만 잘라 쓰면 원래 질문보다 나쁜 검색어가 된다({@code QuestionCondenser} 와 같은 규칙). */
    static final int MAX_QUESTION_CHARS = 300;

    /** 한 줄짜리 응답에 프로바이더의 {@code max-tokens} 전체를 예약하지 않는다(§6.26). */
    static final int MAX_OUTPUT_TOKENS = 256;

    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final AppProperties props;

    public CuratedQuestionSuggester(LlmRouter llmRouter, MessageSource messageSource, AppProperties props) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.props = props;
    }

    /**
     * 본문을 근거로 더 구체적인 질문을 제안한다.
     *
     * @return 제안된 질문. 본문이 비었거나 · 모델이 현재 질문을 그대로 돌려줬거나 · 호출/파싱이
     *         실패하면 {@link Optional#empty()} — 그 경우 화면은 "제안할 것이 없다"고 말하고
     *         기존 질문을 건드리지 않는다
     */
    public Optional<String> suggest(String currentQuestion, String answer, Locale locale) {
        if (answer == null || answer.isBlank()) return Optional.empty();
        String body = answer.strip();
        if (body.length() > MAX_ANSWER_CHARS) body = body.substring(0, MAX_ANSWER_CHARS);
        try {
            String systemPrompt = messageSource.getMessage("prompt.curated.question", null, locale)
                    .replace("{question}", PromptInjectionGuard.wrap(
                            currentQuestion == null ? "" : currentQuestion))
                    .replace("{answer}", body);
            String response = llmRouter.executeWithTracking(
                    TaskType.MICRO_TEXT, RoutingMode.COST_FIRST, BackgroundUsage.QUESTION_PREFIX,
                    model -> model.call(new Prompt(
                            List.of(new SystemMessage(systemPrompt), new UserMessage("질문을 다시 써 주세요.")),
                            options())));
            String suggested = parse(response);
            if (suggested == null
                    || suggested.equalsIgnoreCase(currentQuestion == null ? "" : currentQuestion.strip())) {
                log.debug("[CURATED-Q] 제안 없음(현재 질문과 같거나 파싱 실패)");
                return Optional.empty();
            }
            log.info("[CURATED-Q] 질문 구체화 제안 현재=[{}] 제안=[{}]", currentQuestion, suggested);
            return Optional.of(suggested);
        } catch (Exception e) {
            log.warn("[CURATED-Q] 질문 제안 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 첫 번째 비어 있지 않은 줄만 취하고 감싼 따옴표를 벗긴다({@code QuestionCondenser.parse} 와 같은 규칙). */
    static String parse(String response) {
        if (response == null || response.isBlank()) return null;
        String line = response.lines()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("");
        if (line.length() >= 2
                && ((line.startsWith("\"") && line.endsWith("\""))
                 || (line.startsWith("'") && line.endsWith("'"))
                 || (line.startsWith("“") && line.endsWith("”")))) {
            line = line.substring(1, line.length() - 1).strip();
        }
        if (line.isEmpty() || line.length() > MAX_QUESTION_CHARS) return null;
        return line;
    }

    /** 인덱싱/백그라운드 온도 — 추출 성격의 작업이라 결정적으로 유지한다. 핫이라 매 호출 다시 읽는다. */
    private OpenAiChatOptions options() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(props.llmSafe().indexingTemperature());
        int configured = props.llmSafe().maxTokens();
        if (configured > 0) builder.maxTokens(Math.min(configured, MAX_OUTPUT_TOKENS));
        return builder.build();
    }
}
