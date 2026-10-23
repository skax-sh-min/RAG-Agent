package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.ProviderConnectivity;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.model.SettingsView.ProviderEdit;
import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.repository.ProviderOverrideRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /settings} 의 프로바이더 편집 — 저장·검증·즉시 반영(컨텍스트)·재기동 대기.
 *
 * <p>{@code init()} 이 {@code AppProperties} 의 정적 오버레이를 묶으므로 다른 테스트와 함께 돌지 않게 잠그고 풀어 둔다.
 */
@ResourceLock("global-state")
class ProviderCatalogTest {

    @TempDir Path tmp;

    private ProviderOverrideRepository repo;
    private final ProviderContextWindows windows = new ProviderContextWindows();
    private final List<AppProperties.ProviderConfig> checked = new ArrayList<>();
    private ProviderConnectivity.Result answer = reachable(true);
    private Optional<Integer> probed = Optional.empty();

    private static ProviderConnectivity.Result reachable(Boolean listed) {
        return new ProviderConnectivity.Result(true, 5L, listed, null, Instant.now());
    }

    private static final ProviderConnectivity.Result UNREACHABLE =
            new ProviderConnectivity.Result(false, null, null, "connection refused", Instant.now());

    @BeforeEach
    void open() {
        repo = new ProviderOverrideRepository(SqliteTestDatabase.open(tmp.resolve("t.db")));
    }

    @AfterEach
    void unbind() {
        AppProperties.unbindProviderOverlay();
    }

    private ProviderCatalog catalog(AppProperties.ProviderConfig... base) {
        return catalog("COST_FIRST", base);
    }

