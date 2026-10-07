package com.example.ragagent.llm;

import com.example.ragagent.llm.ThinkingObservations.Sample;
import com.example.ragagent.llm.ThinkingObservations.Stats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code /settings} 생각 수준 카드(PLAN §6.29 ⑦)가 읽는 재료들 — 관측 요약, 생성 속도, dialect 가 구분하는 폭, 사이트의 묶음·키.
 * 화면이 이 값들에서 판정(잘림·최악 소요·접힘)을 내므로 여기서 틀리면 화면의 배지가 틀린다.
 */
class ThinkingPreviewInputsTest {

    private static Sample sample(Integer output, Integer thinking, boolean observed, boolean truncated, long latencyMs) {
        return new Sample(ThinkingWire.Sent.ON, output, false, thinking, thinking != null, observed, truncated, latencyMs);
    }

    // ── 관측 요약 ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("표본이 없으면 비어 있다")
    void emptyStats() {
        assertThat(ThinkingObservations.statsOf(List.of())).isSameAs(Stats.EMPTY);
        assertThat(ThinkingObservations.statsOf(null)).isSameAs(Stats.EMPTY);
        assertThat(Stats.EMPTY.any()).isFalse();
    }

    @Test
    @DisplayName("분위수는 가까운 순위 방식이다 — 열 개의 1..10 에서 p50 = 5, p95 = 10")
    void nearestRankPercentiles() {
        List<Integer> ten = IntStream.rangeClosed(1, 10).boxed().toList();

        assertThat(ThinkingObservations.percentile(ten, 50)).isEqualTo(5);
        assertThat(ThinkingObservations.percentile(ten, 95)).isEqualTo(10);
        assertThat(ThinkingObservations.percentile(List.of(7), 50)).isEqualTo(7);
        assertThat(ThinkingObservations.percentile(List.of(), 95)).isNull();
    }

