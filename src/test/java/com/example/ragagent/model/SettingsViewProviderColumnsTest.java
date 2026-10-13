package com.example.ragagent.model;

import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingWire;
import com.example.ragagent.model.SettingsView.ProviderConnection;
import com.example.ragagent.model.SettingsView.ProviderRow;
import com.example.ragagent.model.SettingsView.ProviderThinking;
import com.example.ragagent.model.SettingsView.ThinkingControl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로바이더 표의 두 열 — "상태"(미설정 · 확인 중 · 접속불가 · 정상)와 "생각 제어"(사용자 설정 적용 · 서버 설정 사용 · Unknown).
 *
 * <p>"생각 제어" 열의 말은 <b>실제 전송과 같은 함수</b>에서 나와야 한다: 화면이 "적용"이라고 하는데 요청에는 아무것도 안 실리면(혹은 그
 * 반대면) 이 열은 거짓말을 한다. 그래서 {@link ProviderThinkingDialects#wireFor} 와 모든 dialect·수준·거부 조합에서 맞춰 본다.
 */
class SettingsViewProviderColumnsTest {

    private static ProviderThinking thinking(ThinkingDialect resolved, String... rejected) {
        return new ProviderThinking(ThinkingDialect.AUTO, resolved, resolved.support(), resolved.field(), Set.of(rejected));
    }

    private static ProviderRow row(boolean configured, ProviderThinking thinking, ProviderConnection connection) {
        return new ProviderRow("local", "LOCAL", 1, "model", "http://x/v1", configured, false, null, true, "-",
                thinking, connection);
    }

    // ── 생각 제어 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("필드를 싣는 dialect 는 '사용자 설정 적용' 이다")
    void appliedWhenTheDialectSendsAField() {
        for (ThinkingDialect dialect : List.of(ThinkingDialect.TEMPLATE_KWARGS, ThinkingDialect.TEMPLATE_KWARGS_EFFORT,
                ThinkingDialect.OPENAI_EFFORT)) {
            assertThat(thinking(dialect).control()).as(dialect.name()).isEqualTo(ThinkingControl.APPLIED);
        }
    }

    @Test
    @DisplayName("아무것도 싣지 않는 dialect(NONE — 지정하지 않은 원격 서버)는 '서버 설정 사용' 이다")
    void serverDecidesWhenNothingIsSent() {
        assertThat(thinking(ThinkingDialect.NONE).control()).isEqualTo(ThinkingControl.SERVER);
    }

    @Test
    @DisplayName("서버가 필드를 거부해 뺐으면 '서버 설정 사용' 이다 — 그 서버에 실리던 유일한 필드가 사라졌으므로")
    void serverDecidesOnceTheFieldWasRejected() {
        assertThat(thinking(ThinkingDialect.TEMPLATE_KWARGS, ThinkingDialect.TEMPLATE_KWARGS_FIELD).control())
                .isEqualTo(ThinkingControl.SERVER);
        assertThat(thinking(ThinkingDialect.TEMPLATE_KWARGS_EFFORT, ThinkingDialect.TEMPLATE_KWARGS_FIELD).control())
                .isEqualTo(ThinkingControl.SERVER);
        assertThat(thinking(ThinkingDialect.OPENAI_EFFORT, ThinkingWire.REASONING_EFFORT_FIELD).control())
                .isEqualTo(ThinkingControl.SERVER);
        // 다른 필드의 거부는 이 dialect 가 싣는 필드에 영향이 없다
        assertThat(thinking(ThinkingDialect.TEMPLATE_KWARGS, ThinkingWire.REASONING_EFFORT_FIELD).control())
                .isEqualTo(ThinkingControl.APPLIED);
    }

    @Test
    @DisplayName("화면의 판정은 실제 전송(ProviderThinkingDialects.wireFor)과 모든 dialect·수준·거부 조합에서 같다")
    void agreesWithWhatIsActuallySent() {
        List<ThinkingDialect> dialects = List.of(ThinkingDialect.NONE, ThinkingDialect.TEMPLATE_KWARGS,
                ThinkingDialect.TEMPLATE_KWARGS_EFFORT, ThinkingDialect.OPENAI_EFFORT);
        List<Set<String>> rejections = List.of(Set.of(), Set.of(ThinkingDialect.TEMPLATE_KWARGS_FIELD),
                Set.of(ThinkingWire.REASONING_EFFORT_FIELD),
                Set.of(ThinkingDialect.TEMPLATE_KWARGS_FIELD, ThinkingWire.REASONING_EFFORT_FIELD));

        for (ThinkingDialect dialect : dialects) {
            for (Set<String> rejected : rejections) {
                ProviderThinkingDialects registry = new ProviderThinkingDialects();
                registry.record("p", dialect, false);
                rejected.forEach(field -> registry.markRejected("p", field));

                ThinkingControl shown = thinking(dialect, rejected.toArray(String[]::new)).control();

                for (ThinkingLevel level : ThinkingLevel.values()) {
                    boolean sent = registry.wireFor("p", level).sent() != ThinkingWire.Sent.NOTHING;
                    assertThat(shown).as("%s rejected=%s level=%s", dialect, rejected, level)
                            .isEqualTo(sent ? ThinkingControl.APPLIED : ThinkingControl.SERVER);
                }
            }
        }
    }

    @Test
    @DisplayName("등록되지 않아 생각 제어 정보가 없는 프로바이더는 Unknown 이다")
    void unknownWhenThereIsNoInformation() {
        assertThat(row(false, null, null).thinkingControl()).isEqualTo(ThinkingControl.UNKNOWN);
        assertThat(row(true, thinking(ThinkingDialect.TEMPLATE_KWARGS), null).thinkingControl())
                .isEqualTo(ThinkingControl.APPLIED);
    }

    // ── 상태 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("접속 확인 없이 만든 행은 설정이 갖춰졌으면 '확인 중', 아니면 '미설정' 이다")
    void connectionDefaultsFollowTheConfiguration() {
        assertThat(row(true, null, null).connection().state()).isEqualTo(ProviderConnection.State.CHECKING);
        assertThat(row(true, null, null).connection().isChecking()).isTrue();
        assertThat(row(false, null, null).connection().state()).isEqualTo(ProviderConnection.State.NOT_CONFIGURED);
        assertThat(row(false, null, null).connection().isChecking()).isFalse();
    }

    @Test
    @DisplayName("접속 확인 열이 생기기 전의 생성자(10·11 인자)도 같은 기본값을 쓴다")
    void legacyConstructorsKeepWorking() {
        ProviderRow ten = new ProviderRow("a", "LOCAL", 1, "m", "http://x/v1", true, false, null, true, "-");
        ProviderRow eleven = new ProviderRow("a", "LOCAL", 1, "m", "http://x/v1", false, false, null, true, "-", null);

        assertThat(ten.connection().state()).isEqualTo(ProviderConnection.State.CHECKING);
        assertThat(eleven.connection().state()).isEqualTo(ProviderConnection.State.NOT_CONFIGURED);
    }

    @Test
    @DisplayName("명시한 접속 결과는 그대로 보존된다 — 정상은 응답 시간과 모델 목록 여부, 접속불가는 사유")
    void explicitConnectionIsKept() {
        ProviderConnection ok = ProviderConnection.reachable(12L, false);
        ProviderConnection down = ProviderConnection.unreachable("models: ConnectException");

        assertThat(row(true, null, ok).connection()).isSameAs(ok);
        assertThat(ok.state()).isEqualTo(ProviderConnection.State.OK);
        assertThat(ok.latencyMs()).isEqualTo(12L);
        assertThat(ok.isModelMissing()).as("목록에 없음 — '정상' 칸의 설명에만 쓴다").isTrue();
        assertThat(ProviderConnection.reachable(12L, null).isModelMissing()).as("목록을 못 읽었다 = 모른다").isFalse();
        assertThat(down.state()).isEqualTo(ProviderConnection.State.UNREACHABLE);
        assertThat(down.error()).isEqualTo("models: ConnectException");
    }

    @Test
    @DisplayName("그룹의 note 는 생략할 수 있다 — 3-인자 생성자는 note 없음")
    void groupNoteIsOptional() {
        assertThat(new SettingsView.SettingGroup("g", "t", List.of()).note()).isNull();
        assertThat(new SettingsView.SettingGroup("g", "t", List.of(), "settings.note.restart").note())
                .isEqualTo("settings.note.restart");
    }
}
