package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("출하 기본값 — 이미 생각을 끄던 세 자리만 끔, 나머지는 낮게(참조 배포에서 지금과 같은 전송 결과)")
    void shippedDefaults() {
        assertThat(Arrays.stream(ThinkingSite.values())
                .filter(s -> s.shippedDefault() == ThinkingLevel.OFF))
                .containsExactlyInAnyOrder(ThinkingSite.CONDENSE, ThinkingSite.POST_ANSWER, ThinkingSite.CURATED_SUGGEST);
        assertThat(Arrays.stream(ThinkingSite.values())
                .filter(s -> s.shippedDefault() != ThinkingLevel.OFF))
                .allMatch(s -> s.shippedDefault() == ThinkingLevel.LOW);
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
}
