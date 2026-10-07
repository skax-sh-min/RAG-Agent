package com.example.ragagent.service;

import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ThinkingWire;
import com.example.ragagent.model.ThinkingPreview;
import com.example.ragagent.model.ThinkingPreview.Badge;
import com.example.ragagent.model.ThinkingPreview.Cell;
import com.example.ragagent.model.ThinkingPreview.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@code /settings} 생각 수준 카드의 숫자(PLAN §6.29 ⑦). 이 숫자들은 런타임이 쓰는 함수를 그대로 불러 나오므로, 여기서 고정하는
 * 것은 <b>골든</b>(공식이 바뀌면 깨진다)과 <b>불변식</b>(어떤 수준·창·사이트에서도 성립해야 하는 것)이다.
 *
 * <p>설정 오버라이드({@code AppProperties} 의 정적 오버라이드 소스)를 읽으므로 다른 설정 테스트와 같이 잠근다.
 */
@ResourceLock("global-state")
class ThinkingPreviewServiceTest {

    private Locale previousLocale;

    @BeforeEach
    void korean() {
        previousLocale = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(Locale.KOREAN);
    }

    @AfterEach
    void restore() {
        LocaleContextHolder.setLocale(previousLocale);
    }

    private static List<Integer> reserved(Row row) {
        return row.cells().stream().map(Cell::reservedTokens).toList();
    }

    private static List<Integer> inputs(Row row) {
        return row.cells().stream().map(Cell::inputBudget).toList();
    }

    private static ThinkingObservations.Sample sample(ThinkingWire.Sent sent, int output, Integer thinking,
                                                      boolean observed, boolean truncated, long latencyMs) {
        return new ThinkingObservations.Sample(sent, output, false, thinking, thinking != null, observed, truncated, latencyMs);
    }

    // ── 구조 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("카드는 모든 호출 지점을 한 번씩, 사이트가 선언한 묶음에, 네 수준(끔·낮게·중간·높게 순)으로 담는다")
    void everySiteOnceInItsGroupWithFourLevels() {
        ThinkingPreview preview = ThinkingPreviewHarness.builder().build().service.preview();

        List<ThinkingSite> seen = preview.groups().stream().flatMap(g -> g.rows().stream()).map(Row::site).toList();
        assertThat(seen).containsExactlyInAnyOrder(ThinkingSite.values()).doesNotHaveDuplicates();
        preview.groups().forEach(g -> g.rows().forEach(row -> {
            assertThat(row.site().group()).isEqualTo(g.group());
            assertThat(row.cells()).extracting(Cell::level).containsExactly(ThinkingLevel.values());
            assertThat(row.cells().stream().filter(Cell::saved)).hasSize(1);
            assertThat(row.savedCell().level()).isEqualTo(row.saved());
        }));
        assertThat(preview.groups()).extracting(ThinkingPreview.Group::group)
                .containsExactly(ThinkingSite.Group.values());
    }

    @Test
    @DisplayName("저장된 수준은 설정 파일의 줄이고, 없으면 출하값 — '기본값' 표시는 그 수준에 붙는다")
    void savedAndDefaultLevels() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder()
                .file(ThinkingPreviewHarness.levels(ThinkingSite.EVAL, ThinkingLevel.MEDIUM)).build();

        Row eval = h.row(ThinkingSite.EVAL);
        assertThat(eval.saved()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(eval.defaultLevel()).as("오버라이드를 지우면 돌아가는 곳은 파일의 줄").isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(eval.cell(ThinkingLevel.MEDIUM).byDefault()).isTrue();
        assertThat(eval.cell(ThinkingLevel.LOW).byDefault()).isFalse();

        Row condense = h.row(ThinkingSite.CONDENSE);
        assertThat(condense.saved()).as("줄이 없으면 출하값").isEqualTo(ThinkingLevel.OFF);
        assertThat(condense.defaultLevel()).isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    @DisplayName("오버라이드가 있으면 '오버라이드됨' — 카드는 그 사실을 SettingsService 에서 읽는다")
    void overriddenComesFromTheSettingsLayer() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().build();
        when(h.settings.isOverridden(ThinkingSite.RERANK.settingsKey())).thenReturn(true);

        assertThat(h.row(ThinkingSite.RERANK).overridden()).isTrue();
        assertThat(h.row(ThinkingSite.EVAL).overridden()).isFalse();
    }

