package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ragagent.model.ResponseMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 호출 지점 목록과 출하 기본값(PLAN §6.29 ②). 출하값은 {@code ThinkingSite} 와 {@code application.properties} 두 곳에
 * 있다 — 파일은 운영자가 보고 고치는 자리이고 열거형은 그 줄이 빠지거나 틀렸을 때 떨어지는 자리라 둘 다 필요하고,
 * 그래서 둘이 같은지를 여기서 지킨다.
 */
class ThinkingSiteTest {

    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Pattern THINKING_LINE = Pattern.compile("^app\\.llm\\.thinking\\.([a-z0-9-]+)=(\\S+)\\s*$");

    @Test
    @DisplayName("id 는 겹치지 않는 kebab-case 이고, byId 로 돌아온다")
    void idsAreUniqueKebabCase() {
        assertThat(Arrays.stream(ThinkingSite.values()).map(ThinkingSite::id)).doesNotHaveDuplicates()
                .allMatch(id -> id.matches("[a-z0-9]+(-[a-z0-9]+)*"));
        for (ThinkingSite site : ThinkingSite.values()) {
            assertThat(ThinkingSite.byId(site.id())).contains(site);
            assertThat(site.propertyKey()).isEqualTo("app.llm.thinking." + site.id());
            assertThat(site.settingsKey()).isEqualTo("llm.thinking." + site.id());
        }
        assertThat(ThinkingSite.byId("nope")).isEmpty();
        assertThat(ThinkingSite.byId(null)).isEmpty();
    }

    @Test
    @DisplayName("출하 기본값 — 6단계 실측: 생각이 이득을 준 검증·응용 답변과 측정하지 못한 요약·이미지 셋만 낮게, 나머지는 끔")
    void shippedDefaults() {
        assertThat(Arrays.stream(ThinkingSite.values())
                .filter(s -> s.shippedDefault() == ThinkingLevel.LOW))
                .containsExactlyInAnyOrder(
                        // 실측(2026-10-07)에서 생각이 이득을 줬다 — 문서가 받쳐 주지 않는 답을 잡는 비율 6% → 44%, 응용 코드가
                        // 문서의 API 를 따르는 정도
                        ThinkingSite.EVAL, ThinkingSite.EVAL_CREATIVE, ThinkingSite.ANSWER_RAG_C,
                        // 그 구성에서 측정하지 못했다(소형 모델 계층이 없으면 요약은 LLM 을 안 부른다 · 비전 모델 없음) — 옛 값 그대로
                        ThinkingSite.SUMMARY, ThinkingSite.IMAGE_DESCRIBE, ThinkingSite.IMAGE_TYPE,
                        ThinkingSite.MD_CORRECT_VISION);
        assertThat(Arrays.stream(ThinkingSite.values())
                .filter(s -> s.shippedDefault() != ThinkingLevel.LOW))
                .allMatch(s -> s.shippedDefault() == ThinkingLevel.OFF);
    }

    @Test
    @DisplayName("application.properties 에 사이트마다 한 줄 — 값이 출하 기본값과 같고, 모르는 사이트 줄이 없다")
    void propertiesFileMatchesShippedDefaults() throws IOException {
        Map<String, String> lines = new LinkedHashMap<>();
        for (String line : Files.readAllLines(PROPERTIES, StandardCharsets.UTF_8)) {
            Matcher m = THINKING_LINE.matcher(line);
            if (m.matches()) {
                assertThat(lines.put(m.group(1), m.group(2))).as("중복 줄: %s", m.group(1)).isNull();
            }
        }
        Map<String, String> expected = Arrays.stream(ThinkingSite.values())
                .collect(Collectors.toMap(ThinkingSite::id, s -> s.shippedDefault().value(),
                        (a, b) -> a, LinkedHashMap::new));
        assertThat(lines).isEqualTo(expected);
    }

