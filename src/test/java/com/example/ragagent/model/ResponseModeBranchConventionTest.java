package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Convention guard for {@link ResponseMode} (PLAN §6.24 Step 0-b).
 *
 * <p>응답 모드 분기는 <b>값 비교가 아니라 성질 질의</b>여야 한다 — {@code == ResponseMode.S}가
 * 아니라 {@code mode.skipsVerification()}. 이 규칙이 없으면 모드를 하나 추가할 때마다
 * {@code AgentGraph}·{@code AnswerService}·{@code DirectAnswerService}·{@code CuratedQaService}에
 * 흩어진 분기를 사람이 기억해서 찾아야 하고, 한 곳을 놓치면 새 모드가 <b>컴파일도 테스트도
 * 통과한 채</b> 엉뚱한 경로를 탄다(실제로 그런 분기가 6곳까지 늘어난 뒤에야 정리했다).
 *
 * <p>새 분기가 필요하면 값을 비교하지 말고 {@code ResponseMode}에 성질을 하나 더 만들어라.
 * 그러면 다음 모드를 추가할 때 고칠 곳이 enum 정의 한 줄로 모인다.
 */
class ResponseModeBranchConventionTest {

    private static final Path MAIN_ROOT = Path.of("src/main/java");

    /**
     * 모드 상수를 직접 비교하는 형태들. 선언({@code ResponseMode mode})이나 기본값 참조
     * ({@code ResponseMode.DEFAULT})는 걸리지 않도록 상수는 <b>한 글자</b>만 본다.
     *
     * <p>패키지 수식(fully-qualified) 형태를 반드시 함께 잡아야 한다 —
     * {@code == com.example.ragagent.model.ResponseMode.S}는 import 없이도 쓸 수 있어서,
     * 수식 없는 형태만 검사하면 규약이 조용히 뚫린다(이 가드를 처음 작성했을 때 실제로 그랬다).
     */
    private static final Pattern[] VALUE_COMPARISONS = {
            // x == ResponseMode.S / x != ...ResponseMode.N
            Pattern.compile("[!=]=\\s*(?:[\\w.]*\\.)?ResponseMode\\.[A-Z](?![A-Z_])"),
            // ResponseMode.S == x
            Pattern.compile("(?:[\\w.]*\\.)?ResponseMode\\.[A-Z](?![A-Z_])\\s*[!=]="),
            // mode.equals(ResponseMode.S)
            Pattern.compile("\\.equals\\(\\s*(?:[\\w.]*\\.)?ResponseMode\\.[A-Z](?![A-Z_])"),
    };

    @Test
    @DisplayName("운영 코드는 응답 모드를 값으로 비교하지 않고 성질로 묻는다")
    void productionCodeBranchesOnCapabilitiesNotValues() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    // enum 자신은 값을 다뤄도 된다 — 성질이 정의되는 곳이다.
                    .filter(p -> !p.getFileName().toString().equals("ResponseMode.java"))
                    .forEach(p -> {
                        String src;
                        try {
                            src = Files.readString(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        for (Pattern pattern : VALUE_COMPARISONS) {
                            var m = pattern.matcher(src);
                            while (m.find()) {
                                violations.add(p.toString().replace("src/main/java/", "")
                                        + " — " + m.group().replaceAll("\\s+", " "));
                            }
                        }
                    });
        }

        assertThat(violations)
                .as("응답 모드를 값으로 비교하는 분기가 남아 있다. ResponseMode 에 성질(예: "
                    + "skipsVerification()/summaryOnly())을 만들어 그것으로 물어라 — PLAN §6.24 Step 0-b")
                .isEmpty();
    }
}