    @Test
    @DisplayName("계산 기준 줄 — 설정 스냅샷과 토큰 추정 계수(표본이 없으면 비어 있다)")
    void basisLine() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().build();
        ThinkingPreview.Basis basis = h.service.basis();

        assertThat(basis.maxTokens()).isEqualTo(10_000);
        assertThat(basis.topK()).isEqualTo(10);
        assertThat(basis.chunkSize()).isEqualTo(1_500);
        assertThat(basis.questionChars()).isEqualTo(200);
        assertThat(basis.shortAnswerChars()).isEqualTo(3_000);
        assertThat(basis.longAnswerChars()).isEqualTo(5_000);
        assertThat(basis.estimateRatio()).isNull();
        assertThat(basis.estimateSamples()).isZero();

        h.calibration.record("가".repeat(100), 150);
        assertThat(h.service.basis().estimateRatio()).isEqualTo(1.5);
        assertThat(h.service.basis().estimateSamples()).isEqualTo(1);
    }

    // ── 골든 — PLAN ⑦-나 예시와 같다 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("골든 — 16k 창의 검증: 예약 2,048 · 2,560 · 3,072 · 4,096 → 입력 예산 12,698 · 12,186 · 11,674 · 10,650 (PLAN 예시)")
    void goldenEvalOn16k() {
        Row eval = ThinkingPreviewHarness.builder().window(16_384).build().row(ThinkingSite.EVAL);

        assertThat(reserved(eval)).containsExactly(2_048, 2_560, 3_072, 4_096);
        assertThat(inputs(eval)).containsExactly(12_698, 12_186, 11_674, 10_650);
        assertThat(eval.cell(ThinkingLevel.HIGH).reservation().ceiling()).as("창의 25%").isEqualTo(4_096);
        assertThat(eval.cell(ThinkingLevel.HIGH).reservation().clipped()).as("정확히 닿았을 뿐 깎이지 않았다").isFalse();
        assertThat(eval.cell(ThinkingLevel.LOW).inputPercent()).isEqualTo(-4);
        assertThat(eval.cell(ThinkingLevel.OFF).inputPercent()).as("끔이 기준이다").isNull();
        assertThat(eval.cell(ThinkingLevel.LOW).use()).isEqualTo(ThinkingPreview.Use.REQUEST);
        assertThat(eval.cell(ThinkingLevel.LOW).thinkingRoom()).as("예약 2,560 − 응답 필요분 400").isEqualTo(2_160);
        assertThat(eval.cell(ThinkingLevel.OFF).thinkingRoom()).as("끔으로 나가면 생각 자리가 없다").isNull();
    }

    @Test
    @DisplayName("골든 — 8k 창의 검증: 상한(창 25% = 2,048)이 기본 예약과 같아 어느 수준도 여유를 못 받는다 — 입력 예산 5,325 그대로, 깎임 배지")
    void goldenEvalOn8k() {
        Row eval = ThinkingPreviewHarness.builder().window(8_192).build().row(ThinkingSite.EVAL);

        assertThat(reserved(eval)).containsExactly(2_048, 2_048, 2_048, 2_048);
        assertThat(inputs(eval)).containsExactly(5_325, 5_325, 5_325, 5_325);
        for (ThinkingLevel level : List.of(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)) {
            assertThat(eval.cell(level).has(Badge.Kind.HEADROOM_CLIPPED)).as(level.name()).isTrue();
        }
        assertThat(eval.cell(ThinkingLevel.OFF).has(Badge.Kind.HEADROOM_CLIPPED)).isFalse();
    }

    @Test
    @DisplayName("기본 예약은 깎지 않는다 — 16k 창의 스트리밍 답변(N 5,000)은 어느 수준에서도 5,000 이고, 응답 자리 없음은 경고로만 보인다")
    void answerKeepsItsBaseReservation() {
        Row answer = ThinkingPreviewHarness.builder().window(16_384).build().row(ThinkingSite.ANSWER_RAG_N);

        assertThat(reserved(answer)).containsExactly(5_000, 5_000, 5_000, 5_000);
        assertThat(inputs(answer)).containsExactly(9_746, 9_746, 9_746, 9_746);
        Cell low = answer.cell(ThinkingLevel.LOW);
        assertThat(low.use()).isEqualTo(ThinkingPreview.Use.BUDGET_ONLY);
        assertThat(low.thinkingRoom()).as("예약 5,000 − 응답 필요분(N 의 최소 보장 5,000)").isZero();
        assertThat(low.badges()).filteredOn(b -> b.kind() == Badge.Kind.NO_ROOM)
                .singleElement().extracting(Badge::severity).as("스트리밍은 max_tokens 를 싣지 않아 ⛔ 이 아니라 ⚠").isEqualTo(Badge.Severity.WARNING);
    }

    @Test
    @DisplayName("32k 창의 답변(N) + 낮게 — 상한 8,192 까지 남은 자리에서 512 를 받는다")
    void answerOn32kGetsTheHeadroom() {
        Row answer = ThinkingPreviewHarness.builder().window(32_768).build().row(ThinkingSite.ANSWER_RAG_N);

        assertThat(answer.cell(ThinkingLevel.LOW).reservedTokens()).isEqualTo(5_512);
        assertThat(answer.cell(ThinkingLevel.OFF).reservedTokens()).isEqualTo(5_000);
        assertThat(answer.cell(ThinkingLevel.LOW).thinkingRoom()).isEqualTo(512);
    }

    @Test
    @DisplayName("블로킹 사이트의 응답 자리 없음은 ⛔ — 프로바이더 상한이 응답 필요분보다 작으면 생각을 켜는 순간 빈 응답이다")
    void noRoomIsDangerousForBlockingCalls() {
        // 프로바이더 자신의 max-tokens 256 — 상한을 싣지 않는 요약(응답 필요분 800)은 그 값이 그대로 예약된다.
        Row summary = ThinkingPreviewHarness.builder().providerMaxTokens(256).build().row(ThinkingSite.SUMMARY);

        Cell low = summary.cell(ThinkingLevel.LOW);
        assertThat(low.reservedTokens()).isEqualTo(256);
        assertThat(low.thinkingRoom()).isEqualTo(256 - 800);
        assertThat(low.badges()).filteredOn(b -> b.kind() == Badge.Kind.NO_ROOM)
                .singleElement().extracting(Badge::severity).isEqualTo(Badge.Severity.DANGER);
        assertThat(summary.cell(ThinkingLevel.OFF).has(Badge.Kind.NO_ROOM)).as("끔이면 생각 자리가 없는 게 아니라 필요 없다").isFalse();
    }

    // ── 창을 모를 때 ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("창을 모르면 입력 예산이 없다 — '—' 이고, 입력 관련 배지(입력 축소)도 없고, '창 모름' 이 대신 선다")
    void windowUnknown() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().window(0).build();

        for (ThinkingSite site : ThinkingSite.values()) {
            Row row = h.row(site);
            assertThat(row.receiver().windowKnown()).isFalse();
            for (Cell cell : row.cells()) {
                String where = site + "/" + cell.level();
                assertThat(cell.inputBudget()).as(where).isNull();
                assertThat(cell.inputPercent()).as(where).isNull();
                assertThat(cell.meaning().known()).as(where).isFalse();
                assertThat(cell.has(Badge.Kind.INPUT_SHRINK)).as(where).isFalse();
                assertThat(cell.has(Badge.Kind.WINDOW_UNKNOWN)).as(where).isTrue();
            }
        }
        // 창 25% 상한은 못 구해도 프로바이더 max-tokens 상한은 안다 — 여유는 그 상한까지 준다
        assertThat(reserved(h.row(ThinkingSite.EVAL))).containsExactly(2_048, 2_560, 3_072, 4_096);
    }

    @Test
    @DisplayName("받을 프로바이더가 없으면 계산하지 않고 그 사실만 배지로 말한다")
    void nobodyReceives() {
        Row eval = ThinkingPreviewHarness.builder().nobodyReceives().build().row(ThinkingSite.EVAL);

        assertThat(eval.receiver().none()).isTrue();
        for (Cell cell : eval.cells()) {
            assertThat(cell.badges()).extracting(Badge::kind).containsExactly(Badge.Kind.NO_PROVIDER);
            assertThat(cell.inputBudget()).isNull();
        }
    }

    // ── 불변식 ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("불변식 — 모든 사이트·수준·창에서: 예약 ≥ 기본 예약 · 여유 ≤ 수준의 여유 · 입력 예산 = PromptBudget · 끔은 여유 0 · 생각 자리는 끔이 아닐 때만")
    void invariantsHoldEverywhere() {
        for (int window : new int[]{0, 4_096, 8_192, 16_384, 32_768, 131_072}) {
            ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().window(window).build();
            for (ThinkingSite site : ThinkingSite.values()) {
                Row row = h.row(site);
                for (Cell cell : row.cells()) {
                    String where = "%s/%s/창 %d".formatted(site, cell.level(), window);
                    ThinkingBudget.Reservation r = cell.reservation();
                    if (r.base() > 0) assertThat(cell.reservedTokens()).as(where).isGreaterThanOrEqualTo(r.base());
                    assertThat(r.granted()).as(where).isBetween(0, ThinkingBudget.headroom(cell.level()));
                    if (cell.level() == ThinkingLevel.OFF) assertThat(r.requested()).as(where).isZero();
                    if (window > 0) {
                        assertThat(cell.inputBudget()).as(where)
                                .isEqualTo(new PromptBudget(window, cell.reservedTokens()).inputBudget());
                    } else {
                        assertThat(cell.inputBudget()).as(where).isNull();
                    }
                    assertThat(cell.thinkingRoom() == null).as(where)
                            .isEqualTo(cell.wire().sent() == ThinkingWire.Sent.OFF);
                    assertThat(cell.badges()).as(where).isNotNull();
                    assertThat(cell.basis()).as(where).isNotEmpty();
                    assertThat(cell.meaning().text()).as(where).isNotBlank();
                }
                // 재작성은 여유만큼 조각이 줄고 그 1.5배가 예약이라 단조가 아니다 — 나머지는 수준이 오를수록 예약이 줄지 않고 입력이 늘지 않는다
                if (!site.rewritesInput()) {
                    for (int i = 1; i < row.cells().size(); i++) {
                        Cell lower = row.cells().get(i - 1);
                        Cell higher = row.cells().get(i);
                        assertThat(higher.reservedTokens()).as("%s 창 %d".formatted(site, window))
                                .isGreaterThanOrEqualTo(lower.reservedTokens());
                        if (window > 0) assertThat(higher.inputBudget()).isLessThanOrEqualTo(lower.inputBudget());
                    }
                }
            }
        }
    }

    // ── 전송과 접힘 ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("켬/끔만 받는 서버(llama.cpp)에서는 낮게·중간·높게가 같은 값으로 접힌다 — 끔은 따로다")
    void collapsedOnTemplateKwargs() {
        Row row = ThinkingPreviewHarness.builder().build().row(ThinkingSite.EVAL);

        assertThat(row.cell(ThinkingLevel.OFF).collapsedWith()).isEmpty();
        assertThat(row.cell(ThinkingLevel.LOW).collapsedWith())
                .containsExactlyInAnyOrder(ThinkingLevel.MEDIUM, ThinkingLevel.HIGH);
        assertThat(row.cell(ThinkingLevel.LOW).has(Badge.Kind.COLLAPSED)).isTrue();
        assertThat(row.cell(ThinkingLevel.OFF).has(Badge.Kind.COLLAPSED)).isFalse();
        assertThat(row.cell(ThinkingLevel.OFF).wire().sent()).isEqualTo(ThinkingWire.Sent.OFF);
        assertThat(row.cell(ThinkingLevel.LOW).wire().describe()).contains("enable_thinking=true");
    }

    @Test
    @DisplayName("끌 수 없는 서버(gpt-oss) — 끔이 low 로 접히고 켬으로 나간다. 수준을 구분하는 서버(OpenAI)는 접히지 않는다")
    void collapseDependsOnTheDialect() {
        Row effortNoOff = ThinkingPreviewHarness.builder().dialect(ThinkingDialect.TEMPLATE_KWARGS_EFFORT).build()
                .row(ThinkingSite.EVAL);
        assertThat(effortNoOff.cell(ThinkingLevel.OFF).wire().sent()).as("끌 수 없다").isEqualTo(ThinkingWire.Sent.ON);
        assertThat(effortNoOff.cell(ThinkingLevel.OFF).collapsedWith()).containsExactly(ThinkingLevel.LOW);
        assertThat(effortNoOff.cell(ThinkingLevel.OFF).reservation().requested()).as("끔이어도 켬으로 나가니 여유를 요구한다")
                .isEqualTo(ThinkingBudget.headroom(ThinkingLevel.OFF));

        Row effort = ThinkingPreviewHarness.builder().dialect(ThinkingDialect.OPENAI_EFFORT).build().row(ThinkingSite.EVAL);
        assertThat(effort.cells()).allSatisfy(c -> assertThat(c.collapsedWith()).isEmpty());
        assertThat(effort.cell(ThinkingLevel.OFF).wire().describe()).contains("reasoning_effort=none");
    }

    @Test
    @DisplayName("생각 제어를 안 받는 프로바이더 — 아무것도 보내지 않고, 여유도 더하지 않고, 생각 자리에 (?) 가 붙는다")
    void noControlProvider() {
        Row row = ThinkingPreviewHarness.builder().dialect(ThinkingDialect.NONE).build().row(ThinkingSite.EVAL);

        for (Cell cell : row.cells()) {
            assertThat(cell.wire().sent()).isEqualTo(ThinkingWire.Sent.NOTHING);
            assertThat(cell.reservedTokens()).as("켬으로 나가지 않으니 여유가 없다").isEqualTo(2_048);
            assertThat(cell.roomUncertain()).isTrue();
            assertThat(cell.thinkingRoom()).isEqualTo(2_048 - 400);
            assertThat(cell.has(Badge.Kind.NO_CONTROL)).isTrue();
            assertThat(cell.has(Badge.Kind.COLLAPSED)).as("보내지 않으니 '같은 값' 이 의미 없다").isFalse();
            assertThat(cell.sentLabel()).contains("생각 제어 안 함");
        }
    }

    @Test
    @DisplayName("서버가 필드를 거부한 프로바이더 — 보내지 않음, 사유에 필드 이름, '거부됨' 배지")
    void rejectedProvider() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().build();
        h.dialects.markRejected(ThinkingPreviewHarness.PROVIDER, ThinkingDialect.TEMPLATE_KWARGS_FIELD);

        Cell low = h.row(ThinkingSite.EVAL).cell(ThinkingLevel.LOW);
        assertThat(low.wire().sent()).isEqualTo(ThinkingWire.Sent.NOTHING);
        assertThat(low.sentLabel()).contains(ThinkingDialect.TEMPLATE_KWARGS_FIELD).contains("재시작");
        assertThat(low.has(Badge.Kind.REJECTED)).isTrue();
        assertThat(low.has(Badge.Kind.NO_CONTROL)).isFalse();
        assertThat(h.row(ThinkingSite.EVAL).receiver().rejected()).containsExactly(ThinkingDialect.TEMPLATE_KWARGS_FIELD);
    }

    // ── 사이트 종류별 "뜻하는 것" ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("상한을 싣지 않는 호출(분류·리랭크·제목…)은 프로바이더 기본값이 예약된다 — 여유는 더하지 않고, 입력 예산은 그 값으로 잰다")
    void callsWithoutACapReserveTheProviderDefault() {
        Row classify = ThinkingPreviewHarness.builder().window(16_384).build().row(ThinkingSite.CLASSIFY);

        assertThat(reserved(classify)).containsExactly(10_000, 10_000, 10_000, 10_000);
        assertThat(inputs(classify)).containsExactly(4_746, 4_746, 4_746, 4_746);
        assertThat(classify.cell(ThinkingLevel.LOW).use()).isEqualTo(ThinkingPreview.Use.PROVIDER_DEFAULT);
        assertThat(classify.cell(ThinkingLevel.LOW).reservation().requested()).as("요구하지도 않는다 — 기본 예약이 이미 상한 위다").isZero();
        assertThat(classify.cell(ThinkingLevel.LOW).thinkingRoom()).isEqualTo(10_000 - 40);
    }

    @Test
    @DisplayName("재작성(MD 교정) — 켬이면 조각이 줄고(창이 작을수록 뚜렷하다), 줄었다는 사실이 '입력 축소' 로 나온다")
    void rewriteSectionShrinksWhenThinkingIsOn() {
        Row md = ThinkingPreviewHarness.builder().window(8_192).build().row(ThinkingSite.MD_CORRECT);

        long off = md.cell(ThinkingLevel.OFF).meaning().metric();
        long high = md.cell(ThinkingLevel.HIGH).meaning().metric();
        assertThat(high).as("높게는 2,048 토큰의 여유만큼 조각을 줄인다").isLessThan(off);
        assertThat(md.cell(ThinkingLevel.HIGH).has(Badge.Kind.INPUT_SHRINK)).isTrue();
        assertThat(md.cell(ThinkingLevel.OFF).has(Badge.Kind.INPUT_SHRINK)).isFalse();
        // 창이 넉넉하면(모두 max-tokens 파생 상한 아래) 조각이 그대로다 — 줄이기만 하고 키우지 않는다
        Row roomy = ThinkingPreviewHarness.builder().window(131_072).build().row(ThinkingSite.MD_CORRECT);
        assertThat(roomy.cell(ThinkingLevel.HIGH).meaning().metric()).isEqualTo(roomy.cell(ThinkingLevel.OFF).meaning().metric());
    }

    @Test
    @DisplayName("키워드 배치 — 설정한 개수가 창에 들어가는지, 들어가는 최대 개수는 몇인지")
    void keywordBatchFits() {
        Row roomy = ThinkingPreviewHarness.builder().window(16_384).build().row(ThinkingSite.KEYWORD_CONTEXT);
        assertThat(roomy.cell(ThinkingLevel.LOW).meaning().metric()).as("설정 2개보다 많이 들어간다").isGreaterThan(2);
        assertThat(roomy.cell(ThinkingLevel.LOW).meaning().text()).contains("들어감").doesNotContain("안 들어감");

        Row tiny = ThinkingPreviewHarness.builder().window(4_096).build().row(ThinkingSite.KEYWORD_CONTEXT);
        assertThat(tiny.cell(ThinkingLevel.LOW).meaning().metric()).isEqualTo(1);
        assertThat(tiny.cell(ThinkingLevel.LOW).meaning().text()).contains("안 들어감");
    }

    @Test
    @DisplayName("짧은 응답 사이트의 위험은 입력이 아니라 생각 자리 — 입력 예산이 모자랄 때만 '입력 초과 위험' 으로 말한다")
    void shortSitesReportInputRiskOnlyWhenItExists() {
        Cell tight = ThinkingPreviewHarness.builder().window(16_384).build().row(ThinkingSite.SUMMARY).cell(ThinkingLevel.LOW);
        assertThat(tight.meaning().text()).as("요약은 이력 상한(5,000자)을 통째로 넣고, 16k 창에서 기본 예약 10,000 이 입력 예산을 4,746 으로 만든다")
                .contains("초과 위험");

        Cell roomy = ThinkingPreviewHarness.builder().window(32_768).build().row(ThinkingSite.SUMMARY).cell(ThinkingLevel.LOW);
        assertThat(roomy.meaning().text()).contains("걱정 없음");
    }

    @Test
    @DisplayName("리랭크 — 후보 풀(top-K × 후보 배수) 중 입력 예산에 들어가는 수")
    void rerankCandidates() {
        Cell cell = ThinkingPreviewHarness.builder().window(16_384).build().row(ThinkingSite.RERANK).cell(ThinkingLevel.LOW);

        assertThat(cell.meaning().kind()).isEqualTo(ThinkingPreview.Meaning.Kind.CANDIDATES);
        assertThat(cell.meaning().known()).isTrue();
        assertThat(cell.meaning().metric()).isBetween(1L, 30L);   // 하네스의 top-K 10 × 후보 배수 3
    }

    @Test
    @DisplayName("Direct 답변 — 문서 자리가 이력으로 돌아온다: HistoryPolicy 가 같은 식으로 낸 값과 같다")
    void directAnswerHistoryCapMatchesHistoryPolicy() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().window(16_384).build();
        Cell low = h.row(ThinkingSite.ANSWER_DIRECT_N).cell(ThinkingLevel.LOW);

        long expected = HistoryPolicy.budgetChars(16_384, low.reservedTokens(), 0,
                ThinkingPreviewService.koreanTokens(ThinkingPreviewService.QUESTION_CHARS), 5_000);
        assertThat(low.meaning().metric()).isEqualTo(expected);
    }

    // ── 저장 전 차이 ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("저장된 값과의 차이 — 저장된 수준의 칸은 비어 있고, 다른 칸에는 예약·입력·뜻하는 것·최악 소요의 증감이 있다")
    void diffAgainstTheSavedLevel() {
        Row eval = ThinkingPreviewHarness.builder()
                .file(ThinkingPreviewHarness.levels(ThinkingSite.EVAL, ThinkingLevel.MEDIUM)).build().row(ThinkingSite.EVAL);

        assertThat(eval.cell(ThinkingLevel.MEDIUM).diff().none()).isTrue();
        List<String> high = eval.cell(ThinkingLevel.HIGH).diff().parts();
        assertThat(high).anyMatch(p -> p.contains("예약") && p.contains("+1,024"));
        assertThat(high).anyMatch(p -> p.contains("입력") && p.contains("-1,024"));
        assertThat(high).anyMatch(p -> p.contains("발췌"));
        List<String> off = eval.cell(ThinkingLevel.OFF).diff().parts();
        assertThat(off).anyMatch(p -> p.contains("예약") && p.contains("-1,024"));
    }

    // ── 관측 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("관측한 생각량이 생각 자리보다 크면 '잘림 거의 확실' — 중앙값 기준. 관측이 3건 미만이면 판정하지 않는다")
    void observedThinkingDrivesTheTruncationBadges() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().build();
        for (int i = 0; i < 2; i++) {
            h.observations.record(ThinkingSite.EVAL, ThinkingPreviewHarness.PROVIDER, ThinkingLevel.LOW,
                    sample(ThinkingWire.Sent.ON, 3_000, 2_900, true, false, 40_000));
        }
        assertThat(h.row(ThinkingSite.EVAL).cell(ThinkingLevel.LOW).has(Badge.Kind.TRUNCATION_LIKELY))
                .as("표본 2건은 판정하기에 모자란다").isFalse();

        h.observations.record(ThinkingSite.EVAL, ThinkingPreviewHarness.PROVIDER, ThinkingLevel.LOW,
                sample(ThinkingWire.Sent.ON, 3_000, 2_900, true, false, 40_000));
        Cell low = h.row(ThinkingSite.EVAL).cell(ThinkingLevel.LOW);
        assertThat(low.thinkingRoom()).isEqualTo(2_160);
        assertThat(low.has(Badge.Kind.TRUNCATION_LIKELY)).as("생각 자리 2,160 < 관측 중앙값 2,900").isTrue();
    }

    @Test
    @DisplayName("관측이 없을 때의 '잘림 가능' — 생각 자리가 있되 요구한 여유보다 작을 때만")
    void withoutObservationsPossibleTruncationComparesToTheHeadroom() {
        // 프로바이더 max-tokens 1,000 → 요약은 1,000 을 예약하고 응답 필요분 800 을 빼면 생각 자리 200 < 낮게의 여유 512
        Cell low = ThinkingPreviewHarness.builder().providerMaxTokens(1_000).build()
                .row(ThinkingSite.SUMMARY).cell(ThinkingLevel.LOW);

        assertThat(low.thinkingRoom()).isEqualTo(200);
        assertThat(low.has(Badge.Kind.TRUNCATION_POSSIBLE)).isTrue();
        assertThat(low.has(Badge.Kind.TRUNCATION_LIKELY)).isFalse();
        assertThat(low.has(Badge.Kind.NO_ROOM)).isFalse();
    }

    @Test
    @DisplayName("최근 잘림 · 스위치 무시됨 — 표본의 finish_reason=length 와, 끔으로 보냈는데 생각이 관측된 호출")
    void recentTruncationAndIgnoredSwitch() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().build();
        h.observations.record(ThinkingSite.EVAL, ThinkingPreviewHarness.PROVIDER, ThinkingLevel.LOW,
                sample(ThinkingWire.Sent.ON, 2_560, 2_400, true, true, 40_000));
        h.observations.record(ThinkingSite.EVAL, ThinkingPreviewHarness.PROVIDER, ThinkingLevel.OFF,
                sample(ThinkingWire.Sent.OFF, 900, 800, true, false, 20_000));

        Row eval = h.row(ThinkingSite.EVAL);
        assertThat(eval.cell(ThinkingLevel.LOW).has(Badge.Kind.RECENT_TRUNCATION)).isTrue();
        assertThat(eval.cell(ThinkingLevel.OFF).has(Badge.Kind.SWITCH_IGNORED)).isTrue();
        assertThat(eval.cell(ThinkingLevel.LOW).has(Badge.Kind.SWITCH_IGNORED)).as("끔 칸에서만").isFalse();
        assertThat(eval.observations().get(ThinkingLevel.LOW.ordinal()).stats().truncated()).isEqualTo(1);
    }

    @Test
    @DisplayName("최악 소요 = 예약 ÷ 관측 생성 속도 — 속도는 그 프로바이더의 모든 표본에서 나오고, 표본이 모자라면 비어 있다")
    void worstCaseFromObservedSpeed() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().readTimeoutSeconds(30).build();
        assertThat(h.row(ThinkingSite.EVAL).cell(ThinkingLevel.LOW).worstCaseSeconds()).isNull();

        // 1,000 토큰 / 20초 = 50 tok/s — 사이트·수준이 달라도 같은 프로바이더의 속도다
        for (ThinkingSite site : List.of(ThinkingSite.CLASSIFY, ThinkingSite.TITLE, ThinkingSite.CONDENSE)) {
            h.observations.record(site, ThinkingPreviewHarness.PROVIDER, ThinkingLevel.OFF,
                    sample(ThinkingWire.Sent.OFF, 1_000, null, false, false, 20_000));
        }
        Cell low = h.row(ThinkingSite.EVAL).cell(ThinkingLevel.LOW);
        assertThat(h.row(ThinkingSite.EVAL).speed()).isEqualTo(50.0);
        assertThat(low.worstCaseSeconds()).as("예약 2,560 ÷ 50").isEqualTo(52L);
        assertThat(low.worstCaseText()).isEqualTo("52초");
        assertThat(low.has(Badge.Kind.TIMEOUT_EXCEEDED)).as("읽기 타임아웃 30초보다 길다").isTrue();
        assertThat(h.row(ThinkingSite.EVAL).cell(ThinkingLevel.HIGH).worstCaseText()).isEqualTo("1분 22초");

        Cell classify = h.row(ThinkingSite.CLASSIFY).cell(ThinkingLevel.LOW);
        assertThat(classify.worstCaseSeconds()).as("기본값 10,000 ÷ 50").isEqualTo(200L);
        assertThat(classify.has(Badge.Kind.LONG_WAIT)).as("대화형 호출이 60초 이상").isTrue();
        assertThat(h.row(ThinkingSite.KEYWORD_CONTEXT).cell(ThinkingLevel.LOW).has(Badge.Kind.LONG_WAIT))
                .as("인덱싱은 사용자가 기다리는 호출이 아니다").isFalse();
    }

    // ── 라우팅 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1순위가 차단돼 다른 프로바이더가 받으면 계산은 지금 받는 쪽으로 하고, 차단 중이라는 사실을 적는다")
    void blockedFirstChoiceIsReportedAndNotUsedForTheNumbers() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().window(16_384).build();
        when(h.router.findProviderName(any(TaskType.class), any(RoutingMode.class))).thenReturn("remote");
        h.windows.record("remote", 131_072, ProviderContextWindows.Source.CONFIGURED);

        Row eval = h.row(ThinkingSite.EVAL);
        assertThat(eval.receiver().name()).isEqualTo("remote");
        assertThat(eval.receiver().blockedFallback()).isTrue();
        assertThat(eval.receiver().note()).contains(ThinkingPreviewHarness.PROVIDER).contains("remote").contains("차단");
        assertThat(eval.receiver().window()).isEqualTo(131_072);
        assertThat(eval.cell(ThinkingLevel.LOW).wire().sent()).as("remote 는 dialect 기록이 없다 — 아무것도 싣지 않는다")
                .isEqualTo(ThinkingWire.Sent.NOTHING);
    }

    @Test
    @DisplayName("대화의 라우팅 모드 때문에 다른 프로바이더가 받으면 모드별 줄을 더한다 — 같은 프로바이더면 줄을 만들지 않는다")
    void modeLinesOnlyWhenAnotherProviderReceives() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().build();
        assertThat(h.row(ThinkingSite.EVAL).modeLines()).isEmpty();

        when(h.router.findProviderName(any(TaskType.class), eq(RoutingMode.QUALITY_FIRST))).thenReturn("remote");
        when(h.router.findNominalProviderName(any(TaskType.class), eq(RoutingMode.QUALITY_FIRST))).thenReturn(Optional.of("remote"));
        h.windows.record("remote", 131_072, ProviderContextWindows.Source.CONFIGURED);

        Row eval = h.row(ThinkingSite.EVAL);
        assertThat(eval.modeLines()).singleElement().satisfies(line -> {
            assertThat(line.mode()).isEqualTo(RoutingMode.QUALITY_FIRST);
            assertThat(line.receiver().name()).isEqualTo("remote");
            assertThat(line.cell().inputBudget()).as("128k 창이라 입력이 훨씬 넉넉하다").isGreaterThan(100_000);
        });
        assertThat(h.row(ThinkingSite.CLASSIFY).modeLines()).as("고정 모드 사이트는 대화의 모드를 따르지 않는다").isEmpty();
    }

    @Test
    @DisplayName("한 사이트만 새로 계산해도 카드 전체의 같은 행과 같다 — [저장] 뒤 행 교체가 낡은 숫자를 남기지 않는다")
    void singleRowEqualsTheRowOfTheWholeCard() {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder().build();
        Row alone = h.service.row(ThinkingSite.TXT_TO_MD);
        Row inCard = h.service.preview().groups().stream().flatMap(g -> g.rows().stream())
                .filter(r -> r.site() == ThinkingSite.TXT_TO_MD).findFirst().orElseThrow();

        assertThat(alone).usingRecursiveComparison().isEqualTo(inCard);
    }
}