    @Test
    @DisplayName("수준 값은 대소문자·공백을 가리지 않고, 넷 중 하나가 아니면 비어 있다(서버 기본값 같은 값은 없다)")
    void levelParsing() {
        assertThat(ThinkingLevel.parse(" Low ")).contains(ThinkingLevel.LOW);
        assertThat(ThinkingLevel.parse("off")).contains(ThinkingLevel.OFF);
        assertThat(ThinkingLevel.parse("default")).isEmpty();
        assertThat(ThinkingLevel.parse("")).isEmpty();
        assertThat(ThinkingLevel.parse(null)).isEmpty();
        assertThat(ThinkingLevel.HIGH.value()).isEqualTo("high");
    }

    @Test
    @DisplayName("라우팅 — 채팅 답변·검증은 대화의 모드를 따르고, 나머지는 고정 모드를 갖는다")
    void routing() {
        for (ThinkingSite site : List.of(ThinkingSite.ANSWER_RAG_S, ThinkingSite.ANSWER_RAG_N, ThinkingSite.ANSWER_RAG_C,
                ThinkingSite.ANSWER_DIRECT_S, ThinkingSite.ANSWER_DIRECT_N, ThinkingSite.ANSWER_META,
                ThinkingSite.EVAL, ThinkingSite.EVAL_CREATIVE)) {
            assertThat(site.followsConversationRouting()).as(site.name()).isTrue();
            assertThat(site.taskType()).isEqualTo(TaskType.TEXT);
            assertThat(site.routingMode(RoutingMode.QUALITY_FIRST)).isEqualTo(RoutingMode.QUALITY_FIRST);
            assertThatThrownBy(site::fixedRoutingMode).isInstanceOf(IllegalStateException.class);
        }
        assertThat(ThinkingSite.SUMMARY.fixedRoutingMode()).isEqualTo(RoutingMode.LOCAL_ONLY);
        assertThat(ThinkingSite.SUMMARY.routingMode(RoutingMode.QUALITY_FIRST)).as("고정 모드가 이긴다")
                .isEqualTo(RoutingMode.LOCAL_ONLY);
        assertThat(ThinkingSite.MD_CORRECT_VISION.taskType()).isEqualTo(TaskType.VISION);
        assertThat(ThinkingSite.MD_CORRECT_VISION.fixedRoutingMode()).isEqualTo(RoutingMode.LOCAL_ONLY);
        assertThat(ThinkingSite.QUERY_EXPANSION.taskTypes())
                .as("작은 모델부터 내려간다(§6.21)")
                .containsExactly(TaskType.MICRO_TEXT, TaskType.LIGHT_TEXT, TaskType.TEXT);
        assertThat(ThinkingSite.QUERY_EXPANSION.taskType()).isEqualTo(TaskType.MICRO_TEXT);
    }

    @Test
    @DisplayName("응답 모드가 답변·검증 사이트를 정한다 — C 는 Direct 가 없고, C 의 검증은 전용 사이트다")
    void responseModeSites() {
        assertThat(ResponseMode.S.ragThinkingSite()).isEqualTo(ThinkingSite.ANSWER_RAG_S);
        assertThat(ResponseMode.N.ragThinkingSite()).isEqualTo(ThinkingSite.ANSWER_RAG_N);
        assertThat(ResponseMode.C.ragThinkingSite()).isEqualTo(ThinkingSite.ANSWER_RAG_C);
        assertThat(ResponseMode.S.directThinkingSite()).isEqualTo(ThinkingSite.ANSWER_DIRECT_S);
        assertThat(ResponseMode.N.directThinkingSite()).isEqualTo(ThinkingSite.ANSWER_DIRECT_N);
        assertThat(ResponseMode.C.directThinkingSite()).as("Direct 를 쓸 수 없는 모드").isNull();
        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(mode.directThinkingSite() != null).as(mode.name()).isEqualTo(mode.allowsDirect());
            assertThat(mode.evalThinkingSite())
                    .isEqualTo(mode.usesCreativeEval() ? ThinkingSite.EVAL_CREATIVE : ThinkingSite.EVAL);
        }
    }
}
