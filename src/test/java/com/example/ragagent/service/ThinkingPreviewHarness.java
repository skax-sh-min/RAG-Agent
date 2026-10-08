package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.TokenEstimateCalibration;
import com.example.ragagent.model.ThinkingPreview;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ThinkingPreviewService} 를 <b>진짜 협력자</b>로 조립한다 — 예산·dialect·창·관측·메시지 번들은 실제 객체이고, 라우터와
 * 설정 저장소(SQLite)만 목이다. 목으로 바꾸면 "미리보기 = 런타임" 이 검증하려는 바로 그 함수들이 빠진다.
 *
 * <p>기본 구성은 참조 배포와 같다: 프로바이더 하나({@code local}, LOCAL, 우선순위 1)가 모든 작업을 받고, 창 16,384,
 * 전역 max-tokens 10,000, top-K 10, 청크 1,500자, 켬/끔만 받는 dialect({@code TEMPLATE_KWARGS}).
 */
public final class ThinkingPreviewHarness {

    public static final String PROVIDER = "local";

    public final AppProperties props;
    public final LlmRouter router = mock(LlmRouter.class);
    public final ProviderContextWindows windows = new ProviderContextWindows();
    public final ProviderThinkingDialects dialects = new ProviderThinkingDialects();
    public final ThinkingObservations observations = new ThinkingObservations();
    public final TokenEstimateCalibration calibration = new TokenEstimateCalibration();
    public final SettingsService settings = mock(SettingsService.class);
    public final ThinkingBudget budget;
    public final ThinkingPreviewService service;

    private ThinkingPreviewHarness(Builder b) {
        AppProperties.ProviderConfig cfg = new AppProperties.ProviderConfig(PROVIDER, "http://x/v1", "key", "model",
                "BOTH", "LOCAL", 1, true, null, null, b.providerMaxTokens);
        AppProperties.LlmConfig llm = new AppProperties.LlmConfig(
                List.of(cfg), 2, 10, b.readTimeoutSeconds, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true,
                b.maxTokens, 1, false, b.file);
        this.props = new AppProperties(
                "./data", 2, 1_500, 100, 100, 10, 0.0, true, 5, false,
                true, false, 3, null,
                llm, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        if (b.window > 0) windows.record(PROVIDER, b.window, ProviderContextWindows.Source.CONFIGURED);
        dialects.record(PROVIDER, b.dialect, true);
        this.budget = new ThinkingBudget(props, dialects, windows);

        when(router.getDefaultMode()).thenReturn(RoutingMode.COST_FIRST);
        // 모든 작업을 같은 프로바이더가 받는다 — 라우팅 규칙 자체는 LlmRouter 의 몫이고 여기서 확인하지 않는다.
        when(router.findProviderName(any(TaskType.class), any(RoutingMode.class))).thenReturn(b.receives ? PROVIDER : "unknown");
        when(router.findNominalProviderName(any(TaskType.class), any(RoutingMode.class)))
                .thenReturn(b.receives ? Optional.of(PROVIDER) : Optional.empty());
        when(settings.isOverridden(anyString())).thenReturn(false);

        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        this.service = new ThinkingPreviewService(props, router, budget, dialects, windows, observations, calibration,
                settings, messages);
    }

    public static Builder builder() {
        return new Builder();
    }

    public ThinkingPreview.Row row(ThinkingSite site) {
        return service.row(site);
    }

    public static final class Builder {
        int window = 16_384;
        int maxTokens = 10_000;
        /** 프로바이더 자신의 {@code max-tokens} — 없으면 전역값을 따른다. 전역값은 1,000 아래로 내려가지 않는다(clamp). */
        Integer providerMaxTokens = null;
        int readTimeoutSeconds = 600;
        ThinkingDialect dialect = ThinkingDialect.TEMPLATE_KWARGS;
        Map<String, String> file = Map.of();
        boolean receives = true;

        public Builder window(int window) {
            this.window = window;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder providerMaxTokens(int providerMaxTokens) {
            this.providerMaxTokens = providerMaxTokens;
            return this;
        }

        public Builder readTimeoutSeconds(int seconds) {
            this.readTimeoutSeconds = seconds;
            return this;
        }

        public Builder dialect(ThinkingDialect dialect) {
            this.dialect = dialect;
            return this;
        }

        /** {@code app.llm.thinking.<id>} 의 줄 — 키는 사이트 id. */
        public Builder file(Map<String, String> file) {
            this.file = file;
            return this;
        }

        /** 이 호출을 받을 프로바이더가 없다(라우터가 "unknown"). */
        public Builder nobodyReceives() {
            this.receives = false;
            return this;
        }

        public ThinkingPreviewHarness build() {
            return new ThinkingPreviewHarness(this);
        }
    }

    /** 수준을 지정하지 않은 사이트는 출하값이다. */
    public static Map<String, String> levels(ThinkingSite site, ThinkingLevel level) {
        return Map.of(site.id(), level.value());
    }
}
