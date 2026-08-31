package com.example.ragagent.model;

import java.util.List;

/**
 * Backend-agnostic model for the {@code /settings} page. Built by
 * {@code SettingsService.buildView()} from the effective {@code AppProperties} values (overrides
 * already applied), the registered LLM providers, and the circuit-breaker state.
 */
public record SettingsView(
        List<ProviderRow> providers,
        String defaultRoutingMode,
        String temperature,
        String maxTokens,
        String embeddingModel,
        String embeddingBaseUrl,
        String embeddingDimensions,
        String vectorStoreType,
        List<SettingGroup> groups
) {

    /**
     * One registered chat/vision LLM provider. API keys are never included.
     *
     * @param baseUrl    the configured endpoint address (not a secret — safe to display)
     * @param configured true when this provider passes {@code AppProperties.ProviderConfig#isEnabled()}
     *                   (LlmConfig's actual G1+G2 registration gate) — false means it was never wired
     *                   up as a live {@code LlmProvider} regardless of what its api-key/toggle look like
     * @param enabled    false when an operator has disabled this provider at runtime (§A) — routing skips
     *                   it until re-enabled or the app restarts (the toggle is in-memory/volatile)
     */
    public record ProviderRow(
            String name,
            String role,
            int priority,
            String model,
            String baseUrl,
            boolean configured,
            boolean blocked,
            String blockedUntil,
            boolean enabled
    ) {}

    /** A titled group of settings on the page (e.g. "검색 튜닝 (핫 수정)"). */
    public record SettingGroup(String id, String title, List<SettingItem> items) {}

    /**
     * A single setting row.
     *
     * @param key        override key ({@link com.example.ragagent.config.SettingsKeys}) when editable, else null
     * @param label      human-readable label
     * @param value      effective value as a string (override applied)
     * @param type       input type hint: {@code "number"} | {@code "bool"} | {@code "text"}
     * @param editable   true → hot-editable (renders an input); false → read-only
     * @param overridden true → a persisted override is currently active for this key
     * @param note       restart-required reason or other note (nullable)
     * @param min        numeric lower bound (nullable / number type only)
     * @param max        numeric upper bound (nullable / number type only)
     * @param step       numeric input step (nullable / number type only)
     * @param tooltip    i18n key for a hover explanation (nullable) — 값 열이 좁아 한 줄로는
     *                   담을 수 없는 계산 근거·적용 범위를 여기에 둔다
     */
    public record SettingItem(
            String key,
            String label,
            String value,
            String type,
            boolean editable,
            boolean overridden,
            String note,
            Double min,
            Double max,
            Double step,
            String tooltip
    ) {
        /**
         * 툴팁 없는 행(대다수)을 위한 편의 생성자 — {@code SourceRef} 의 5/8/10-인자 생성자와 같은
         * 선례다. 툴팁은 값 열에 담을 수 없는 긴 설명이 있을 때만 붙으므로, 그 필드를 추가하면서
         * 기존 호출부 전부에 {@code null} 을 흩뿌리는 대신 여기서 한 번 채운다.
         */
        public SettingItem(String key, String label, String value, String type,
                           boolean editable, boolean overridden, String note,
                           Double min, Double max, Double step) {
            this(key, label, value, type, editable, overridden, note, min, max, step, null);
        }

        /**
         * 허용 범위 툴팁이 쓰는 경계값 — {@code 1.0} 이 아니라 {@code 1} 로 보이게 다듬는다.
         *
         * <p>레코드 컴포넌트가 아니라 <b>일반 메서드</b>다({@code SourceRef.staleBadge()} 선례):
         * 저장·전송되는 값이 아니라 {@code min}/{@code max}/{@code step} 에서 그때그때 파생되는
         * 표시 형태라, 생성자에 실어 나르면 호출부마다 같은 계산을 복제하게 된다. 템플릿이 SpEL
         * ({@code ${item.minLabel}}) 로 읽으므로 실제 렌더로 접근 가능 여부를
         * {@code SettingsControllerRenderTest} 가, 다듬는 규칙 자체를 {@code SettingsRangeLabelTest} 가
         * 고정한다 — 이름이나 형식이 어긋나도 화면에는 예외가 아니라 <b>어긋난 툴팁</b>으로만
         * 드러난다({@code 1.0 ~ 1000.0} 처럼).
         *
         * <p>{@code SettingsService.trimNum()} 과 같은 모양이지만 합치지 않는다: 저쪽은 저장·검증에
         * 쓰이는 <b>값</b>의 정규화(원시 double)이고 이쪽은 <b>경계</b>의 표시(nullable Double,
         * 읽기 전용 행에서는 셋 다 null)다. 둘이 갈라져도 결과는 툴팁에 {@code 1.0} 이 보이는
         * 정도이지 저장되는 값이 달라지지 않는다.
         */
        public String minLabel() { return trimBound(min); }

        /** @see #minLabel() */
        public String maxLabel() { return trimBound(max); }

        /** @see #minLabel() */
        public String stepLabel() { return trimBound(step); }

        private static String trimBound(Double d) {
            if (d == null) return "";
            if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) (double) d);
            return Double.toString(d);
        }
    }
}