    private ProviderCatalog catalog(String routingMode, AppProperties.ProviderConfig... base) {
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(base), 2, 2, 2, routingMode, 3, 20, 0.0, 0.1, 0.0, 0.7, true, 12_000, 1, false));
        ProviderCatalog c = new ProviderCatalog(repo, props, windows, mock(AuditLogger.class),
                cfg -> { checked.add(cfg); return answer; }, cfg -> probed);
        c.init();
        return c;
    }

    private static AppProperties.ProviderConfig local(String name, String url, String model, Integer ctx) {
        return new AppProperties.ProviderConfig(name, url, "", model, "BOTH", "LOCAL", 1, true, null, ctx, null);
    }

    private static AppProperties.ProviderConfig remote(String name, String url) {
        return new AppProperties.ProviderConfig(name, url, "secret", "gpt", "TEXT", "NORMAL", 3, true, null, null, null);
    }

    private static ProviderCatalog.Edit edit(String name, String url, String model, String type, String role,
                                             int priority, Integer ctx) {
        return new ProviderCatalog.Edit(name, url, model, type, role, priority, ctx, false);
    }

    @Test
    @DisplayName("모델을 바꾸면 그 칸만 저장되고 재기동 대기로 보인다 — 저장 전 새 값으로 서버의 모델 목록을 확인한다")
    void savingAModelChangeIsPendingUntilRestart() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "old", null));

        ProviderCatalog.SaveResult r = c.save(edit("local", "http://a:1/v1", "new", "BOTH", "LOCAL", 1, null));

        assertThat(repo.findAll().get("local").model()).isEqualTo("new");
        assertThat(repo.findAll().get("local").baseUrl()).as("properties 와 같은 칸은 비워 둔다").isNull();
        assertThat(checked).singleElement().satisfies(cfg -> assertThat(cfg.model()).isEqualTo("new"));
        assertThat(r.notes()).containsExactly(ProviderCatalog.NOTE_SAVED, ProviderCatalog.NOTE_RESTART);
        ProviderEdit e = c.editOf("local");
        assertThat(e.origin()).isEqualTo(ProviderEdit.Origin.EDITED);
        assertThat(e.pending()).isEqualTo(ProviderEdit.Pending.CHANGE);
        assertThat(e.pendingFields()).containsExactly(ProviderCatalog.F_MODEL);
    }

    @Test
    @DisplayName("properties 와 똑같이 저장하면 행이 남지 않는다 — 되돌린 것과 같다")
    void savingTheSameValuesLeavesNoRow() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "m", null));

        ProviderCatalog.SaveResult r = c.save(edit("local", "http://a:1/v1", "m", "BOTH", "LOCAL", 1, null));

        assertThat(repo.findAll()).isEmpty();
        assertThat(r.notes()).containsExactly(ProviderCatalog.NOTE_NOTHING);
        assertThat(c.editOf("local")).isEqualTo(ProviderEdit.untouched());
    }

    @Test
    @DisplayName("탐지가 안 될 때 넣은 컨텍스트는 저장 즉시 반영된다(수동 입력) — 되돌리면 다시 탐지하고, 못 구하면 '모름'이다")
    void contextAppliesImmediatelyWhenDetectionFailed() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "m", null));   // 창 모름 = 탐지가 안 됐다

        ProviderCatalog.SaveResult r = c.save(edit("local", "http://a:1/v1", "m", "BOTH", "LOCAL", 1, 32_768));

        assertThat(r.contextChanged()).isTrue();
        assertThat(r.notes()).containsExactly(ProviderCatalog.NOTE_SAVED, ProviderCatalog.NOTE_CONTEXT);
        assertThat(windows.find("local")).hasValueSatisfying(w -> {
            assertThat(w.tokens()).isEqualTo(32_768);
            assertThat(w.source()).isEqualTo(ProviderContextWindows.Source.MANUAL);
            assertThat(w.source().declared()).as("탐지가 성공하면 덮을 수 있다").isFalse();
        });
        assertThat(checked).as("재기동 칸이 그대로면 서버를 묻지 않는다").isEmpty();
        assertThat(c.editOf("local").pending()).isEqualTo(ProviderEdit.Pending.NONE);

        probed = Optional.of(20_480);
        c.revert("local");
        assertThat(windows.find("local")).hasValueSatisfying(w ->
                assertThat(w.source()).isEqualTo(ProviderContextWindows.Source.PROBED));
        assertThat(repo.findAll()).isEmpty();

        ProviderCatalog unknown = catalog(local("other", "http://b:1/v1", "m", null));
        unknown.save(edit("other", "http://b:1/v1", "m", "BOTH", "LOCAL", 1, 32_768));
        probed = Optional.empty();
        unknown.save(edit("other", "http://b:1/v1", "m", "BOTH", "LOCAL", 1, null));
        assertThat(windows.find("other")).isEmpty();
    }

    @Test
    @DisplayName("탐지값이 있거나 선언된 프로바이더에는 수동 컨텍스트를 넣을 수 없다 — 잠긴 칸(빈 값)은 저장된 대체값을 그대로 둔다")
    void manualContextOnlyWhenDetectionFails() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "m", null), local("decl", "http://d:1/v1", "m", 16_384));
        c.save(edit("local", "http://a:1/v1", "m", "BOTH", "LOCAL", 1, 24_576));   // 탐지가 안 될 때 넣었다
        windows.record("local", 32_768, ProviderContextWindows.Source.PROBED);       // 그 뒤 재탐지가 성공해 덮었다
        windows.record("decl", 16_384, ProviderContextWindows.Source.CONFIGURED);

        assertThat(c.contextLock("local")).contains(ProviderCatalog.LOCK_PROBED);
        assertThat(c.contextLock("decl")).contains(ProviderCatalog.LOCK_DECLARED);
        assertThatThrownBy(() -> c.save(edit("local", "http://a:1/v1", "m", "BOTH", "LOCAL", 1, 40_000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("자동 탐지");
        assertThatThrownBy(() -> c.save(edit("decl", "http://d:1/v1", "m", "BOTH", "LOCAL", 1, 65_536)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("context-size");

        c.save(edit("local", "http://a:1/v1", "m", "BOTH", "LOCAL", 2, null));   // 잠긴 칸 = 그대로

        assertThat(repo.findAll().get("local").contextSize()).as("탐지 실패 때 쓸 대체값은 남는다").isEqualTo(24_576);
        assertThat(windows.find("local")).hasValueSatisfying(w ->
                assertThat(w.source()).isEqualTo(ProviderContextWindows.Source.PROBED));
    }

    @Test
    @DisplayName("서버가 답했는데 모델이 없으면 저장하지 않는다 — 다음 기동의 모델 검증에 실패할 값이다")
    void refusesAModelTheServerDoesNotList() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "old", null));
        answer = reachable(false);

        assertThatThrownBy(() -> c.save(edit("local", "http://a:1/v1", "typo", "BOTH", "LOCAL", 1, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("typo");
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("서버가 닿지 않으면 저장하되 확인하지 못했다고 알린다")
    void savesButWarnsWhenTheServerIsUnreachable() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "old", null));
        answer = UNREACHABLE;

        ProviderCatalog.SaveResult r = c.save(edit("local", "http://a:9/v1", "old", "BOTH", "LOCAL", 1, null));

        assertThat(r.notes()).contains(ProviderCatalog.NOTE_RESTART, ProviderCatalog.NOTE_UNVERIFIED);
        assertThat(repo.findAll().get("local").baseUrl()).isEqualTo("http://a:9/v1");
    }

    @Test
    @DisplayName("키가 있는 프로바이더는 호스트를 바꿀 수 없다(키가 새 호스트로 나간다) — 같은 호스트의 경로는 된다")
    void keyedProviderCannotMoveToAnotherHost() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "m", null), remote("gpt", "https://api.x.com/v1"));

        assertThatThrownBy(() -> c.save(edit("gpt", "https://evil.example/v1", "gpt", "TEXT", "NORMAL", 3, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("호스트");
        assertThatThrownBy(() -> c.save(edit("gpt", "https://api.x.com:8443/v1", "gpt", "TEXT", "NORMAL", 3, null)))
                .as("포트도 키가 나가는 곳이다").isInstanceOf(IllegalArgumentException.class);

        c.save(edit("gpt", "https://api.x.com/v2", "gpt", "TEXT", "NORMAL", 3, null));
        assertThat(repo.findAll().get("gpt").baseUrl()).isEqualTo("https://api.x.com/v2");
    }

    @Test
    @DisplayName("LOCAL 슬롯의 기본값 no-key 는 키가 아니다 — 로컬 서버 주소는 다른 호스트로 옮길 수 있다")
    void theNoKeyPlaceholderIsNotAKey() {
        ProviderCatalog c = catalog(new AppProperties.ProviderConfig("local", "http://a:1/v1", "no-key", "m", "BOTH", "LOCAL",
                1, true, null, null, null));

        assertThat(c.hasApiKey("local")).isFalse();
        c.save(edit("local", "http://b:2/v1", "m", "BOTH", "LOCAL", 1, null));
        assertThat(repo.findAll().get("local").baseUrl()).isEqualTo("http://b:2/v1");
    }

    @Test
    @DisplayName("새 프로바이더는 LOCAL 만, 이름 규칙과 중복을 지킨다 — 저장하면 재기동 뒤 추가될 행이 된다")
    void addingAProvider() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "m", null));

        assertThatThrownBy(() -> c.save(new ProviderCatalog.Edit("cloud", "https://c/v1", "m", "TEXT", "NORMAL", 2, null, true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOCAL");
        assertThatThrownBy(() -> c.save(new ProviderCatalog.Edit("local", "http://b/v1", "m", "BOTH", "LOCAL", 2, null, true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이미 있는");
        assertThatThrownBy(() -> c.save(new ProviderCatalog.Edit("bad name", "http://b/v1", "m", "BOTH", "LOCAL", 2, null, true)))
                .isInstanceOf(IllegalArgumentException.class);

        c.save(new ProviderCatalog.Edit("local-3", "http://b:1/v1", "m3", "TEXT", "LOCAL", 2, 32_768, true));

        assertThat(c.pendingAdditions()).singleElement()
                .satisfies(cfg -> assertThat(cfg.name()).isEqualTo("local-3"));
        ProviderEdit e = c.editOf("local-3");
        assertThat(e.origin()).isEqualTo(ProviderEdit.Origin.ADDED);
        assertThat(e.pending()).isEqualTo(ProviderEdit.Pending.ADD);
        assertThat(windows.find("local-3")).as("아직 돌지 않는 프로바이더의 창은 기록하지 않는다").isEmpty();
    }

    @Test
    @DisplayName("값 검증 — 주소 형식, type·role 목록, 우선순위·컨텍스트 범위")
    void validatesValues() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "m", null));

        assertThatThrownBy(() -> c.save(edit("local", "ftp://a/v1", "m", "BOTH", "LOCAL", 1, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.save(edit("local", "http://user:pw@a/v1", "m", "BOTH", "LOCAL", 1, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.save(edit("local", "http://a:1/v1", "m", "HUGE", "LOCAL", 1, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.save(edit("local", "http://a:1/v1", "m", "BOTH", "ROOT", 1, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.save(edit("local", "http://a:1/v1", "m", "BOTH", "LOCAL", 100, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.save(edit("local", "http://a:1/v1", "m", "BOTH", "LOCAL", 1, 512)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.save(edit("ghost", "http://a:1/v1", "m", "BOTH", "LOCAL", 1, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("알 수 없는");
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    @DisplayName("삭제 — properties 정의는 재기동 뒤 숨김, 아직 돌지 않는 추가분은 바로 지움, 마지막 하나는 지울 수 없다")
    void deleting() {
        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "m", null), remote("gpt", "https://api.x.com/v1"));
        c.save(new ProviderCatalog.Edit("local-3", "http://b:1/v1", "m3", "TEXT", "LOCAL", 2, null, true));

        c.delete("local-3");
        assertThat(repo.findAll()).doesNotContainKey("local-3");

        c.delete("gpt");
        assertThat(c.editOf("gpt").pending()).isEqualTo(ProviderEdit.Pending.DELETE);

        assertThatThrownBy(() -> c.delete("local"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("하나도 남지 않습니다");

        c.revert("gpt");
        assertThat(c.editOf("gpt")).isEqualTo(ProviderEdit.untouched());
    }

    @Test
    @DisplayName("LOCAL_ONLY 배포에서는 LOCAL 이 하나는 남아야 한다")
    void localOnlyKeepsALocalProvider() {
        ProviderCatalog c = catalog("LOCAL_ONLY", local("local", "http://a:1/v1", "m", null), remote("gpt", "https://api.x.com/v1"));

        assertThatThrownBy(() -> c.delete("local"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOCAL");
    }

    @Test
    @DisplayName("기동 검증에 실패하면 properties 정의로 돌고(수동 컨텍스트는 남는다), 저장된 편집은 지우지 않고 사유를 보인다")
    void startupFallbackKeepsTheStoredEdit() {
        ProviderCatalog first = catalog(local("local", "http://a:1/v1", "m", null));
        first.save(edit("local", "http://a:1/v1", "typo", "BOTH", "LOCAL", 1, 32_768));
        AppProperties.unbindProviderOverlay();

        ProviderCatalog c = catalog(local("local", "http://a:1/v1", "m", null));   // 재기동
        assertThat(c.editedLocalProviders()).extracting(AppProperties.ProviderConfig::model).containsExactly("typo");
        assertThat(c.hasManualContext("local")).isTrue();

        c.fallBackAtStartup("local", "model typo not found");

        assertThat(c.editedLocalProviders()).isEmpty();
        assertThat(c.hasManualContext("local")).as("컨텍스트는 실패와 무관하다").isTrue();
        ProviderEdit e = c.editOf("local");
        assertThat(e.startupFailure()).contains("typo");
        assertThat(e.pending()).as("다음 기동에 다시 시도된다").isEqualTo(ProviderEdit.Pending.CHANGE);
        assertThat(repo.findAll().get("local").model()).isEqualTo("typo");
    }
}
