package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Convention guard for EDIT.md #3: every {@code AppProperties} field that has an
 * {@code xxxSafe()} null-guard must be accessed through it, never the raw record accessor,
 * outside of {@code AppProperties} itself. A raw call bypasses default-value/clamping logic
 * and NPEs when the property is absent (e.g. {@code @MockitoBean AppProperties} in
 * {@code @WebMvcTest}). The guarded-field set is derived by reflection from {@code xxxSafe()}
 * method names matched case-insensitively against the record's actual component names (e.g.
 * {@code vectorstore} / {@code vectorStoreSafe()} differ in case), so this stays in sync
 * automatically as new safe accessors are added.
 */
class AppPropertiesSafeAccessorTest {

    private static final Path SRC_ROOT = Path.of("src/main/java");
    private static final String SAFE_SUFFIX = "Safe";
    // only these receiver names are used for an injected AppProperties across the codebase
    private static final Set<String> RECEIVER_NAMES = Set.of("props", "appProperties");

    @Test
    @DisplayName("raw AppProperties getters guarded by xxxSafe() are never called outside AppProperties.java")
    void mainSourceUsesSafeAccessorsOnly() throws IOException {
        Set<String> componentNames = Stream.of(AppProperties.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        // raw component name -> actual xxxSafe() method name, e.g. "vectorstore" -> "vectorStoreSafe"
        Map<String, String> guarded = Stream.of(AppProperties.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.endsWith(SAFE_SUFFIX) && !name.equals(SAFE_SUFFIX))
                .collect(Collectors.toMap(
                        // xxxSafe() strips to the component name case-insensitively (e.g. vectorStoreSafe()
                        // -> "vectorStore", but the actual record component is "vectorstore") — resolve to
                        // the real component name so the regex matches the real raw accessor call.
                        safeName -> componentNames.stream()
                                .filter(c -> c.equalsIgnoreCase(safeName.substring(0, safeName.length() - SAFE_SUFFIX.length())))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                        "%s() has no matching AppProperties record component".formatted(safeName))),
                        safeName -> safeName,
                        (a, b) -> a,
                        LinkedHashMap::new));
        assertThat(guarded).isNotEmpty();

        String receiverAlternation = String.join("|", RECEIVER_NAMES);
        Pattern violationPattern = Pattern.compile(
                "\\b(?:" + receiverAlternation + ")\\s*\\.\\s*(" +
                        guarded.keySet().stream().map(Pattern::quote).collect(Collectors.joining("|")) +
                        ")\\s*\\(\\s*\\)");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("AppProperties.java"))
                    .forEach(p -> scanFile(p, violationPattern, guarded, violations));
        }

        assertThat(violations)
                .as("raw AppProperties getter called — use the xxxSafe() accessor instead")
                .isEmpty();
    }

    private void scanFile(Path file, Pattern violationPattern, Map<String, String> guarded, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = violationPattern.matcher(lines.get(i));
                while (m.find()) {
                    String rawName = m.group(1);
                    violations.add("%s:%d — raw call to %s(), use %s() instead"
                            .formatted(file, i + 1, rawName, guarded.get(rawName)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
