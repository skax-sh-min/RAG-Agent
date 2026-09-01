package com.example.ragagent.service;

import org.junit.jupiter.api.parallel.ResourceLock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.model.SettingsView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SettingsService: validation, persistence, reset-to-default, audit, and the effective
 * value flowing back through {@code AppProperties.xxxSafe()} (so the page shows what the next
 * search will use).
 */
@ResourceLock("global-state")
class SettingsServiceTest {

    private static AppProperties base() {
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                null, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private SettingsOverrideRepositoryStub repo;
    private AuditLogger audit;
    private CircuitBreaker circuitBreaker;
    private ProviderToggle toggle;
    private AppProperties props;
    private SettingsService service;

    /** AppProperties with LLM providers (base() has none) — for the enable/disable tests. */
    private static AppProperties propsWithProviders(String... names) {
        java.util.List<AppProperties.ProviderConfig> pcs = new java.util.ArrayList<>();
        for (String n : names) {
            pcs.add(new AppProperties.ProviderConfig(
                    n, "http://x/v1", "key", "model", "BOTH", "LOCAL", 1, true, null, null, null));
        }
        AppProperties.LlmConfig llm = new AppProperties.LlmConfig(
                pcs, 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, false);
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                llm, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** AppProperties with LLM providers of specific roles under a given default routing mode. */
    private static AppProperties propsWithProvidersOfRoles(String routingMode, Map<String, String> nameToRole) {
        List<AppProperties.ProviderConfig> pcs = new java.util.ArrayList<>();
        nameToRole.forEach((name, role) -> pcs.add(new AppProperties.ProviderConfig(
                name, "http://x/v1", "key", "model", "BOTH", role, 1, true, null, null, null)));
        AppProperties.LlmConfig llm = new AppProperties.LlmConfig(
                pcs, 2, 10, 180, routingMode, 0.6, 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, false);
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                llm, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Lightweight in-memory stand-in for the SQLite repository. */
    private static final class SettingsOverrideRepositoryStub
            extends com.example.ragagent.repository.SettingsOverrideRepository {
        final Map<String, String> store = new LinkedHashMap<>();
        SettingsOverrideRepositoryStub() { super(null); }
        @Override public Map<String, String> findAll() { return new LinkedHashMap<>(store); }
        @Override public void upsert(String key, String value) { store.put(key, value); }
        @Override public int delete(String key) { return store.remove(key) != null ? 1 : 0; }
    }

    @BeforeEach
    void setup() {
        repo = new SettingsOverrideRepositoryStub();
        audit = mock(AuditLogger.class);
        circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.getBlockedProviders()).thenReturn(Map.<String, Instant>of());
        toggle = new ProviderToggle();
        props = base();
        service = new SettingsService(repo, props, audit, circuitBreaker, toggle, new ProviderContextWindows());
        service.init(); // loads (empty) overrides + binds the static override source to this service
    }

    @AfterEach
    void tearDown() {
        AppProperties.unbindOverrides();
    }

    @Test
    @DisplayName("update — 유효 값 저장 후 effective 값·프로퍼티 접근자에 즉시 반영 + 감사 로그")
    void update_persistsAndReflectsAndAudits() {
        String after = service.update(SettingsKeys.SEARCH_RRF_K, "80");

        assertThat(after).isEqualTo("80");
        assertThat(repo.store).containsEntry(SettingsKeys.SEARCH_RRF_K, "80");
        assertThat(props.searchRrfKSafe()).isEqualTo(80);          // pull-side (RetrievalService) sees it
        assertThat(service.get(SettingsKeys.SEARCH_RRF_K)).isEqualTo("80");
        verify(audit).log(eq("settings.update"), eq(SettingsKeys.SEARCH_RRF_K), anyMap());
    }

    @Test
    @DisplayName("reset — 오버라이드 삭제 시 프로퍼티 기본값으로 정확히 복귀")
    void reset_revertsToPropertyDefault() {
        service.update(SettingsKeys.SEARCH_RRF_K, "80");
        assertThat(props.searchRrfKSafe()).isEqualTo(80);

        service.reset(SettingsKeys.SEARCH_RRF_K);

        assertThat(repo.store).doesNotContainKey(SettingsKeys.SEARCH_RRF_K);
        assertThat(service.get(SettingsKeys.SEARCH_RRF_K)).isNull();
        assertThat(props.searchRrfKSafe()).isEqualTo(60);          // back to application.properties default
        verify(audit).log(eq("settings.reset"), eq(SettingsKeys.SEARCH_RRF_K), anyMap());
    }

    @Test
    @DisplayName("update — 범위를 벗어난 값은 거부(예외)하고 저장하지 않는다")
    void update_outOfRange_rejected() {
        assertThatThrownBy(() -> service.update(SettingsKeys.SEARCH_RRF_K, "0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.store).doesNotContainKey(SettingsKeys.SEARCH_RRF_K);
        assertThat(props.searchRrfKSafe()).isEqualTo(60);
    }

    @Test
    @DisplayName("인덱싱 3종의 허용 범위 — 경계값은 통과, 한 칸 넘으면 거부")
    void indexingKeys_enforceTheirNarrowedRanges() {
        // 하한은 셋이 다르다 — 동시성은 1(0개 워커는 무의미), 온도는 0(완전 결정적이 기본값이다).
        record Bound(String key, String atMax, String overMax, String underMin) {}
        List<Bound> bounds = List.of(
                new Bound(SettingsKeys.INDEXING_MAX_CONCURRENT_FILES, "4", "5", "0"),
                new Bound(SettingsKeys.INDEXING_MAX_CONCURRENT_LLM, "8", "9", "0"),
                new Bound(SettingsKeys.LLM_INDEXING_TEMPERATURE, "0.1", "0.2", "-0.1"));

        for (Bound b : bounds) {
            assertThat(service.update(b.key(), b.atMax()))
                    .as("%s 의 상한값 자체는 저장돼야 한다", b.key()).isEqualTo(b.atMax());
            assertThatThrownBy(() -> service.update(b.key(), b.overMax()))
                    .as("%s = %s 는 거부돼야 한다", b.key(), b.overMax())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.update(b.key(), b.underMin()))
                    .as("%s = %s 는 하한 아래라 거부돼야 한다", b.key(), b.underMin())
                    .isInstanceOf(IllegalArgumentException.class);
            // 거부된 값이 저장되지 않았는지 — 상한값이 그대로 남아 있어야 한다
            assertThat(repo.store).containsEntry(b.key(), b.atMax());
        }
    }

    @Test
    @DisplayName("indexing-temperature 는 /settings 스펙 상한과 llmSafe() clamp 상한이 같다")
    void indexingTemperature_specMaxMatchesClamp() {
        // 한쪽만 좁으면 화면이 거부하는 값이 환경변수로는 그대로 적용된다 — 두 파일에 나뉘어 있어
        // 컴파일러가 잡아주지 못하는 짝이다.
        service.update(SettingsKeys.LLM_INDEXING_TEMPERATURE, "0.1");
        assertThat(props.llmSafe().indexingTemperature()).isEqualTo(0.1);
        assertThatThrownBy(() -> service.update(SettingsKeys.LLM_INDEXING_TEMPERATURE, "0.2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update — 알 수 없는/비-핫 키는 거부")
    void update_unknownKey_rejected() {
        // rerank-enabled is surfaced read-only (structural @ConditionalOnProperty bean), never hot
        assertThatThrownBy(() -> service.update("search-rerank-enabled", "true"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("nonsense", "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update — boolean 키는 true/false만 허용, 그 외는 거부")
    void update_boolValidation() {
        service.update(SettingsKeys.SEARCH_RETRY_ESCALATE, "false");
        assertThat(props.searchRetryEscalateSafe()).isFalse();
        assertThat(repo.store).containsEntry(SettingsKeys.SEARCH_RETRY_ESCALATE, "false");

        assertThatThrownBy(() -> service.update(SettingsKeys.SEARCH_RETRY_ESCALATE, "yes"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update — double 값은 정규화되어 저장(60.0 → 60, 0.50 → 0.5)")
    void update_doubleCanonicalization() {
        service.update(SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT, "2.50");
        assertThat(repo.store).containsEntry(SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT, "2.5");

        service.update(SettingsKeys.SEARCH_SIMILARITY_THRESHOLD, "0.50");
        assertThat(repo.store).containsEntry(SettingsKeys.SEARCH_SIMILARITY_THRESHOLD, "0.5");
    }

    @Test
    @DisplayName("update — 검증 실패 시 감사 로그를 남기지 않는다")
    void update_invalid_notAudited() {
        assertThatThrownBy(() -> service.update(SettingsKeys.SEARCH_RRF_K, "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(audit, never()).log(eq("settings.update"), eq(SettingsKeys.SEARCH_RRF_K), anyMap());
    }

    @Test
    @DisplayName("buildView — search_hot/fixed/indexing/llm_hot/ui_hot/cache 6그룹, 모든 핫 키가 편집 가능 항목으로 노출")
    void buildView_structure() {
        SettingsView view = service.buildView();

        assertThat(view.groups()).hasSize(6);
        assertThat(view.groups().get(0).id()).isEqualTo("search_hot");
        assertThat(view.groups().get(0).items()).allMatch(SettingsView.SettingItem::editable);
        // indexing + llm_hot groups exist (chunk/file-concurrency + direct-temperature knobs live here)
        assertThat(view.groups()).anyMatch(g -> g.id().equals("indexing"));
        assertThat(view.groups()).anyMatch(g -> g.id().equals("llm_hot"));
        assertThat(view.groups()).anyMatch(g -> g.id().equals("ui_hot"));
        // every hot-editable key is rendered as an editable row somewhere (search_hot + indexing + llm_hot + ui_hot)
        long editableCount = view.groups().stream()
                .flatMap(g -> g.items().stream())
                .filter(SettingsView.SettingItem::editable)
                .count();
        assertThat(editableCount).isEqualTo(SettingsKeys.HOT_EDITABLE.size());
        assertThat(view.vectorStoreType()).isEqualTo("chroma");
        assertThat(view.defaultRoutingMode()).isEqualTo("COST_FIRST");
        assertThat(view.providers()).isEmpty();
    }

    @Test
    @DisplayName("buildView — LLM 튜닝 그룹에 creative-temperature 행이 뜨고 현재값이 채워진다 (§6.24 Step 2-b)")
    void buildView_llmGroupRendersCreativeTemperature() {
        // 배선이 한 곳이라도 빠지면 '조용히' 실패한다 — 값은 반영되는데 화면에 안 뜨거나(②③),
        // 화면엔 뜨는데 현재값이 비거나(④), 라벨 자리에 키 문자열이 그대로 나온다(⑦).
        SettingsView.SettingItem item = service.buildView().groups().stream()
                .filter(g -> g.id().equals("llm_hot"))
                .flatMap(g -> g.items().stream())
                .filter(i -> i.key().equals(SettingsKeys.LLM_CREATIVE_TEMPERATURE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("llm_hot 그룹에 creative-temperature 행이 없다"));

        assertThat(item.editable()).isTrue();
        assertThat(item.value()).isEqualTo("0.7");                       // ④ 현재값
        assertThat(item.max()).isEqualTo(1.0);                           // ③ clamp 범위
        assertThat(item.label()).isEqualTo("settings.item.creative-temperature"); // ⑦ 라벨 키
    }

    @Test
    @DisplayName("update — creative-temperature 는 [0.0, 1.0] 범위로 검증된다 (일반 temperature의 0.3 상한과 무관)")
    void update_creativeTemperature_range() {
        service.update(SettingsKeys.LLM_CREATIVE_TEMPERATURE, "0.9");
        assertThat(props.llmSafe().creativeTemperature()).isEqualTo(0.9);

        // 0.9 는 일반 temperature 였다면 거부됐을 값이다 — 두 knob 이 실제로 갈려 있는지 확인.
        assertThatThrownBy(() -> service.update(SettingsKeys.LLM_TEMPERATURE, "0.9"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(SettingsKeys.LLM_CREATIVE_TEMPERATURE, "1.4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("init — env/properties와 다른 SQLite 오버라이드는 시작 시 WARN 로그로 알린다 (일치하는 오버라이드는 조용히)")
    void init_warnsWhenPersistedOverrideDivergesFromEnv() {
        // The @BeforeEach already bound the (empty-store) service. Unbind so the fresh service below
        // captures its base values while nothing is bound — mirroring a real first-boot.
        AppProperties.unbindOverrides();

        SettingsOverrideRepositoryStub seeded = new SettingsOverrideRepositoryStub();
        seeded.store.put(SettingsKeys.SEARCH_RRF_K, "80");  // application.properties default = 60 → diverges → WARN
        seeded.store.put(SettingsKeys.SEARCH_TOP_K, "7");   // default = 7 → identical → must NOT warn
        SettingsService fresh = new SettingsService(seeded, base(), audit, circuitBreaker, new ProviderToggle(), new ProviderContextWindows());

        Logger settingsLogger = com.example.ragagent.LogbackTestSupport.logger(SettingsService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        settingsLogger.addAppender(appender);
        try {
            fresh.init();
        } finally {
            settingsLogger.detachAppender(appender);
        }

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).hasSize(1);                       // only the diverging key, not the matching one
        String msg = warns.getFirst().getFormattedMessage();
        assertThat(msg)
                .contains(SettingsKeys.SEARCH_RRF_K)
                .contains("80")                             // effective (override) value
                .contains("60")                             // env/properties value
                .doesNotContain(SettingsKeys.SEARCH_TOP_K); // identical override is not flagged
    }

    @Test
    @DisplayName("setProviderEnabled — 비활성화/재활성화가 ProviderToggle·row·감사로그에 반영된다")
    void setProviderEnabled_disablesThenEnables() {
        ProviderToggle tg = new ProviderToggle();
        SettingsService svc = new SettingsService(repo, propsWithProviders("a", "b"), audit, circuitBreaker, tg,
                new ProviderContextWindows());

        List<SettingsView.ProviderRow> afterDisable = svc.setProviderEnabled("a", false);
        assertThat(tg.isDisabled("a")).isTrue();
        assertThat(afterDisable.stream().filter(r -> r.name().equals("a")).findFirst().orElseThrow().enabled())
                .isFalse();
        assertThat(afterDisable.stream().filter(r -> r.name().equals("b")).findFirst().orElseThrow().enabled())
                .isTrue();
        verify(audit).log(eq("settings.provider.toggle"), eq("a"), anyMap());

        svc.setProviderEnabled("a", true);
        assertThat(tg.isEnabled("a")).isTrue();
    }

    @Test
    @DisplayName("setProviderEnabled — 알 수 없는 프로바이더 이름은 거부")
    void setProviderEnabled_unknownName_rejected() {
        SettingsService svc = new SettingsService(repo, propsWithProviders("a"), audit, circuitBreaker, new ProviderToggle(), new ProviderContextWindows());
        assertThatThrownBy(() -> svc.setProviderEnabled("nope", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("setProviderEnabled — 마지막으로 활성화된 프로바이더는 비활성화할 수 없다")
    void setProviderEnabled_lastEnabled_rejected() {
        ProviderToggle tg = new ProviderToggle();
        SettingsService svc = new SettingsService(repo, propsWithProviders("a", "b"), audit, circuitBreaker, tg,
                new ProviderContextWindows());
        svc.setProviderEnabled("a", false);   // one left enabled (b)

        assertThatThrownBy(() -> svc.setProviderEnabled("b", false))  // would disable the last one
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tg.isEnabled("b")).isTrue();                       // still enabled — guard held
    }

    @Test
    @DisplayName("providerRows — LOCAL_ONLY 배포에서는 LOCAL 역할 프로바이더만 노출된다")
    void providerRows_localOnlyDeployment_showsOnlyLocalRoleProviders() {
        Map<String, String> nameToRole = new LinkedHashMap<>();
        nameToRole.put("local", "LOCAL");
        nameToRole.put("gemini-flash", "NORMAL");
        nameToRole.put("openai", "PREMIUM");
        SettingsService svc = new SettingsService(
                repo, propsWithProvidersOfRoles("LOCAL_ONLY", nameToRole), audit, circuitBreaker, new ProviderToggle(), new ProviderContextWindows());

        List<SettingsView.ProviderRow> rows = svc.providerRows();

        assertThat(rows).extracting(SettingsView.ProviderRow::name).containsExactly("local");
    }

    @Test
    @DisplayName("providerRows — LOCAL_ONLY 가 아니면 모든 역할의 프로바이더가 그대로 노출된다")
    void providerRows_nonLocalOnlyDeployment_showsAllRoles() {
        Map<String, String> nameToRole = new LinkedHashMap<>();
        nameToRole.put("local", "LOCAL");
        nameToRole.put("gemini-flash", "NORMAL");
        SettingsService svc = new SettingsService(
                repo, propsWithProvidersOfRoles("COST_FIRST", nameToRole), audit, circuitBreaker, new ProviderToggle(), new ProviderContextWindows());

        List<SettingsView.ProviderRow> rows = svc.providerRows();

        assertThat(rows).extracting(SettingsView.ProviderRow::name)
                .containsExactlyInAnyOrder("local", "gemini-flash");
    }

    @Test
    @DisplayName("setProviderEnabled — LOCAL_ONLY 배포에서는 숨겨진 비-LOCAL 프로바이더 토글이 거부된다")
    void setProviderEnabled_localOnlyDeployment_rejectsHiddenNonLocalProvider() {
        Map<String, String> nameToRole = new LinkedHashMap<>();
        nameToRole.put("local", "LOCAL");
        nameToRole.put("gemini-flash", "NORMAL");
        SettingsService svc = new SettingsService(
                repo, propsWithProvidersOfRoles("LOCAL_ONLY", nameToRole), audit, circuitBreaker, new ProviderToggle(), new ProviderContextWindows());

        assertThatThrownBy(() -> svc.setProviderEnabled("gemini-flash", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("editableItem — 오버라이드 후 overridden=true, value=오버라이드 값")
    void editableItem_reflectsOverride() {
        assertThat(service.editableItem(SettingsKeys.SEARCH_RRF_K).overridden()).isFalse();

        service.update(SettingsKeys.SEARCH_RRF_K, "77");

        SettingsView.SettingItem item = service.editableItem(SettingsKeys.SEARCH_RRF_K);
        assertThat(item.overridden()).isTrue();
        assertThat(item.value()).isEqualTo("77");
        assertThat(item.editable()).isTrue();
    }
}
