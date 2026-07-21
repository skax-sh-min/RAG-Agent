package com.example.ragagent.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.model.SettingsView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
class SettingsServiceTest {

    private static AppProperties base() {
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                null, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null);
    }

    private SettingsOverrideRepositoryStub repo;
    private AuditLogger audit;
    private CircuitBreaker circuitBreaker;
    private AppProperties props;
    private SettingsService service;

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
        props = base();
        service = new SettingsService(repo, props, audit, circuitBreaker);
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
    @DisplayName("buildView — search_hot/fixed/indexing/llm_hot/cache 5그룹, 모든 핫 키가 편집 가능 항목으로 노출")
    void buildView_structure() {
        SettingsView view = service.buildView();

        assertThat(view.groups()).hasSize(5);
        assertThat(view.groups().get(0).id()).isEqualTo("search_hot");
        assertThat(view.groups().get(0).items()).allMatch(SettingsView.SettingItem::editable);
        // indexing + llm_hot groups exist (chunk/file-concurrency + direct-temperature knobs live here)
        assertThat(view.groups()).anyMatch(g -> g.id().equals("indexing"));
        assertThat(view.groups()).anyMatch(g -> g.id().equals("llm_hot"));
        // every hot-editable key is rendered as an editable row somewhere (search_hot + indexing + llm_hot)
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
    @DisplayName("init — env/properties와 다른 SQLite 오버라이드는 시작 시 WARN 로그로 알린다 (일치하는 오버라이드는 조용히)")
    void init_warnsWhenPersistedOverrideDivergesFromEnv() {
        // The @BeforeEach already bound the (empty-store) service. Unbind so the fresh service below
        // captures its base values while nothing is bound — mirroring a real first-boot.
        AppProperties.unbindOverrides();

        SettingsOverrideRepositoryStub seeded = new SettingsOverrideRepositoryStub();
        seeded.store.put(SettingsKeys.SEARCH_RRF_K, "80");  // application.properties default = 60 → diverges → WARN
        seeded.store.put(SettingsKeys.SEARCH_TOP_K, "7");   // default = 7 → identical → must NOT warn
        SettingsService fresh = new SettingsService(seeded, base(), audit, circuitBreaker);

        Logger settingsLogger = (Logger) LoggerFactory.getLogger(SettingsService.class);
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
