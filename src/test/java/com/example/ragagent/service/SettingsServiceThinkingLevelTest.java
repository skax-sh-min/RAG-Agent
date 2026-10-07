package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.SettingsView;
import com.example.ragagent.repository.SettingsOverrideRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Instant;
import java.util.LinkedHashMap;
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
 * 호출 지점별 생각 수준의 설정 계층(PLAN §6.29 ⑦-아): {@code Kind.CHOICE} 의 검증, 사이트에서 <b>생성되는</b> 키,
 * 오버라이드가 {@code props.llmSafe().thinkingLevel()} 로 흘러가는 것, 그리고 프로바이더 표의 "생각 제어" 열.
 *
 * <p>허용 값은 넷({@code off·low·medium·high})뿐이다 — "서버 기본값" 같은 수준은 일부러 없다(§6.29 ①). 이 계층이 그 값을
 * 400 으로 거부해야 화면이 무엇을 보내든 서버가 지킨다.
 */
@ResourceLock("global-state")
class SettingsServiceThinkingLevelTest {

    private static final class RepoStub extends SettingsOverrideRepository {
        final Map<String, String> store = new LinkedHashMap<>();

        RepoStub() {
            super(null);
        }

        @Override public Map<String, String> findAll() { return new LinkedHashMap<>(store); }
        @Override public void upsert(String key, String value) { store.put(key, value); }
        @Override public int delete(String key) { return store.remove(key) != null ? 1 : 0; }
    }

