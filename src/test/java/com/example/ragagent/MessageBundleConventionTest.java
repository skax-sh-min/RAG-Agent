package com.example.ragagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메시지 번들 규약 — 여러 줄 값은 줄 끝마다 {@code \n\} 로 잇고, 두 번들의 키는 같다.
 *
 * <p>{@code .properties} 는 줄 끝에 {@code \} 가 없으면 거기서 값을 끝낸다. 그러면 <b>첫 줄만 값</b>이 되고, 그 아래
 * 줄들은 첫 낱말을 키로 삼은 엉뚱한 항목이 된다 — 오류도 경고도 없다. 실제로 독립화·큐레이션 질문 제안 프롬프트가
 * 첫 문장만 남은 채(재료·규칙과 {@code {history}}·{@code {query}} 자리가 전부 빠진 채) 모델에 갔고, 검색 0건 답변은
 * "## 요약" 제목만 보였다. 그 값들을 쓰는 서비스의 테스트는 {@code MessageSource} 를 목으로 바꿔 의도한 문장을
 * 넣었으므로 아무것도 몰랐다. 그렇게 생긴 항목은 <b>키 모양</b>으로 드러난다 — 한국어 낱말, {@code [블록]},
 * {@code {자리표시자}}, {@code -} 가 키가 된다.
 */
class MessageBundleConventionTest {

    private static final Path EN = Path.of("src/main/resources/messages.properties");
    private static final Path KO = Path.of("src/main/resources/messages_ko.properties");

    /** 이 앱의 키 모양 — 점으로 이은 식별자({@code prompt.retrieval.condense}, {@code chat.answer.no-documents}). */
    private static final Pattern KEY_SHAPE = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+");

    @Test
    @DisplayName("모든 키가 점으로 이은 식별자다 — 아니면 바로 위 여러 줄 값의 줄 끝 \\n\\ 가 빠져 그 줄이 키가 된 것이다")
    void everyKeyIsADottedIdentifier() throws IOException {
        for (Path bundle : List.of(EN, KO)) {
            List<String> stray = load(bundle).stringPropertyNames().stream()
                    .filter(key -> !KEY_SHAPE.matcher(key).matches())
                    .sorted()
                    .toList();
            assertThat(stray)
                    .as("%s — 이 낱말들로 시작하는 줄의 바로 위 값에서 줄 끝 \\n\\ 가 빠졌다", bundle)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("한/영 번들의 키가 같다 — 한쪽에만 있으면 다른 언어 화면에 키가 그대로 나오거나 다른 언어가 섞인다")
    void bothBundlesCarryTheSameKeys() throws IOException {
        Set<String> en = new TreeSet<>(load(EN).stringPropertyNames());
        Set<String> ko = new TreeSet<>(load(KO).stringPropertyNames());

        Set<String> onlyKo = new TreeSet<>(ko);
        onlyKo.removeAll(en);
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(ko);
        assertThat(onlyKo).as("한국어 번들에만 있는 키").isEmpty();
        assertThat(onlyEn).as("영어 번들에만 있는 키").isEmpty();
    }

    /** 앱과 같은 방식으로 읽는다 — Spring Boot 의 메시지 소스는 UTF-8 이다. */
    private static Properties load(Path bundle) throws IOException {
        assertThat(bundle).as("번들 파일이 옮겨졌다면 이 테스트의 경로도 고칠 것").exists();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
