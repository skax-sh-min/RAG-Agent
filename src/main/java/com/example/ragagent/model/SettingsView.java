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
     * @param contextWindow 이 프로바이더의 컨텍스트 창 표시 문자열 — {@code "8,192 (탐지됨)"} /
     *                   {@code "16,384 (설정됨)"} / {@code "-"}(모름). <b>{@code "-"} 가 정보다</b>:
     *                   선언도 탐지도 없다는 뜻이고, 그 상태에서는 입력 예산을 짤 근거가 없어
     *                   {@code max-tokens} 보정도 돌지 않는다. 기동 로그의 {@code ctx=?} 와 같은 값이다
     * @param thinking   "생각 제어" 열의 재료 — 등록되지 않은 프로바이더는 {@code null}(= {@link ThinkingControl#UNKNOWN})
     * @param connection "상태" 열 — 설정(미설정)과 서버에 실제로 물어본 결과(접속불가 · 정상). {@code null} 이면 설정이 갖춰졌느냐로 정한다
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
            boolean enabled,
            String contextWindow,
            ProviderThinking thinking,
            ProviderConnection connection
    ) {
        /** 접속 확인 없이 만드는 호출부(테스트 포함)는 설정이 갖춰졌으면 "확인 중", 아니면 "미설정" 이다. */
        public ProviderRow {
            if (connection == null) {
                connection = configured ? ProviderConnection.pending() : ProviderConnection.notConfigured();
            }
        }

        /** 접속 확인 열이 생기기 전부터 있던 호출부(테스트 포함)를 위한 편의 생성자. */
        public ProviderRow(String name, String role, int priority, String model, String baseUrl,
                           boolean configured, boolean blocked, String blockedUntil, boolean enabled,
                           String contextWindow, ProviderThinking thinking) {
            this(name, role, priority, model, baseUrl, configured, blocked, blockedUntil, enabled, contextWindow,
                    thinking, null);
        }

        /** 생각 제어 열 없이 만드는 형태 — 이 열이 생기기 전부터 있던 호출부(테스트 포함)를 위한 편의 생성자. */
        public ProviderRow(String name, String role, int priority, String model, String baseUrl,
                           boolean configured, boolean blocked, String blockedUntil, boolean enabled,
                           String contextWindow) {
            this(name, role, priority, model, baseUrl, configured, blocked, blockedUntil, enabled, contextWindow, null, null);
        }

        /** "생각 제어" 열의 3단계 — 호출 지점별 생각 수준 설정이 이 프로바이더로 가는 요청에 실리는가. */
        public ThinkingControl thinkingControl() {
            return thinking == null ? ThinkingControl.UNKNOWN : thinking.control();
        }
    }

    /**
     * 프로바이더 표의 "상태" 열 — 설정(미설정)과 서버에 실제로 물어본 결과(접속불가 · 정상)를 합친 3단계에, 아직 묻지 않았거나
     * 묻는 중인 "확인 중" 이 하나 더 있다. 화면을 열 때는 늘 "확인 중" 으로 시작하고, 확인이 끝나면 칸만 바꿔 끼운다
     * ({@code GET /settings/llm-status}) — 죽은 서버의 연결 타임아웃이 페이지 전체를 붙잡지 않게.
     *
     * @param latencyMs   모델 목록 API 가 응답하기까지 걸린 시간 — 정상일 때 설명에 쓴다
     * @param modelListed 설정한 모델이 서버의 목록에 있는가({@code null} = 목록을 읽지 못했다). 3단계를 바꾸지 않고 설명에만 쓴다
     * @param error       접속하지 못한 사유 한 줄 — 접속불가일 때 설명에 쓴다
     */
    public record ProviderConnection(State state, Long latencyMs, Boolean modelListed, String error) {

        public enum State {
            /** 주소나 키가 없어 등록되지 않았다 — 물어볼 곳이 없다. */
            NOT_CONFIGURED,
            /** 아직 묻지 않았거나 묻는 중이다. */
            CHECKING,
            /** 모델 목록 API 가 응답하지 않았다(연결 거부 · 타임아웃 · HTTP 오류). */
            UNREACHABLE,
            /** 모델 목록 API 가 응답했다. */
            OK
        }

        private static final ProviderConnection NOT_CONFIGURED_CONNECTION = new ProviderConnection(State.NOT_CONFIGURED, null, null, null);
        private static final ProviderConnection PENDING_CONNECTION = new ProviderConnection(State.CHECKING, null, null, null);

        public static ProviderConnection notConfigured() {
            return NOT_CONFIGURED_CONNECTION;
        }

        public static ProviderConnection pending() {
            return PENDING_CONNECTION;
        }

        public static ProviderConnection reachable(Long latencyMs, Boolean modelListed) {
            return new ProviderConnection(State.OK, latencyMs, modelListed, null);
        }

        public static ProviderConnection unreachable(String error) {
            return new ProviderConnection(State.UNREACHABLE, null, null, error);
        }

        public boolean isChecking() {
            return state == State.CHECKING;
        }

        /** 서버는 응답했는데 설정한 모델이 목록에 없다 — "정상" 칸의 설명에 덧붙는다. */
        public boolean isModelMissing() {
            return Boolean.FALSE.equals(modelListed);
        }
    }

    /**
     * "생각 제어" 열의 3단계 — 호출 지점별 생각 수준({@code app.llm.thinking.*})이 이 프로바이더로 가는 요청에 <b>실리는가</b>.
     * 서버가 그 필드를 알아듣는지는 이 앱이 알 수 없다(알아듣는 서버에 실었다는 것까지가 이 열의 말이다).
     */
    public enum ThinkingControl {
        /** 요청마다 생각 제어 필드를 실어 호출 지점별 설정을 서버에 알린다. */
        APPLIED,
        /** 아무것도 싣지 않는다(지정하지 않은 원격 서버이거나, 서버가 필드를 거부해 뺐다) — 생각 여부는 서버의 자체 설정이 정한다. */
        SERVER,
        /** 알 수 없다 — 등록되지 않은 프로바이더라 요청 자체가 가지 않는다. */
        UNKNOWN
    }

    /**
     * 한 프로바이더가 생각 수준을 어떻게 받는가(PLAN §6.29 ⑦-나) — 관리자에게만 보이는 "생각 제어" 열의 재료다.
     *
     * @param configured 설정에 적힌 값. {@code AUTO} 면 화면이 "자동(AUTO)" 이라고 함께 적는다 — 어느 쪽을 골랐는지가 곧 운영자의 의도다
     * @param resolved   실제로 쓰는 값({@code AUTO} 가 풀린 것)
     * @param support    그 서버가 구분해서 알아듣는 수준의 폭
     * @param field      싣는 본문 필드 — 싣지 않으면 {@code null}
     * @param rejected   서버가 거부해 이 프로세스가 다시는 싣지 않기로 한 필드 — 재시작하면 초기화된다
     */
    public record ProviderThinking(com.example.ragagent.llm.ThinkingDialect configured,
                                   com.example.ragagent.llm.ThinkingDialect resolved,
                                   com.example.ragagent.llm.ThinkingDialect.Support support,
                                   String field, java.util.Set<String> rejected) {

        public boolean auto() {
            return configured == com.example.ragagent.llm.ThinkingDialect.AUTO;
        }

        public boolean hasRejected() {
            return rejected != null && !rejected.isEmpty();
        }

        /**
         * 실제 전송과 같은 함수로 가른다 — dialect 가 만드는 값에서 서버가 거부한 필드를 뺀 뒤({@code ProviderThinkingDialects#wireFor}
         * 와 같은 식) 아무것도 남지 않으면 서버가 정하고, 남으면 우리가 정한다. 필드 이름을 문자열로 비교하는 두 번째 규칙을
         * 두지 않는다.
         */
        public ThinkingControl control() {
            var wire = resolved.wire(com.example.ragagent.llm.ThinkingLevel.OFF)
                    .without(rejected == null ? java.util.Set.of() : rejected);
            return wire.sent() == com.example.ragagent.llm.ThinkingWire.Sent.NOTHING
                    ? ThinkingControl.SERVER : ThinkingControl.APPLIED;
        }

        /** 메시지 키 — {@code settings.thinking.support.on-off} 처럼. */
        public String supportKey() {
            return "settings.thinking.support." + support.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * A titled group of settings on the page (e.g. "검색 튜닝 (핫 수정)").
     *
     * @param note 제목 옆에 붙이는 표시의 메시지 키(nullable) — "재기동 필요" 처럼 그룹 전체에 해당하는 말. 예전에는 항목마다
     *             붙였는데, 같은 말이 줄마다 반복돼 오히려 어느 값이 그런지 읽히지 않았다
     */
    public record SettingGroup(String id, String title, List<SettingItem> items, String note) {
        public SettingGroup(String id, String title, List<SettingItem> items) {
            this(id, title, items, null);
        }
    }

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
         * 툴팁 없는 행(대다수)을 위한 편의 생성자 — {@code SourceRef} 의 5/8/11-인자 생성자와 같은
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