    private RepoStub repo;
    private AuditLogger audit;
    private ThinkingPreviewHarness harness;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        repo = new RepoStub();
        audit = mock(AuditLogger.class);
        harness = ThinkingPreviewHarness.builder().build();
        service = newService();
    }

    @AfterEach
    void unbind() {
        service.shutdown();   // 정적 오버라이드 소스를 풀어 다른 테스트로 새지 않게 한다
    }

    private SettingsService newService() {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.getBlockedProviders()).thenReturn(Map.<String, Instant>of());
        SettingsService s = new SettingsService(repo, harness.props, audit, circuitBreaker, new ProviderToggle(),
                harness.windows, mock(StorageQuotaService.class), harness.dialects);
        s.init();   // 저장된 오버라이드를 읽고 정적 오버라이드 소스에 자기를 묶는다
        return s;
    }

    private ThinkingLevel effective(ThinkingSite site) {
        return harness.props.llmSafe().thinkingLevel(site);
    }

    @Test
    @DisplayName("저장하면 다음 호출부터 그 수준이다 — 감사 로그가 남고, 대소문자·공백은 정규화되며, '오버라이드됨' 이 선다")
    void updateTakesEffectImmediately() {
        assertThat(effective(ThinkingSite.EVAL)).as("출하값").isEqualTo(ThinkingLevel.LOW);
        assertThat(service.isOverridden(ThinkingSite.EVAL.settingsKey())).isFalse();

        String after = service.update(ThinkingSite.EVAL.settingsKey(), "  HIGH ");

        assertThat(after).isEqualTo("high");
        assertThat(repo.store).containsEntry("llm.thinking.eval", "high");
        assertThat(effective(ThinkingSite.EVAL)).isEqualTo(ThinkingLevel.HIGH);
        assertThat(service.isOverridden("llm.thinking.eval")).isTrue();
        assertThat(service.editableItem("llm.thinking.eval").value()).isEqualTo("high");
        assertThat(service.editableItem("llm.thinking.eval").type()).isEqualTo("choice");
        verify(audit).log(eq("settings.update"), eq("llm.thinking.eval"), anyMap());
        assertThat(effective(ThinkingSite.CLASSIFY)).as("다른 사이트는 그대로").isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    @DisplayName("허용 값은 넷뿐이다 — default·빈 값·모르는 값은 400 이고 아무것도 저장하지 않는다")
    void onlyTheFourLevelsAreAccepted() {
        for (String bad : new String[]{"default", "auto", "extreme", "1", "true"}) {
            assertThatThrownBy(() -> service.update("llm.thinking.eval", bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("off / low / medium / high");
        }
        assertThatThrownBy(() -> service.update("llm.thinking.eval", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.store).isEmpty();
        assertThat(effective(ThinkingSite.EVAL)).isEqualTo(ThinkingLevel.LOW);
        verify(audit, never()).log(eq("settings.update"), eq("llm.thinking.eval"), anyMap());
    }

    @Test
    @DisplayName("모르는 호출 지점의 키는 거부한다 — 키는 사이트에서 생성되므로 오타가 조용히 저장되지 않는다")
    void unknownSiteKeyIsRejected() {
        assertThatThrownBy(() -> service.update("llm.thinking.not-a-site", "low"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("수정할 수 없는");
        assertThatThrownBy(() -> service.reset("llm.thinking.not-a-site"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[기본값] 은 오버라이드를 지워 파일의 줄(없으면 출하값)로 돌아간다")
    void resetReturnsToTheFileValue() {
        service.update("llm.thinking.condense", "high");
        assertThat(effective(ThinkingSite.CONDENSE)).isEqualTo(ThinkingLevel.HIGH);

        service.reset("llm.thinking.condense");

        assertThat(effective(ThinkingSite.CONDENSE)).as("출하값 끔").isEqualTo(ThinkingLevel.OFF);
        assertThat(service.isOverridden("llm.thinking.condense")).isFalse();
        assertThat(repo.store).isEmpty();
        verify(audit).log(eq("settings.reset"), eq("llm.thinking.condense"), anyMap());
    }

    @Test
    @DisplayName("사이트마다 키가 생긴다 — 사이트를 더하면 설정 키·검증·읽기가 저절로 따라온다(손으로 맞출 곳이 없다)")
    void everySiteHasAnEditableKey() {
        for (ThinkingSite site : ThinkingSite.values()) {
            service.update(site.settingsKey(), "medium");
            assertThat(effective(site)).as(site.id()).isEqualTo(ThinkingLevel.MEDIUM);
        }
        assertThat(repo.store).hasSize(ThinkingSite.values().length);
    }

    @Test
    @DisplayName("저장된 오버라이드는 재기동 뒤에도 살아 있다")
    void overridesSurviveARestart() {
        service.update("llm.thinking.rerank", "off");
        service.shutdown();

        service = newService();   // 같은 저장소 위의 새 서비스 = 재기동

        assertThat(effective(ThinkingSite.RERANK)).isEqualTo(ThinkingLevel.OFF);
        assertThat(service.isOverridden("llm.thinking.rerank")).isTrue();
    }

    @Test
    @DisplayName("일반 항목 격자에는 나오지 않는다 — 이 카드가 유일한 편집 자리다(같은 키를 두 곳에서 고치면 한쪽이 낡는다)")
    void thinkingKeysAreNotInTheGenericGrid() {
        SettingsView view = service.buildView();

        assertThat(view.groups().stream().flatMap(g -> g.items().stream())
                .map(SettingsView.SettingItem::key))
                .noneMatch(key -> key != null && key.startsWith("llm.thinking."));
    }

    // ── 프로바이더 표의 "생각 제어" 열 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("프로바이더 표 — 켬/끔만 받는 서버: 필드 이름, AUTO 여부, 거부 기억이 함께 나온다")
    void providerRowCarriesTheThinkingControl() {
        SettingsView.ProviderThinking info = service.providerRows().get(0).thinking();

        assertThat(info.support()).isEqualTo(ThinkingDialect.Support.ON_OFF);
        assertThat(info.field()).isEqualTo("chat_template_kwargs.enable_thinking");
        assertThat(info.resolved()).isEqualTo(ThinkingDialect.TEMPLATE_KWARGS);
        assertThat(info.auto()).as("설정에 적지 않았으니 AUTO 가 풀린 값이다").isTrue();
        assertThat(info.hasRejected()).isFalse();
        assertThat(info.supportKey()).isEqualTo("settings.thinking.support.on-off");

        harness.dialects.markRejected("local", ThinkingDialect.TEMPLATE_KWARGS_FIELD);
        SettingsView.ProviderThinking rejected = service.providerRows().get(0).thinking();
        assertThat(rejected.hasRejected()).isTrue();
        assertThat(rejected.rejected()).containsExactly(ThinkingDialect.TEMPLATE_KWARGS_FIELD);
    }

    @Test
    @DisplayName("프로바이더 표 — 기록이 없는 서버(생각 제어 안 함)는 '안 함 — 서버가 정함'")
    void providerWithoutControl() {
        harness = ThinkingPreviewHarness.builder().dialect(ThinkingDialect.NONE).build();
        service.shutdown();
        service = newService();

        SettingsView.ProviderThinking info = service.providerRows().get(0).thinking();

        assertThat(info.support()).isEqualTo(ThinkingDialect.Support.NONE);
        assertThat(info.field()).isNull();
        assertThat(info.supportKey()).isEqualTo("settings.thinking.support.none");
    }
}
