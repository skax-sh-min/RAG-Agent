package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN §6.29 — LLM 호출부는 자기 호출 지점({@link ThinkingSite})을 거쳐서만 라우팅하고, 옵션에 그 사이트를 표시한다.
 *
 * <p>둘 다 코드로는 아무도 눈치채지 못하는 규칙이라 빌드로 막는다:
 * <ul>
 *   <li><b>표시 없는 옵션</b> — {@code OpenAiChatOptions.builder()} 를 {@link ThinkingControl#mark} 로 감싸지 않으면
 *       그 호출은 생각 수준 설정이 아무 효과가 없는 채로 나간다. 오류도 로그도 없다.</li>
 *   <li><b>{@code TaskType} 리터럴 라우팅</b> — {@code /settings} 미리보기(5단계)는 "이 사이트를 누가 받는가"를
 *       {@code ThinkingSite} 의 라우팅으로 계산한다. 호출부가 리터럴로 라우팅하면 화면과 실제가 조용히 갈라진다.</li>
 *   <li><b>체인을 우회하는 직행 호출</b> — 프로바이더의 날것 {@code OpenAiApi} 로 부르면 데코레이터 체인(생각 수준을
 *       싣는 {@code ThinkingControlChatModel})을 통째로 지나지 않는다. 그 길은 채팅 답변 스트리밍 하나뿐이고, 같은 규칙을
 *       {@code AnswerStreamer} 가 따로 싣는다 — 다른 곳에서 열면 그 호출은 생각 수준이 아무 효과가 없다.</li>
 * </ul>
 * 예외는 프로바이더 빈을 만드는 {@code LlmConfig}(기본 옵션·{@code @Primary} 모델), 라우터 자신, 그리고 표시를 구현하는
 * {@code ThinkingControl}(옵션이 없는 프롬프트에 표시하려고 빈 빌더를 쓴다)뿐이다.
 */
class ThinkingSiteConventionTest {

    private static final Path SRC = Path.of("src/main/java");
    private static final Set<String> EXEMPT = Set.of("LlmConfig.java", "LlmRouter.java", "ThinkingControl.java");

    /** 라우터의 라우팅·실행 메서드에 TaskType 리터럴(또는 그 목록)을 첫 인자로 넘기는 호출. */
    private static final Pattern LITERAL_ROUTING = Pattern.compile(
            "\\b(executeGated|executeGatedWithUsage|executeWithTracking|routeProvider|routeProviderWithFallback"
                    + "|findProviderName|route)\\s*\\(\\s*(List\\.of\\(\\s*)?TaskType\\.");

    private static final Pattern OPTIONS_BUILDER = Pattern.compile("OpenAiChatOptions\\.builder\\(\\)");
    private static final Pattern MARKED_BUILDER = Pattern.compile("ThinkingControl\\.mark\\(\\s*OpenAiChatOptions\\.builder\\(\\)");

    @Test
    @DisplayName("라우터 호출은 TaskType 리터럴이 아니라 ThinkingSite 의 라우팅을 쓴다")
    void routingComesFromSites() {
        List<String> violations = new ArrayList<>();
        forEachSource((file, text) -> {
            Matcher m = LITERAL_ROUTING.matcher(text);
            while (m.find()) violations.add(file + ":" + lineOf(text, m.start()) + " — " + m.group(1) + "(TaskType…)");
        });
        assertThat(violations)
                .as("site.taskType() / site.fixedRoutingMode() / site.routingMode(대화의 모드) 를 쓴다")
                .isEmpty();
    }

    @Test
    @DisplayName("OpenAiChatOptions.builder() 는 ThinkingControl.mark(...) 로 감싼다 — 표시 없는 호출은 생각 수준이 먹지 않는다")
    void everyOptionsBuilderIsMarked() {
        List<String> violations = new ArrayList<>();
        forEachSource((file, text) -> {
            long builders = OPTIONS_BUILDER.matcher(text).results().count();
            long marked = MARKED_BUILDER.matcher(text).results().count();
            if (builders != marked) {
                violations.add("%s — OpenAiChatOptions.builder() %d곳 중 표시된 곳 %d".formatted(file, builders, marked));
            }
        });
        assertThat(violations)
                .as("ThinkingControl.mark(OpenAiChatOptions.builder()..., ThinkingSite.X) 로 쓴다")
                .isEmpty();
    }

    /** 프로바이더의 날것 {@code OpenAiApi} 를 꺼내는 자리 — 스트리밍이든 블로킹이든 체인을 우회한다. */
    private static final Pattern RAW_API = Pattern.compile("\\.openAiApi\\(\\)");
    private static final String STREAMER = "service/AnswerStreamer.java";

    @Test
    @DisplayName("체인을 우회하는 OpenAiApi 직행 호출은 AnswerStreamer 한 곳에서만 연다 — 다른 곳은 생각 수준이 먹지 않는다")
    void rawApiCallsGoThroughTheStreamer() {
        List<String> violations = new ArrayList<>();
        forEachSource((file, text) -> {
            if (file.endsWith(STREAMER)) return;
            String code = withoutComments(text);
            Matcher m = RAW_API.matcher(code);
            while (m.find()) violations.add(file + ":" + lineOf(code, m.start()) + " — provider.openAiApi()");
        });
        assertThat(violations)
                .as("채팅 답변 스트리밍은 AnswerStreamer.stream() 을 쓴다(생각 수준·생각 델타·거부 재시도·관측이 거기 있다)")
                .isEmpty();
    }

    /**
     * PLAN §6.29 ④ — 예약을 믿는 자리(입력 예산·이력 예산)는 생각 여유까지 더한 예약을 써야 한다. 옛 상수로 예산을
     * 재는 자리가 하나라도 있으면 그 계산이 실제 요청(여유가 더해진 {@code max_tokens})과 조용히 갈라진다.
     */
    private static final List<String> BUDGET_CALLS = List.of("new PromptBudget(", "HistoryPolicy.budgetChars(");
    /** 이 둘은 받은 예약을 그대로 쓰는 자리(예산 계산 자체)라 예외다. */
    private static final Set<String> BUDGET_EXEMPT = Set.of("PromptBudget.java", "HistoryPolicy.java");

    @Test
    @DisplayName("§6.29 ④ — 예산에 넘기는 출력 예약은 ThinkingBudget 을 지난 값(Reservation.tokens())이다")
    void budgetsTakeTheThinkingAwareReservation() {
        List<String> violations = new ArrayList<>();
        forEachSource((file, text) -> {
            if (BUDGET_EXEMPT.stream().anyMatch(file::endsWith)) return;
            String code = withoutComments(text);
            for (String call : BUDGET_CALLS) {
                for (int at = code.indexOf(call); at >= 0; at = code.indexOf(call, at + 1)) {
                    String args = argumentsAt(code, at + call.length());
                    if (!args.contains(".tokens()")) {
                        violations.add(file + ":" + lineOf(code, at) + " — " + call + args + ")");
                    }
                }
            }
        });
        assertThat(violations)
                .as("AnswerService.answerReservation(…)/evalReservation(…).tokens() 로 넘긴다 — 기본 예약을 그대로 빼면 "
                        + "생각을 켠 호출의 예산이 실제 요청보다 크게 잡힌다")
                .isEmpty();
    }

    @Test
    @DisplayName("§6.29 ④ — 기본 예약의 원천(MAX_EVAL_OUTPUT_TOKENS·outputReservation)은 그 함수 안에서만 읽는다")
    void baseReservationsAreReadOnlyThroughTheirFunction() {
        List<String> violations = new ArrayList<>();
        forEachSource((file, text) -> {
            String code = withoutComments(text);
            long evalConstant = Pattern.compile("\\bMAX_EVAL_OUTPUT_TOKENS\\b").matcher(code).results().count();
            // 맨 이름이나 AnswerService. 로 부르는 것만 — budget.outputReservation() 은 PromptBudget 레코드의 접근자다
            long answerBase = Pattern.compile("(?:\\bAnswerService\\.|(?<![.\\w]))outputReservation\\(")
                    .matcher(code).results().count();
            boolean owner = file.endsWith("service/AnswerService.java");
            // 선언 1 + 그 값을 읽는 함수 1(evalBaseReservation / answerReservation)
            if (evalConstant > (owner ? 2 : 0)) {
                violations.add(file + " — MAX_EVAL_OUTPUT_TOKENS " + evalConstant + "곳 (evalReservation 을 쓸 것)");
            }
            if (answerBase > (owner ? 2 : 0)) {
                violations.add(file + " — outputReservation( " + answerBase + "곳 (answerReservation 을 쓸 것)");
            }
        });
        assertThat(violations).isEmpty();
    }

    /** {@code code} 의 {@code from} 위치(여는 괄호 다음)부터 짝이 맞는 닫는 괄호 앞까지. */
    private static String argumentsAt(String code, int from) {
        int depth = 1;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return code.substring(from, i);
        }
        return code.substring(from);
    }

    @Test
    @DisplayName("이 검사가 실제로 잡는다 — 위반 모양을 넣으면 걸린다")
    void patternsCatchViolations() {
        assertThat(argumentsAt("new PromptBudget(window, MAX_EVAL_OUTPUT_TOKENS).inputBudget()", 17))
                .isEqualTo("window, MAX_EVAL_OUTPUT_TOKENS").doesNotContain(".tokens()");
        assertThat(argumentsAt("new PromptBudget(window, evalReservation(state, p).tokens()).inputBudget()", 17))
                .contains(".tokens()");
        assertThat(LITERAL_ROUTING.matcher("llmRouter.executeGated(TaskType.TEXT, RoutingMode.COST_FIRST,").find()).isTrue();
        assertThat(LITERAL_ROUTING.matcher("routeProviderWithFallback(\n    List.of(TaskType.MICRO_TEXT, TaskType.TEXT)").find()).isTrue();
        assertThat(LITERAL_ROUTING.matcher("executeWithTracking(\n                    TaskType.VISION,").find()).isTrue();
        assertThat(LITERAL_ROUTING.matcher("executeGated(site.taskType(), site.fixedRoutingMode(),").find()).isFalse();
        assertThat(MARKED_BUILDER.matcher("ThinkingControl.mark(OpenAiChatOptions.builder()\n .temperature(t), site)").find()).isTrue();
        assertThat(RAW_API.matcher(withoutComments("provider.openAiApi().chatCompletionStream(request)")).find()).isTrue();
        assertThat(RAW_API.matcher(withoutComments("// provider.openAiApi() 를 직접 부르지 않는다\n/** {@code x.openAiApi()} */"))
                .find()).as("주석 속 언급은 위반이 아니다").isFalse();
        assertThat(Files.exists(SRC.resolve("com/example/ragagent/" + STREAMER)))
                .as("예외로 둔 파일이 실제로 있다 — 옮겨지면 이 검사가 아무것도 막지 않게 된다").isTrue();
    }

    /**
     * 주석을 걷어낸 코드 — 주석 속 언급까지 위반으로 세지 않게. 블록 주석은 줄바꿈만 남겨 위반의 줄 번호가 맞게 한다.
     * 문자열 안의 {@code //}·{@code /*}(URL·경로 패턴)는 코드를 조금 더 지울 뿐이라, 놓칠 수는 있어도 없는 위반을 만들지는 않는다.
     */
    private static String withoutComments(String java) {
        Matcher block = Pattern.compile("(?s)/\\*.*?\\*/").matcher(java);
        StringBuilder sb = new StringBuilder();
        while (block.find()) {
            block.appendReplacement(sb, Matcher.quoteReplacement(block.group().replaceAll("[^\\n]", "")));
        }
        block.appendTail(sb);
        return sb.toString().replaceAll("//[^\\n]*", "");
    }

    private interface SourceVisitor {
        void visit(String file, String text);
    }

    private static void forEachSource(SourceVisitor visitor) {
        try (Stream<Path> files = Files.walk(SRC)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !EXEMPT.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            visitor.visit(SRC.relativize(p).toString().replace('\\', '/'),
                                    Files.readString(p, StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
