package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 재검색 루프(§6.33, {@link SearchLoopService})의 <b>새 검색어 하나</b>를 LLM 으로 만든다.
 *
 * <p><b>재검색을 할지는 여기서 정하지 않는다.</b> 작은 로컬 모델은 "다시 찾을까"를 스스로 판단하지 못했고(무관한 결과를 줘도
 * 12건 중 0건 재검색, JSON 판정은 거꾸로 답이 있어도 12건 중 10건 "부족" — 설계서 부록 A.3) 새 검색어를 쓰는 일은 안정적이었다
 * (34건 중 32건). 그래서 판단은 리랭커 점수로 코드가 하고({@code SearchLoopService}) 이 클래스는 문장 하나만 만든다.
 *
 * <p>호출 지점은 {@link ThinkingSite#CONDENSE} 를 그대로 쓴다 — 한 줄짜리 검색 질의를 만드는 같은 성질의 호출이고, 긴 질문의
 * {@code QuestionCondenser.distill()} 이 이미 새 사이트를 만들지 않고 같은 자리를 쓴다(생각 끔 · 출력 예약 256 · 라우팅 고정).
 * 설계서 초안의 {@code SEARCH_REWRITE} 는 만들지 않았다: 사이트가 늘면 생각 수준 카드·미리보기·문서의 "21곳"이 함께 바뀌는데 얻는 것은
 * 별도 손잡이 하나뿐이다.
 *
 * <p>실패는 전부 {@link Optional#empty()} — 호출자는 그때까지 찾은 결과로 진행한다.
 */
@Service
public class SearchQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(SearchQueryRewriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 새 검색어의 길이 상한. 넘으면 버린다 — 설명을 쓴 것이고 앞부분만 잘라 검색어로 쓰면 원문보다 나쁜 질의가 된다. */
    static final int MAX_QUERY_CHARS = 200;
    /** 프롬프트에 싣는 지금까지 찾은 문서의 수와 문서당 글자 수(설계서 §5.2). */
    static final int FOUND_EXCERPTS = 3;
    static final int FOUND_EXCERPT_CHARS = 160;

    private static final Pattern NEXT_QUERY =
            Pattern.compile("\"nextQuery\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);

    /** 새 검색어와 그 호출의 토큰 사용량 — 그래프 노드 안의 호출이라 호출부가 상태에 더한다({@code QuestionCondenser.Condensed} 와 같은 이유). */
    public record Next(String query, int inputTokens, int outputTokens) {}

    private final LlmRouter llmRouter;
    private final QuestionCondenser condenser;
    private final MessageSource messageSource;
    private final AppProperties props;

    public SearchQueryRewriter(LlmRouter llmRouter, QuestionCondenser condenser,
                               MessageSource messageSource, AppProperties props) {
        this.llmRouter = llmRouter;
        this.condenser = condenser;
        this.messageSource = messageSource;
        this.props = props;
    }

    /**
     * @param question    사용자의 원문 질문(답변이 보는 것과 같다)
     * @param usedQueries 지금까지 검색에 쓴 검색어(독립화된 질문 포함, 첫 검색어부터 순서대로)
     * @param found       직전 검색에서 리랭커가 고른 문서(점수순) — 앞 {@value #FOUND_EXCERPTS}개의 앞부분만 싣는다
     * @return 새 검색어. 호출·파싱이 실패했거나 비었거나 이미 쓴 검색어와 같으면 비어 있다
     */
    public Optional<Next> nextQuery(String userId, String threadId, String question, List<String> usedQueries,
                                    List<Document> found, Locale locale) {
        try {
            String history = condenser.buildMaterial(userId, threadId);
            String systemPrompt = messageSource.getMessage("prompt.retrieval.rewrite", null, locale)
                    .replace("{history}", history.isBlank() ? "-" : history)
                    .replace("{used}", usedBlock(usedQueries))
                    .replace("{found}", foundBlock(found));
            String userMessage = PromptInjectionGuard.wrap(question);
            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                    ThinkingSite.CONDENSE.taskType(), ThinkingSite.CONDENSE.fixedRoutingMode(),
                    model -> model.call(new Prompt(
                            List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)), options())));
            String next = parse(result.text());
            if (next == null) {
                log.debug("[SEARCH-REWRITE] 새 검색어 없음(파싱 실패) thread={}", threadId);
                return Optional.empty();
            }
            if (isUsed(next, usedQueries)) {
                log.debug("[SEARCH-REWRITE] 이미 쓴 검색어 thread={} query=[{}]", threadId, next);
                return Optional.empty();
            }
            log.info("[SEARCH-REWRITE] 재검색어 thread={} 쓴 검색어={} 새 검색어=[{}]", threadId, usedQueries, next);
            return Optional.of(new Next(next, result.inputTokens(), result.outputTokens()));
        } catch (Exception e) {
            // 검색을 멈출 이유가 아니다 — 그때까지 찾은 결과로 답한다.
            log.warn("[SEARCH-REWRITE] 새 검색어 생성 실패 — 찾은 결과로 진행한다 thread={}: {}", threadId, e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code {"nextQuery": "..."}} 에서 검색어를 읽는다. JSON 앞뒤의 머리말·코드 펜스는 무시한다. 비었거나 길이를 넘기면 {@code null}. */
    static String parse(String response) {
        if (response == null || response.isBlank()) return null;
        Matcher m = NEXT_QUERY.matcher(response);
        if (!m.find()) return null;
        String raw = m.group(1);
        String query;
        try {
            query = MAPPER.readValue("\"" + raw + "\"", String.class);
        } catch (RuntimeException e) {
            query = raw;   // 이스케이프가 깨졌으면 있는 그대로 — 검색어로는 충분하다
        }
        query = query.strip().replaceAll("\\s+", " ");
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) return null;
        return query;
    }

    /** 공백·대소문자만 다른 것도 같은 검색어다 — 같은 검색을 한 번 더 돌리지 않는다. */
    static boolean isUsed(String candidate, List<String> usedQueries) {
        String c = candidate.strip().replaceAll("\\s+", " ");
        for (String u : usedQueries) {
            if (u != null && u.strip().replaceAll("\\s+", " ").equalsIgnoreCase(c)) return true;
        }
        return false;
    }

    private static String usedBlock(List<String> usedQueries) {
        StringBuilder sb = new StringBuilder();
        for (String q : usedQueries) {
            if (q == null || q.isBlank()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("- ").append(q.strip().replaceAll("\\s+", " "));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String foundBlock(List<Document> found) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Document d : found) {
            if (n >= FOUND_EXCERPTS) break;
            String text = d.getText() == null ? "" : d.getText().strip().replaceAll("\\s+", " ");
            if (text.isEmpty()) continue;
            if (text.length() > FOUND_EXCERPT_CHARS) text = text.substring(0, FOUND_EXCERPT_CHARS) + "…";
            if (sb.length() > 0) sb.append('\n');
            sb.append(++n).append(". ").append(text);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** 분류기·독립화와 같은 일반 temperature(핫). 생각 수준·출력 예약은 {@code CONDENSE} 사이트 그대로다. */
    private OpenAiChatOptions options() {
        OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature()), ThinkingSite.CONDENSE);
        int base = QuestionCondenser.baseReservation(props.llmSafe().maxTokens());
        if (base > 0) builder.maxTokens(base);
        return builder.build();
    }
}
