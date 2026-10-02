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

    @Test
    @DisplayName("이 검사가 실제로 잡는다 — 위반 모양을 넣으면 걸린다")
    void patternsCatchViolations() {
        assertThat(LITERAL_ROUTING.matcher("llmRouter.executeGated(TaskType.TEXT, RoutingMode.COST_FIRST,").find()).isTrue();
        assertThat(LITERAL_ROUTING.matcher("routeProviderWithFallback(\n    List.of(TaskType.MICRO_TEXT, TaskType.TEXT)").find()).isTrue();
        assertThat(LITERAL_ROUTING.matcher("executeWithTracking(\n                    TaskType.VISION,").find()).isTrue();
        assertThat(LITERAL_ROUTING.matcher("executeGated(site.taskType(), site.fixedRoutingMode(),").find()).isFalse();
        assertThat(MARKED_BUILDER.matcher("ThinkingControl.mark(OpenAiChatOptions.builder()\n .temperature(t), site)").find()).isTrue();
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
