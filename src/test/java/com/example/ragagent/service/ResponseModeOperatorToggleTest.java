package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.SettingsView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C(응용) 응답 모드의 운영자 스위치 ({@code app.llm.creative-mode-enabled}).
 *
 * <p>판정이 두 조각으로 나뉘어 있다 — enum 은 "끌 수 있는 모드인가"
 * ({@link ResponseMode#operatorToggleable()}), 설정 계층은 "지금 꺼져 있는가"
 * ({@code SettingsService.creativeModeEnabled()}) 를 알고, 둘을 합치는 곳은
 * {@link SettingsService#effectiveResponseMode(ResponseMode)} 하나다. 여기서 고정하는 것은
 * 그 합성 규칙과, 그것이 성립하기 위해 enum 이 지켜야 하는 불변식이다.
 */
@ResourceLock("global-state")
class ResponseModeOperatorToggleTest {

    /** Lightweight in-memory stand-in for the SQLite repository (SettingsServiceTest 와 같은 형태). */
    private static final class RepoStub
            extends com.example.ragagent.repository.SettingsOverrideRepository {
        final Map<String, String> store = new LinkedHashMap<>();
        RepoStub() { super(null); }
        @Override public Map<String, String> findAll() { return new LinkedHashMap<>(store); }
        @Override public void upsert(String key, String value) { store.put(key, value); }
        @Override public int delete(String key) { return store.remove(key) != null ? 1 : 0; }
    }

    private AppProperties props;
    private SettingsService service;

    /** §6.15 — /settings 저장 행 전용 의존성. 이 테스트가 보는 것과 무관하고 디스크도 건드리지 않는다. */
    private static StorageQuotaService quotaService() {
        return new StorageQuotaService(propsWithLlm());
    }

    private static AppProperties propsWithLlm() {
        AppProperties.LlmConfig llm = new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, null, 6000, 1, false);
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                llm, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @BeforeEach
    void setup() {
        CircuitBreaker cb = mock(CircuitBreaker.class);
        when(cb.getBlockedProviders()).thenReturn(Map.<String, Instant>of());
        props = propsWithLlm();
        service = new SettingsService(new RepoStub(), props, mock(AuditLogger.class), cb, new ProviderToggle(), new ProviderContextWindows(), quotaService());
        service.init();
    }

    @AfterEach
    void tearDown() {
        AppProperties.unbindOverrides();
    }

    // ── enum 쪽 불변식 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("기본 모드는 절대 끌 수 있는 모드가 아니다 — 강등 대상이 자기 자신이 되면 스위치가 무의미해진다")
    void defaultModeIsNeverToggleable() {
        assertThat(ResponseMode.DEFAULT.operatorToggleable())
                .as("DEFAULT(%s) 를 끌 수 있게 만들면 effectiveResponseMode() 의 강등이 무한정 제자리다",
                        ResponseMode.DEFAULT)
                .isFalse();
    }

    @Test
    @DisplayName("끌 수 있는 모드가 최소 하나는 있다 — 없으면 이 스위치가 아무것도 지키지 않는다")
    void atLeastOneModeIsToggleable() {
        assertThat(Arrays.stream(ResponseMode.values()).filter(ResponseMode::operatorToggleable).toList())
                .containsExactly(ResponseMode.C);
    }

    // ── 합성 규칙 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("기본은 ON — 스위치가 생기기 전 동작(C 항상 열림)이 미설정 시 그대로 유지된다")
    void enabledByDefault() {
        assertThat(service.creativeModeEnabled()).isTrue();
        assertThat(service.effectiveResponseMode(ResponseMode.C)).isEqualTo(ResponseMode.C);
    }

    @Test
    @DisplayName("끄면 끌 수 있는 모든 모드가 N으로 강등되고, 나머지는 손대지 않는다")
    void disabling_downgradesOnlyToggleableModes() {
        service.update(SettingsKeys.LLM_CREATIVE_MODE_ENABLED, "false");

        assertThat(service.creativeModeEnabled()).isFalse();
        assertThat(props.llmSafe().creativeModeEnabled()).isFalse();   // pull-side(설정 페이지·컨트롤러)도 같은 값
        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(service.effectiveResponseMode(mode))
                    .as("%s", mode)
                    .isEqualTo(mode.operatorToggleable() ? ResponseMode.DEFAULT : mode);
        }
    }

    @Test
    @DisplayName("다시 켜면 원래대로 — 오버라이드 삭제(기본값 복귀)도 마찬가지")
    void reEnablingRestoresTheMode() {
        service.update(SettingsKeys.LLM_CREATIVE_MODE_ENABLED, "false");
        service.update(SettingsKeys.LLM_CREATIVE_MODE_ENABLED, "true");
        assertThat(service.effectiveResponseMode(ResponseMode.C)).isEqualTo(ResponseMode.C);

        service.update(SettingsKeys.LLM_CREATIVE_MODE_ENABLED, "false");
        service.reset(SettingsKeys.LLM_CREATIVE_MODE_ENABLED);
        assertThat(service.effectiveResponseMode(ResponseMode.C)).isEqualTo(ResponseMode.C);
    }

    @Test
    @DisplayName("null 요청은 기본 모드로 — 강등 경로가 널을 흘려보내지 않는다")
    void nullRequestFallsBackToDefault() {
        assertThat(service.effectiveResponseMode(null)).isEqualTo(ResponseMode.DEFAULT);
    }

    // ── 화면 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LLM 그룹에서 C 두 행은 붙어 있고 온도가 먼저다 — 스위치는 그 온도를 무의미하게 만드는 쪽")
    void creativeRowsSitTogetherWithTheKnobFirst() {
        var llmKeys = service.buildView().groups().stream()
                .filter(g -> "llm_hot".equals(g.id()))
                .flatMap(g -> g.items().stream())
                .map(SettingsView.SettingItem::key)
                .filter(k -> k != null)
                .toList();

        int temp = llmKeys.indexOf(SettingsKeys.LLM_CREATIVE_TEMPERATURE);
        int gate = llmKeys.indexOf(SettingsKeys.LLM_CREATIVE_MODE_ENABLED);
        assertThat(temp).as("C 온도 행이 없다").isNotNegative();
        assertThat(gate).as("C 스위치 행이 없다").isEqualTo(temp + 1);
    }

    @Test
    @DisplayName("/settings 의 LLM 그룹에 이 행이 렌더되고, 라벨 키가 한/영 번들에 있다")
    void settingsRowIsRenderedAndLabelled() {
        var row = service.buildView().groups().stream()
                .filter(g -> "llm_hot".equals(g.id()))
                .flatMap(g -> g.items().stream())
                .filter(i -> SettingsKeys.LLM_CREATIVE_MODE_ENABLED.equals(i.key()))
                .findFirst();
        assertThat(row).as("LLM 튜닝 그룹에 C 모드 스위치 행이 없다").isPresent();
        assertThat(row.get().type()).isEqualTo("bool");
        assertThat(row.get().editable()).isTrue();
        assertThat(row.get().value()).isEqualTo("true");

        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);   // ENGLISH 조회가 ko 로 폴백되면 영어 번들을 안 읽는다
        for (Locale locale : new Locale[]{Locale.KOREAN, Locale.ENGLISH}) {
            assertThat(ms.getMessage(row.get().label(), null, "MISSING", locale))
                    .as("%s (%s)", row.get().label(), locale)
                    .isNotEqualTo("MISSING");
        }
    }
}
