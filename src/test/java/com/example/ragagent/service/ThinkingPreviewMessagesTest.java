package com.example.ragagent.service;

import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingWire;
import com.example.ragagent.model.ThinkingPreview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생각 수준 카드의 문구(PLAN §6.29 ⑦)가 한/영 번들에 <b>빠짐도 남음도 없이</b> 있는가.
 *
 * <p>서비스가 푸는 문구는 키가 빠지면 계산 시점에 예외가 나지만, 그 예외는 <b>그 경로를 지나야</b> 난다 — 사이트·수준·배지 종류의
 * 모든 조합을 한 번의 테스트 실행이 지나가지는 못한다. 템플릿이 푸는 문구는 더 나쁘다: 빠진 키는 오류 없이 {@code ??key_ko??}
 * 로만 보인다. 그래서 키를 <b>소스에서 긁어</b> 두 번들과 대조한다. 반대로 번들에 있는데 아무도 쓰지 않는 키(고친 뒤 남은 문구)도
 * 잡는다 — 그런 문구는 다음 사람이 "이건 어디서 나오지" 하고 헤매게 한다.
 */
class ThinkingPreviewMessagesTest {

    private static final Path EN = Path.of("src/main/resources/messages.properties");
    private static final Path KO = Path.of("src/main/resources/messages_ko.properties");
    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java/com/example/ragagent/service/ThinkingPreviewService.java"),
            Path.of("src/main/java/com/example/ragagent/model/ThinkingPreview.java"),
            Path.of("src/main/java/com/example/ragagent/model/SettingsView.java"),
            Path.of("src/main/resources/templates/fragments/settings-thinking.html"),
            Path.of("src/main/resources/templates/fragments/settings-providers.html"),
            Path.of("src/main/resources/templates/settings.html"));

    /** 점으로 끝나는 것은 {@code 'settings.thinking.level.' + …} 같은 <b>접두</b>다 — 아래 생성 목록이 대신한다. */
    private static final Pattern KEY = Pattern.compile("settings\\.(?:thinking|col\\.thinking)[a-z0-9.\\-]*");

    private static Set<String> literalKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Path source : SOURCES) {
            Matcher m = KEY.matcher(Files.readString(source, StandardCharsets.UTF_8));
            while (m.find()) {
                String key = m.group();
                if (!key.endsWith(".") && !key.equals("settings.thinking")) keys.add(key);
            }
        }
        keys.add("settings.col.thinking-control");
        return keys;
    }

    /** 문자열 연결로 만들어져 소스에서 한 덩어리로 보이지 않는 키들 — 열거형이 단일 출처다. */
    private static Set<String> generatedKeys() {
        Set<String> keys = new TreeSet<>();
        for (ThinkingLevel level : ThinkingLevel.values()) keys.add("settings.thinking.level." + level.value());
        for (ThinkingSite.Group group : ThinkingSite.Group.values()) keys.add("settings.thinking.group." + group.id());
        for (ThinkingSite site : ThinkingSite.values()) keys.add("settings.thinking.site." + site.id());
        for (ThinkingPreview.Badge.Kind kind : ThinkingPreview.Badge.Kind.values()) {
            keys.add(kind.messageKey());
            keys.add(kind.messageKey() + ".hint");
        }
        for (ThinkingPreview.Use use : ThinkingPreview.Use.values()) keys.add(use.messageKey());
        for (ThinkingDialect.Support support : ThinkingDialect.Support.values()) {
            keys.add("settings.thinking.support." + support.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        for (ThinkingWire.Sent sent : ThinkingWire.Sent.values()) {
            keys.add("settings.thinking.sent." + sent.name().toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private static Properties load(Path bundle) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    @Test
    @DisplayName("소스와 템플릿이 쓰는 키는 한/영 번들에 모두 있다 — 빠지면 화면에 ??key?? 가 뜬다")
    void everyKeyInUseExistsInBothBundles() throws IOException {
        Set<String> used = new TreeSet<>(literalKeys());
        used.addAll(generatedKeys());

        for (Path bundle : List.of(EN, KO)) {
            Set<String> have = load(bundle).stringPropertyNames();
            Set<String> missing = new TreeSet<>(used);
            missing.removeAll(have);
            assertThat(missing).as("%s 에 없는 키", bundle).isEmpty();
        }
    }

    @Test
    @DisplayName("번들의 settings.thinking.* 키는 전부 어딘가에서 쓰인다 — 쓰이지 않는 문구가 남지 않는다")
    void noUnusedKeysRemain() throws IOException {
        Set<String> used = new TreeSet<>(literalKeys());
        used.addAll(generatedKeys());

        for (Path bundle : List.of(EN, KO)) {
            Set<String> stale = new TreeSet<>();
            for (String key : load(bundle).stringPropertyNames()) {
                if ((key.startsWith("settings.thinking.") || key.equals("settings.col.thinking-control")) && !used.contains(key)) {
                    stale.add(key);
                }
            }
            assertThat(stale).as("%s 에서 아무도 쓰지 않는 키", bundle).isEmpty();
        }
    }

    @Test
    @DisplayName("한/영 번들에서 인자 자리({0}…)의 개수가 같다 — 한쪽만 인자를 하나 더 받으면 영어 화면이 숫자를 잃는다")
    void placeholdersAgreeBetweenLanguages() throws IOException {
        Properties en = load(EN);
        Properties ko = load(KO);
        Pattern slot = Pattern.compile("\\{(\\d+)}");
        for (String key : en.stringPropertyNames()) {
            if (!key.startsWith("settings.thinking.")) continue;
            assertThat(slots(ko.getProperty(key), slot)).as(key).isEqualTo(slots(en.getProperty(key), slot));
        }
    }

    private static Set<Integer> slots(String value, Pattern slot) {
        Set<Integer> out = new TreeSet<>();
        if (value == null) return out;
        Matcher m = slot.matcher(value);
        while (m.find()) out.add(Integer.parseInt(m.group(1)));
        return out;
    }

    @Test
    @DisplayName("인자가 있는 영어 문구에는 작은따옴표가 없다 — MessageFormat 이 따옴표 안을 인자로 풀지 않아 숫자가 사라진다")
    void noApostropheInParameterisedMessages() throws IOException {
        for (Path bundle : List.of(EN, KO)) {
            Properties p = load(bundle);
            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith("settings.thinking.")) continue;
                String value = p.getProperty(key);
                if (value.contains("{0}") || value.contains("{1}")) {
                    assertThat(value).as("%s / %s", bundle.getFileName(), key).doesNotContain("'");
                }
            }
        }
    }

    @Test
    @DisplayName("이 테스트가 실제로 긁어낸다 — 키 수가 0 이면 정규식이 깨진 것이다")
    void theScraperFindsKeys() throws IOException {
        assertThat(literalKeys()).hasSizeGreaterThan(40).contains("settings.thinking.title", "settings.thinking.basis.room");
        assertThat(generatedKeys()).hasSizeGreaterThan(ThinkingSite.values().length);
    }
}
