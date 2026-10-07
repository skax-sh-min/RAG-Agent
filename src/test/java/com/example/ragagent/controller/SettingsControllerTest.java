package com.example.ragagent.controller;

import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.SettingsView;
import com.example.ragagent.model.SettingsView.SettingItem;
import com.example.ragagent.model.ThinkingPreview;
import com.example.ragagent.service.SettingsService;
import com.example.ragagent.service.ThinkingPreviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SettingsController contract: view names, model wiring, and delegation to
 * {@link SettingsService}. HTTP status codes for validation errors are produced by
 * {@code GlobalExceptionHandler} (IllegalArgumentException → 400); the {@code /admin/settings/**}
 * write endpoints inherit ROLE_ADMIN gating from the existing {@code /admin/**} rule in
 * SecurityConfig, so authorization is not re-tested here.
 */
class SettingsControllerTest {

    private final SettingsService service = mock(SettingsService.class);
    private final ThinkingPreviewService thinking = mock(ThinkingPreviewService.class);
    private final SettingsController controller = new SettingsController(service, thinking);

    private static SettingItem sampleItem() {
        return new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "80",
                "number", true, true, null, 1.0, 1000.0, 1.0);
    }

    @Test
    @DisplayName("GET /settings — settings 뷰 + settings 모델 속성")
    void settingsPage_returnsViewAndModel() {
        SettingsView view = new SettingsView(List.of(), "COST_FIRST", "0.0", "6000",
                "bge-m3", "http://localhost:1234/v1", "1024", "chroma", List.of());
        when(service.buildView()).thenReturn(view);

        Model model = new ExtendedModelMap();
        String name = controller.settingsPage(model);

        assertThat(name).isEqualTo("settings");
        assertThat(model.getAttribute("settings")).isSameAs(view);
    }

    @Test
    @DisplayName("POST /admin/settings/update — 서비스 위임 + item 프래그먼트 반환")
    void update_delegatesAndReturnsFragment() {
        when(service.editableItem(SettingsKeys.SEARCH_RRF_K)).thenReturn(sampleItem());

        Model model = new ExtendedModelMap();
        String name = controller.update(SettingsKeys.SEARCH_RRF_K, "80", model);

        verify(service).update(SettingsKeys.SEARCH_RRF_K, "80");
        assertThat(name).isEqualTo("fragments/settings-item :: item");
        assertThat(model.getAttribute("item")).isInstanceOf(SettingItem.class);
    }

    @Test
    @DisplayName("POST /admin/settings/reset — 서비스 위임 + item 프래그먼트 반환")
    void reset_delegatesAndReturnsFragment() {
        when(service.editableItem(SettingsKeys.SEARCH_RRF_K)).thenReturn(sampleItem());

        Model model = new ExtendedModelMap();
        String name = controller.reset(SettingsKeys.SEARCH_RRF_K, model);

        verify(service).reset(SettingsKeys.SEARCH_RRF_K);
        assertThat(name).isEqualTo("fragments/settings-item :: item");
    }

    @Test
    @DisplayName("POST /admin/settings/update — 생각 수준 키는 그 사이트의 행을 새로 계산해 돌려준다(값 칸만 갈아끼우면 낡은 숫자가 남는다)")
    void update_thinkingKey_returnsRecalculatedRow() {
        ThinkingPreview.Row row = mock(ThinkingPreview.Row.class);
        when(thinking.row(ThinkingSite.EVAL)).thenReturn(row);

        Model model = new ExtendedModelMap();
        String name = controller.update(ThinkingSite.EVAL.settingsKey(), "high", model);

        verify(service).update(ThinkingSite.EVAL.settingsKey(), "high");
        assertThat(name).isEqualTo("fragments/settings-thinking :: row");
        assertThat(model.getAttribute("row")).isSameAs(row);
        assertThat(model.getAttribute("item")).isNull();
    }

    @Test
    @DisplayName("POST /admin/settings/reset — 생각 수준 키도 행 조각을 돌려준다")
    void reset_thinkingKey_returnsRecalculatedRow() {
        ThinkingPreview.Row row = mock(ThinkingPreview.Row.class);
        when(thinking.row(ThinkingSite.CONDENSE)).thenReturn(row);

        Model model = new ExtendedModelMap();
        String name = controller.reset(ThinkingSite.CONDENSE.settingsKey(), model);

        verify(service).reset(ThinkingSite.CONDENSE.settingsKey());
        assertThat(name).isEqualTo("fragments/settings-thinking :: row");
        assertThat(model.getAttribute("row")).isSameAs(row);
    }

    @Test
    @DisplayName("GET /admin/settings/thinking — 미리보기를 계산해 카드 조각을 돌려준다")
    void thinkingCard_returnsCardFragment() {
        ThinkingPreview preview = new ThinkingPreview(java.time.Instant.now(), null, List.of());
        when(thinking.preview()).thenReturn(preview);

        Model model = new ExtendedModelMap();
        String name = controller.thinkingCard(model);

        assertThat(name).isEqualTo("fragments/settings-thinking :: card");
        assertThat(model.getAttribute("thinking")).isSameAs(preview);
    }

    @Test
    @DisplayName("POST /admin/settings/update — 검증 예외는 그대로 전파(→ 400)")
    void update_invalid_propagatesException() {
        when(service.update(SettingsKeys.SEARCH_RRF_K, "0"))
                .thenThrow(new IllegalArgumentException("out of range"));

        assertThatThrownBy(() -> controller.update(SettingsKeys.SEARCH_RRF_K, "0", new ExtendedModelMap()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("POST /admin/settings/provider/toggle — 서비스 위임 + providers 프래그먼트 반환")
    void toggleProvider_delegatesAndReturnsFragment() {
        when(service.setProviderEnabled("local", false)).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        String name = controller.toggleProvider("local", false, model);

        verify(service).setProviderEnabled("local", false);
        assertThat(name).isEqualTo("fragments/settings-providers :: providers");
        assertThat(model.getAttribute("providers")).isNotNull();
    }
}