    @Test
    @DisplayName("생각 토큰의 분위수는 생각을 잰 표본만으로 센다 — 생각하지 않은 호출(null)이 중앙값을 0 으로 끌어내리지 않는다")
    void thinkingPercentilesIgnoreCallsThatDidNotThink() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 6; i++) samples.add(sample(100, null, false, false, 1_000));   // 생각 없음
        samples.add(sample(2_000, 1_800, true, false, 30_000));
        samples.add(sample(2_200, 2_000, true, true, 31_000));
        samples.add(sample(2_400, 2_200, true, false, 32_000));

        Stats stats = ThinkingObservations.statsOf(samples);

        assertThat(stats.count()).isEqualTo(9);
        assertThat(stats.thinkingObserved()).isEqualTo(3);
        assertThat(stats.truncated()).isEqualTo(1);
        assertThat(stats.thinkingP50()).isEqualTo(2_000);
        assertThat(stats.thinkingP95()).isEqualTo(2_200);
        assertThat(stats.thinkingEstimated()).as("표본의 생각 토큰이 추정으로 표시돼 있다").isTrue();
        assertThat(stats.outputP50()).isEqualTo(100);
    }

    @Test
    @DisplayName("서버가 센 생각 토큰만 있으면 추정 표시가 없다")
    void reportedThinkingIsNotMarkedEstimated() {
        Sample reported = new Sample(ThinkingWire.Sent.ON, 500, false, 300, false, true, false, 5_000);

        assertThat(ThinkingObservations.statsOf(List.of(reported)).thinkingEstimated()).isFalse();
    }

    // ── 생성 속도 ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("속도는 출력 토큰 ÷ 지연의 중앙값이고, 표본이 3건 미만이면 내지 않는다")
    void speedNeedsEnoughSamples() {
        assertThat(ThinkingObservations.speedOf(List.of(sample(1_000, null, false, false, 20_000),
                sample(1_000, null, false, false, 20_000)))).isNull();

        // 50 · 40 · 100 tok/s → 중앙값 50
        assertThat(ThinkingObservations.speedOf(List.of(
                sample(1_000, null, false, false, 20_000),
                sample(1_000, null, false, false, 25_000),
                sample(1_000, null, false, false, 10_000)))).isEqualTo(50.0);
    }

    @Test
    @DisplayName("짧은 호출은 속도 표본이 아니다 — prefill·왕복 지연이 속도를 지배한다(16 토큰 미만 · 0.5초 미만은 거른다)")
    void shortCallsAreNotSpeedSamples() {
        List<Sample> noise = Arrays.asList(
                sample(8, null, false, false, 5_000),        // 토큰이 적다
                sample(1_000, null, false, false, 200),      // 너무 빨리 끝났다 — 캐시·지연이 섞인 값
                sample(null, null, false, false, 5_000),     // 서버가 토큰을 안 셌다
                sample(1_000, null, false, false, 20_000));

        assertThat(ThinkingObservations.speedOf(noise)).as("남은 표본은 하나뿐이라 낼 수 없다").isNull();
    }

    @Test
    @DisplayName("속도는 프로바이더 단위다 — 사이트·수준이 달라도 같은 프로바이더의 표본을 모두 쓰고, 다른 프로바이더는 섞지 않는다")
    void speedIsPerProvider() {
        ThinkingObservations obs = new ThinkingObservations();
        for (ThinkingSite site : List.of(ThinkingSite.CLASSIFY, ThinkingSite.TITLE, ThinkingSite.EVAL)) {
            obs.record(site, "local", ThinkingLevel.LOW, sample(1_000, null, false, false, 20_000));
            obs.record(site, "remote", ThinkingLevel.LOW, sample(1_000, null, false, false, 5_000));
        }

        assertThat(obs.speedTokensPerSecond("local")).isEqualTo(50.0);
        assertThat(obs.speedTokensPerSecond("remote")).isEqualTo(200.0);
        assertThat(obs.speedTokensPerSecond("nobody")).isNull();
        assertThat(obs.speedTokensPerSecond(null)).isNull();
    }

    // ── dialect 가 구분하는 폭 ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("support()/field() 는 wire() 가 실제로 만드는 것과 같은 이야기를 한다 — 프로바이더 표의 설명이 전송과 어긋나지 않는다")
    void supportMatchesWhatTheWireDoes() {
        for (ThinkingDialect dialect : ThinkingDialect.values()) {
            if (dialect == ThinkingDialect.AUTO) continue;
            ThinkingWire off = dialect.wire(ThinkingLevel.OFF);
            ThinkingWire low = dialect.wire(ThinkingLevel.LOW);
            ThinkingWire medium = dialect.wire(ThinkingLevel.MEDIUM);
            ThinkingWire high = dialect.wire(ThinkingLevel.HIGH);

            switch (dialect.support()) {
                case NONE -> assertThat(List.of(off, low, medium, high))
                        .allMatch(w -> w.sent() == ThinkingWire.Sent.NOTHING).as(dialect.name());
                case ON_OFF -> {
                    assertThat(off.sent()).isEqualTo(ThinkingWire.Sent.OFF);
                    assertThat(List.of(low, medium, high)).as("%s — 켬 셋은 같은 값으로 접힌다", dialect)
                            .allMatch(w -> w.sent() == ThinkingWire.Sent.ON && w.extraBody().equals(low.extraBody()));
                }
                case EFFORT -> {
                    assertThat(off.sent()).isEqualTo(ThinkingWire.Sent.OFF);
                    assertThat(List.of(low.reasoningEffort(), medium.reasoningEffort(), high.reasoningEffort()))
                            .as("%s — 세 수준이 서로 다른 값", dialect).doesNotHaveDuplicates();
                }
                case EFFORT_NO_OFF -> {
                    assertThat(off.sent()).as("%s — 끌 수 없다", dialect).isEqualTo(ThinkingWire.Sent.ON);
                    assertThat(off.extraBody()).isEqualTo(low.extraBody());
                    assertThat(medium.extraBody()).isNotEqualTo(low.extraBody());
                }
            }
            assertThat(dialect.field() == null).as("%s — 필드가 없는 것은 아무것도 안 싣는 것뿐", dialect)
                    .isEqualTo(dialect.support() == ThinkingDialect.Support.NONE);
            if (dialect.field() != null) {
                String root = dialect.field().split("\\.")[0];
                assertThat(low.fields()).as("%s — 화면에 적는 필드 이름이 실제로 싣는 본문 필드다", dialect).contains(root);
            }
        }
    }

    @Test
    @DisplayName("AUTO 는 풀기 전에 support()/field() 를 물을 수 없다 — wire() 와 같은 이유(조용히 NONE 으로 떨어지면 LOCAL 서버의 끄기가 사라진다)")
    void autoMustBeResolvedFirst() {
        assertThatThrownBy(ThinkingDialect.AUTO::support).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(ThinkingDialect.AUTO::field).isInstanceOf(IllegalStateException.class);
    }

    // ── 사이트 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("설정 키로 사이트를 찾는다 — 접두와 id 를 모두 맞춰야 하고, 모르는 키는 비어 있다")
    void findBySettingsKey() {
        for (ThinkingSite site : ThinkingSite.values()) {
            assertThat(ThinkingSite.bySettingsKey(site.settingsKey())).contains(site);
        }
        assertThat(ThinkingSite.bySettingsKey("llm.thinking.nope")).isEmpty();
        assertThat(ThinkingSite.bySettingsKey("llm.temperature")).isEmpty();
        assertThat(ThinkingSite.bySettingsKey("thinking.eval")).isEmpty();
        assertThat(ThinkingSite.bySettingsKey(null)).isEmpty();
    }

    @Test
    @DisplayName("응답 필요분은 음수가 아니고, 상수로 둘 수 없는 사이트(답변·키워드 배치·재작성)만 0 이다")
    void expectedOutputIsDeclaredExceptWhereTheCallShapeDecidesIt() {
        List<ThinkingSite> shaped = List.of(
                ThinkingSite.ANSWER_RAG_S, ThinkingSite.ANSWER_RAG_N, ThinkingSite.ANSWER_RAG_C,
                ThinkingSite.ANSWER_DIRECT_S, ThinkingSite.ANSWER_DIRECT_N, ThinkingSite.ANSWER_META,
                ThinkingSite.KEYWORD_CONTEXT, ThinkingSite.MD_CORRECT, ThinkingSite.TXT_TO_MD);
        for (ThinkingSite site : ThinkingSite.values()) {
            if (shaped.contains(site)) assertThat(site.expectedOutputTokens()).as(site.id()).isZero();
            else assertThat(site.expectedOutputTokens()).as(site.id()).isPositive();
        }
    }

    @Test
    @DisplayName("모든 묶음에 사이트가 있다 — 표가 빈 채로 그려지지 않는다")
    void everyGroupHasSites() {
        for (ThinkingSite.Group group : ThinkingSite.Group.values()) {
            assertThat(Arrays.stream(ThinkingSite.values()).filter(s -> s.group() == group)).as(group.name()).isNotEmpty();
        }
    }
}
