package com.example.ragagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTMX 의 문자열 평가 기능을 쓰지 않는다 — CSP 가 {@code script-src 'self' 'unsafe-inline'} 이라 {@code 'unsafe-eval'} 이
 * 없다({@code SecurityConfig}).
 *
 * <p>HTMX 는 이벤트 필터({@code hx-trigger="toggle[this.open]"}), {@code hx-on}, {@code hx-vals="js:…"} 를
 * {@code Function()} 으로 만든다. CSP 가 그것을 막으면 EvalError 가 콘솔에 남고, HTMX 는 필터를 버린 채(필터 없이) 돈다 —
 * 쓴 사람이 기대한 조건이 조용히 빠진다. {@code /admin} 의 지연 로딩 카드 다섯이 그렇게 카드마다 오류 하나를 남기며
 * 필터 없는 {@code toggle once} 로 돌고 있었다. 조건이 필요하면 JS 리스너에서 확인할 것.
 */
class HtmxCspConventionTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/resources/templates"),
            Path.of("src/main/resources/static/js"));

    /** 필터가 붙은 트리거, hx-on, js: 로 시작하는 hx-vals/hx-vars. */
    private static final Pattern EVAL_FEATURE = Pattern.compile(
            "hx-trigger\\s*=\\s*\"[^\"]*\\[|\\bhx-on[:-]|\\bhx-on\\b|hx-va(?:ls|rs)\\s*=\\s*['\"]\\s*js:");

    @Test
    @DisplayName("템플릿·JS 에 HTMX 가 eval 로 만드는 기능(트리거 필터·hx-on·js: 값)이 없다 — CSP 에 unsafe-eval 이 없다")
    void noEvalBasedHtmxFeatures() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".html") || p.toString().endsWith(".js"))
                        .filter(p -> !p.toString().replace('\\', '/').contains("/vendor/")).toList()) {
                    String code = ChatFontSizeConventionTest.withoutComments(Files.readString(file));
                    Matcher m = EVAL_FEATURE.matcher(code);
                    while (m.find()) violations.add(file + " — " + m.group());
                }
            }
        }
        assertThat(violations)
                .as("HTMX 가 Function() 으로 만드는 기능이 있다 — CSP 에 막혀 조건이 조용히 빠진다. JS 리스너로 옮길 것")
                .isEmpty();
    }
}
