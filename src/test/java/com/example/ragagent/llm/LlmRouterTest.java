package com.example.ragagent.llm;

import com.example.ragagent.exception.LlmBackpressureException;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — LlmRouter routing & guard behaviour
 *
 * Focuses mainly on the side-effect-free surface: findFirst() priority by RoutingMode,
 * hasLocalProvider(), findProviderName() — plus targeted
 * Mockito-based coverage of executeWithTracking()'s circuit-breaker/backpressure branches
 * (mmproj detection, concurrency gate, overload-without-fallback blocking).
 */
class LlmRouterTest {

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new CircuitBreaker(2);
    }

    private LlmProvider p(String name, ProviderRole role, TaskType type, int priority) {
        return new LlmProvider(name, type, role, priority, "k", null, null, true, (ChatModel) null, null);
    }

    private LlmRouter router(RoutingMode defaultMode, LlmProvider... providers) {
        return new LlmRouter(List.of(providers), null, breaker, defaultMode, 0.6);
    }

    @Test
    @DisplayName("COST_FIRST: LOCAL > NORMAL > PREMIUM 순으로 선택")
    void costFirstPrefersLocal() {
        var local   = p("lm",     ProviderRole.LOCAL,   TaskType.TEXT, 1);
        var normal  = p("openai", ProviderRole.NORMAL,  TaskType.TEXT, 2);
        var premium = p("claude", ProviderRole.PREMIUM, TaskType.TEXT, 3);
        var r = router(RoutingMode.COST_FIRST, local, normal, premium);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("lm");
    }

    /**
     * 실제로 겪은 사고 — `LOCAL_LLM_TYPE=TEXT` 로 등록된 로컬 모델 하나뿐인 폐쇄망(LOCAL_ONLY)
     * 배포에서 MICRO_TEXT/LIGHT_TEXT 자격 프로바이더가 0개가 됐다. 인덱싱 로그에는
     * "All providers exhausted for task=MICRO_TEXT" 가 DEBUG 로만 찍히고 채팅은 TEXT 라
     * 정상 동작해, 키워드+맥락 추출·MD 교정·제목·요약이 조용히 전부 죽어 있었다.
     */
    @Test
    @DisplayName("findFirst — type=TEXT 단독 프로바이더도 MICRO_TEXT·LIGHT_TEXT 를 받는다 (사다리)")
    void loneTextProviderServesLighterTextTasks() {
        var local = p("local", ProviderRole.LOCAL, TaskType.TEXT, 1);
        var r = router(RoutingMode.LOCAL_ONLY, local);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.LOCAL_ONLY)).isEqualTo("local");
        assertThat(r.findProviderName(TaskType.LIGHT_TEXT, RoutingMode.LOCAL_ONLY)).isEqualTo("local");
        assertThat(r.findProviderName(TaskType.MICRO_TEXT, RoutingMode.LOCAL_ONLY)).isEqualTo("local");
        // VISION 은 다른 축이라 여전히 흡수되지 않는다 (mmproj 없는 모델에 이미지가 가면 안 된다).
        assertThat(r.findProviderName(TaskType.VISION, RoutingMode.LOCAL_ONLY)).isEqualTo("unknown");
    }

    /**
     * 위 사다리 확장이 §6.21(잡무를 답변 모델에서 떼어내기)을 되돌리지 않는다는 확인 —
     * 소형 모델이 등록돼 있으면 priority=0 이 여전히 먼저 뽑히고, 그게 죽었을 때만
     * 답변 모델로 내려간다(예전에는 그냥 실패했다).
     */
    @Test
    @DisplayName("findFirst — 소형(p0) 이 있으면 MICRO_TEXT 는 여전히 소형 우선, 차단 시에만 TEXT 로 폴백")
    void microTextStillPrefersOffloadTierOverTextProvider() {
        var fast  = p("local-fast", ProviderRole.LOCAL, TaskType.MICRO_TEXT, 0);
        var local = p("local",      ProviderRole.LOCAL, TaskType.TEXT,       1);
        var r = router(RoutingMode.LOCAL_ONLY, fast, local);

        assertThat(r.findProviderName(TaskType.MICRO_TEXT, RoutingMode.LOCAL_ONLY)).isEqualTo("local-fast");

        breaker.block("local-fast", null);
        assertThat(r.findProviderName(TaskType.MICRO_TEXT, RoutingMode.LOCAL_ONLY)).isEqualTo("local");
    }

    @Test
    @DisplayName("ProviderToggle: 비활성화된 프로바이더는 findFirst에서 제외되고 다음 우선순위로 넘어간다")
    void disabledProviderIsSkipped() {
        var local  = p("lm",     ProviderRole.LOCAL,  TaskType.TEXT, 1);
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 2);
        var toggle = new ProviderToggle();
        var r = new LlmRouter(List.of(local, normal), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of(), 3, 20, toggle);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("lm");
        assertThat(r.hasLocalProvider()).isTrue();

        toggle.setEnabled("lm", false);
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("openai"); // fell through
        assertThat(r.hasLocalProvider()).isFalse();  // the only LOCAL provider is disabled

        toggle.setEnabled("lm", true);
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("lm"); // re-enabled
    }

    @Test
    @DisplayName("QUALITY_FIRST: PREMIUM > NORMAL > LOCAL 순으로 선택")
    void qualityFirstPrefersPremium() {
        var local   = p("lm",     ProviderRole.LOCAL,   TaskType.TEXT, 1);
        var normal  = p("openai", ProviderRole.NORMAL,  TaskType.TEXT, 2);
        var premium = p("claude", ProviderRole.PREMIUM, TaskType.TEXT, 3);
        var r = router(RoutingMode.COST_FIRST, local, normal, premium);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.QUALITY_FIRST)).isEqualTo("claude");
    }

    @Test
    @DisplayName("LOCAL_ONLY: LOCAL 만 후보, LOCAL 없으면 'unknown'")
    void localOnlyMode() {
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 1);
        var r = router(RoutingMode.COST_FIRST, normal);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.LOCAL_ONLY)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("CircuitBreaker 가 LOCAL 을 차단하면 다음 ROLE(NORMAL) 로 폴백")
    void blockedLocalFallsThroughToNormal() {
        var local  = p("lm",     ProviderRole.LOCAL,  TaskType.TEXT, 1);
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 2);
        var r = router(RoutingMode.COST_FIRST, local, normal);

        breaker.block("lm", null);
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("openai");
    }

    @Test
    @DisplayName("hasLocalProvider — LOCAL 등록 + 차단 안 됨 → true")
    void hasLocalProvider() {
        var local  = p("lm",     ProviderRole.LOCAL,  TaskType.TEXT, 1);
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 2);
        var r = router(RoutingMode.COST_FIRST, local, normal);

        assertThat(r.hasLocalProvider()).isTrue();
        breaker.block("lm", null);
        assertThat(r.hasLocalProvider()).isFalse();
    }

    /**
     * 문서 업로드 화면의 "이미지 설명 추가" 체크박스 활성 판단({@code DocumentController}).
     * 예전 형태는 {@code hasEnabledProviderType(BOTH, VISION)} 이라는 **타입 이름 목록** 검사였고
     * {@code LIGHT_BOTH} 가 빠져 있어, 이미지 설명이 실제로 동작하는 배포에서 체크박스가 비활성화됐다.
     * 이제 {@code supports()} 에서 능력을 파생하므로 목록을 따로 관리할 필요가 없다.
     */
    @Test
    @DisplayName("hasEnabledProviderFor(VISION) — VISION 을 실제로 처리하는 모든 타입을 센다(LIGHT_BOTH 포함)")
    void hasEnabledProviderFor_countsEveryVisionCapableType() {
        for (TaskType visionCapable : List.of(TaskType.VISION, TaskType.LIGHT_BOTH, TaskType.BOTH)) {
            assertThat(router(RoutingMode.COST_FIRST, p("p", ProviderRole.LOCAL, visionCapable, 1))
                    .hasEnabledProviderFor(TaskType.VISION))
                    .as("%s serves VISION", visionCapable).isTrue();
        }
        for (TaskType textOnly : List.of(TaskType.MICRO_TEXT, TaskType.LIGHT_TEXT, TaskType.TEXT)) {
            assertThat(router(RoutingMode.COST_FIRST, p("p", ProviderRole.LOCAL, textOnly, 1))
                    .hasEnabledProviderFor(TaskType.VISION))
                    .as("%s does not serve VISION", textOnly).isFalse();
        }
    }

    @Test
    @DisplayName("hasEnabledProviderFor — /settings 비활성화는 반영, 일시적 서킷 차단은 무시")
    void hasEnabledProviderFor_togglesCountButCircuitBlocksDoNot() {
        var vision = p("local-vision", ProviderRole.LOCAL, TaskType.VISION, 0);
        var toggle = new ProviderToggle();
        var r = new LlmRouter(List.of(vision), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of(), 3, 20, toggle);

        assertThat(r.hasEnabledProviderFor(TaskType.VISION)).isTrue();

        breaker.block("local-vision", null);
        assertThat(r.hasEnabledProviderFor(TaskType.VISION)).isTrue();   // 일시 차단은 능력 부재가 아니다

        toggle.setEnabled("local-vision", false);
        assertThat(r.hasEnabledProviderFor(TaskType.VISION)).isFalse();  // 운영자 결정은 반영
    }

    @Test
    @DisplayName("hasMicroTextOffloadProvider — LOCAL priority=0(local-fast) 등록 여부/차단·비활성화까지 반영")
    void hasMicroTextOffloadProvider() {
        var fast  = p("local-fast", ProviderRole.LOCAL, TaskType.MICRO_TEXT, 0);
        var local = p("local",      ProviderRole.LOCAL, TaskType.BOTH,       1);
        var toggle = new ProviderToggle();
        var r = new LlmRouter(List.of(fast, local), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of(), 3, 20, toggle);

        assertThat(r.hasMicroTextOffloadProvider()).isTrue();

        toggle.setEnabled("local-fast", false);
        assertThat(r.hasMicroTextOffloadProvider()).isFalse();   // 런타임 비활성화
        toggle.setEnabled("local-fast", true);

        breaker.block("local-fast", null);
        assertThat(r.hasMicroTextOffloadProvider()).isFalse();   // 서킷 차단

        // LOCAL_FAST_LLM_URL 미설정 → LlmConfig G2 가 아예 등록하지 않는 상태
        assertThat(router(RoutingMode.COST_FIRST, local).hasMicroTextOffloadProvider()).isFalse();
    }

    @Test
    @DisplayName("hasMicroTextOffloadProvider — LOCAL priority=0 이어도 MICRO_TEXT 를 못 받는 타입이면 false "
            + "(local-vision 이 소형 모델로 오인되던 버그)")
    void hasMicroTextOffloadProvider_visionAtPriorityZeroIsNotAnOffloadTier() {
        // application.properties 의 주석 처리된 local-vision 예제와 동일한 조합 —
        // role=LOCAL, priority=0, type=VISION. VISION 은 VISION 만 지원하므로 findFirst 는
        // 이 프로바이더를 건너뛰고 priority=1 답변 모델로 내려간다. 게이트가 true 를 주면
        // "소형 모델이 있다"고 믿고 요약 LLM 호출을 내보내 답변 티어를 잠식하게 된다.
        var vision = p("local-vision", ProviderRole.LOCAL, TaskType.VISION,    0);
        var local  = p("local",        ProviderRole.LOCAL, TaskType.BOTH,      1);
        var r = new LlmRouter(List.of(vision, local), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of(), 3, 20, new ProviderToggle());

        assertThat(r.hasMicroTextOffloadProvider()).isFalse();

        // 반대로 §9 "A안"(소형을 LIGHT_TEXT 로 등록)은 MICRO_TEXT 를 실제로 처리하므로 true.
        var lightFast = p("local-fast", ProviderRole.LOCAL, TaskType.LIGHT_TEXT, 0);
        assertThat(new LlmRouter(List.of(vision, lightFast, local), null, breaker, RoutingMode.COST_FIRST,
                0.6, 180, Map.of(), 3, 20, new ProviderToggle())
                .hasMicroTextOffloadProvider()).isTrue();
    }

    @Test
    @DisplayName("executeWithTracking — mmproj 미지원 에러는 CircuitBreaker 를 차단하지 않음 (TEXT 작업은 계속 이용 가능)")
    void visionUnsupportedError_doesNotBlockCircuitBreaker() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException(
                "500 - image input is not supported - hint: if this is unexpected, you may need to provide the mmproj"));
        var local = new LlmProvider("lm", TaskType.BOTH, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = router(RoutingMode.COST_FIRST, local);

        assertThatThrownBy(() -> r.executeWithTracking(TaskType.VISION, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x"))))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(breaker.isBlocked("lm")).isFalse();
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("lm");
    }

    @Test
    @DisplayName("executeWithTracking — 컨텍스트 초과는 CircuitBreaker 를 차단하지 않음 (뒤따르는 작은 요청이 말려들지 않는다)")
    void contextOverflow_doesNotBlockCircuitBreaker() {
        ChatModel chatModel = mock(ChatModel.class);
        // 실제 관측된 LM Studio 응답 — HTTP 400 본문 안에 500 이 들어 있고, Spring AI 가
        // NonTransientAiException 으로 감싸므로 HttpStatusCodeException catch 에 걸리지 않는다.
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException(
                "400 - {\"error\":\"Engine protocol predict stream returned an error: "
                + "{\\\"code\\\":500,\\\"message\\\":\\\"Context size has been exceeded.\\\"}\"}"));
        var local = new LlmProvider("lm", TaskType.BOTH, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = router(RoutingMode.COST_FIRST, local);

        // 이 요청 자체는 여전히 실패한다 — 얻는 것은 프로바이더가 살아남는 것뿐이다.
        assertThatThrownBy(() -> r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x"))))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(breaker.isBlocked("lm")).isFalse();
        // mmproj 와 달리 기억해 두지 않는다 — 요청 하나의 크기 문제라 다음 짧은 질문은 통과해야 한다.
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("lm");
        assertThat(r.findProviderName(TaskType.VISION, RoutingMode.COST_FIRST)).isEqualTo("lm");
    }

    @Test
    @DisplayName("컨텍스트 초과 판정 — 서버별 문구를 인식하되 레이트리밋 문구는 건드리지 않는다")
    void contextOverflow_recognizesServerPhrasingsButNotRateLimits() {
        var overflow = List.of(
                "Context size has been exceeded.",                                   // LM Studio
                "context_length_exceeded",                                           // OpenAI code
                "This model's maximum context length is 8192 tokens",                // OpenAI message
                "the request exceeds the available context size",                    // llama.cpp
                "prompt is too long: 210000 tokens > 200000 maximum");               // Anthropic
        for (String msg : overflow) {
            ChatModel cm = mock(ChatModel.class);
            when(cm.call(any(Prompt.class))).thenThrow(new RuntimeException(msg));
            CircuitBreaker cb = new CircuitBreaker(2);
            var p = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true, cm, null);
            var r = new LlmRouter(List.of(p), null, cb, RoutingMode.COST_FIRST, 0.6, 180,
                    Map.of(), 3, 20, new ProviderToggle());
            assertThatThrownBy(() -> r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST,
                    m -> m.call(new Prompt("x")))).isInstanceOf(LlmProviderExhaustedException.class);
            assertThat(cb.isBlocked("lm")).as("'%s' 는 컨텍스트 초과로 읽혀야 한다", msg).isFalse();
        }

        // 레이트리밋은 여전히 프로바이더 실패로 다뤄야 한다 — "too many tokens" 를 마커에 넣었다면
        // 여기서 걸린다(그랬다면 진짜 429 의 Retry-After 처리까지 건너뛰게 된다).
        ChatModel cm = mock(ChatModel.class);
        when(cm.call(any(Prompt.class))).thenThrow(new RuntimeException("Rate limit: too many tokens per minute"));
        CircuitBreaker cb = new CircuitBreaker(2);
        var p = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true, cm, null);
        var r = new LlmRouter(List.of(p), null, cb, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of(), 3, 20, new ProviderToggle());
        assertThatThrownBy(() -> r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x")))).isInstanceOf(LlmProviderExhaustedException.class);
        assertThat(cb.isBlocked("lm")).as("레이트리밋은 컨텍스트 초과가 아니다").isTrue();
    }

    @Test
    @DisplayName("executeWithTracking — mmproj 미지원 확인 후에는 VISION/LIGHT_BOTH 요청을 재시도 없이 건너뜀")
    void visionUnsupportedError_skipsFutureImageTasksForThatProvider() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("mmproj file not found"));
        var local = new LlmProvider("lm", TaskType.BOTH, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = router(RoutingMode.COST_FIRST, local);

        assertThatThrownBy(() -> r.executeWithTracking(TaskType.LIGHT_BOTH, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x"))))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(r.findProviderName(TaskType.VISION, RoutingMode.COST_FIRST)).isEqualTo("unknown");
        assertThat(r.findProviderName(TaskType.LIGHT_BOTH, RoutingMode.COST_FIRST)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("findFirst — apiKey 비어 있으면 후보에서 제외")
    void blankApiKeyExcluded() {
        var local  = new LlmProvider("lm",     TaskType.TEXT, ProviderRole.LOCAL,  1, "",   null, null, true, (ChatModel) null, null);
        var normal = new LlmProvider("openai", TaskType.TEXT, ProviderRole.NORMAL, 2, "sk", null, null, true, (ChatModel) null, null);
        var r = router(RoutingMode.COST_FIRST, local, normal);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("openai");
    }

    // ── Per-provider concurrency gate + backpressure ──────────────────────────

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("acquirePermit: concurrency=1에서 슬롯이 점유돼 있으면 대기 후 LlmBackpressureException")
    void acquirePermit_timesOutWhenSaturated() {
        var local = p("lm", ProviderRole.LOCAL, TaskType.TEXT, 1);
        var r = new LlmRouter(List.of(local), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of("lm", 1), 1, 1);

        LlmRouter.Permit held = r.acquirePermit(local);
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> r.acquirePermit(local))
                .isInstanceOf(LlmBackpressureException.class);
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(900);
        assertThat(breaker.isBlocked("lm")).isFalse();

        held.close();
        // slot freed — a new acquire must succeed immediately (no leaked permit).
        r.acquirePermit(local).close();
    }

    @Test
    @DisplayName("executeGated: 게이트 포화 시 즉시 429 전파, CircuitBreaker는 차단하지 않음")
    void executeGated_backpressureDoesNotBlockCircuitBreakerOrRetry() {
        ChatModel chatModel = mock(ChatModel.class);
        var local = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = new LlmRouter(List.of(local), mock(LlmUsageRepository.class), breaker,
                RoutingMode.COST_FIRST, 0.6, 180, Map.of("lm", 1), 1, 1);

        LlmRouter.Permit held = r.acquirePermit(local);
        assertThatThrownBy(() -> r.executeGated(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x"))))
                .isInstanceOf(LlmBackpressureException.class);
        assertThat(breaker.isBlocked("lm")).isFalse();
        held.close();
    }

    @Test
    @DisplayName("executeWithTracking(인덱싱 경로, 미적용)은 게이트가 가득 차도 대기 없이 즉시 호출됨")
    void executeWithTracking_ungated_ignoresConcurrencyGate() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("ok"));
        var local = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = new LlmRouter(List.of(local), mock(LlmUsageRepository.class), breaker,
                RoutingMode.COST_FIRST, 0.6, 180, Map.of("lm", 1), 1, 1);

        LlmRouter.Permit held = r.acquirePermit(local); // saturate the gate
        String result = r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x")));
        assertThat(result).isEqualTo("ok");
        held.close();
    }

    @Test
    @DisplayName("executeWithTracking — usageRepo.record() 실패(예: SQLITE_FULL)는 무시되고 성공한 응답이 그대로 반환되며 CircuitBreaker도 차단하지 않는다")
    void executeWithTracking_usageRecordFailure_isSwallowedAndDoesNotBlockProvider() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("ok"));
        var local = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        LlmUsageRepository usageRepo = mock(LlmUsageRepository.class);
        doThrow(new org.springframework.jdbc.UncategorizedSQLException(
                "insert", "INSERT INTO llm_usage ...", new java.sql.SQLException("database or disk is full")))
                .when(usageRepo).record(any(), anyLong(), anyLong());
        var r = new LlmRouter(List.of(local), usageRepo, breaker, RoutingMode.COST_FIRST, 0.6);

        // The LLM call itself succeeded — a bookkeeping-only failure must not be mistaken for a
        // provider failure (which would discard this response, block the circuit breaker, and
        // cascade into LlmProviderExhaustedException for other concurrent callers of "lm").
        String result = r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x")));

        assertThat(result).isEqualTo("ok");
        assertThat(breaker.isBlocked("lm")).isFalse();
    }

    @Test
    @DisplayName("executeWithTracking(게이트 미적용) — 호출 도중 BackgroundLlmConcurrencyTracker가 증가했다가 끝나면 원복된다")
    void executeWithTracking_incrementsAndDecrementsBackgroundTracker() {
        var tracker = new BackgroundLlmConcurrencyTracker();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            assertThat(tracker.get()).as("must be incremented while the call is in flight").isEqualTo(1);
            return chatResponse("ok");
        });
        var local = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = new LlmRouter(List.of(local), mock(LlmUsageRepository.class), breaker, RoutingMode.COST_FIRST,
                0.6, 180, Map.of(), 3, 20, new ProviderToggle(), tracker);

        r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST, m -> m.call(new Prompt("x")));

        assertThat(tracker.get()).as("decremented back to 0 after the call finishes").isEqualTo(0);
    }

    @Test
    @DisplayName("executeGated — 동시성 게이트로 이미 카운트되므로 BackgroundLlmConcurrencyTracker는 증가하지 않는다 (이중 계산 방지)")
    void executeGated_doesNotIncrementBackgroundTracker() {
        var tracker = new BackgroundLlmConcurrencyTracker();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            assertThat(tracker.get()).as("gated calls are already counted via the semaphore-based indicator")
                    .isEqualTo(0);
            return chatResponse("ok");
        });
        var local = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = new LlmRouter(List.of(local), mock(LlmUsageRepository.class), breaker, RoutingMode.COST_FIRST,
                0.6, 180, Map.of(), 3, 20, new ProviderToggle(), tracker);

        r.executeGated(TaskType.TEXT, RoutingMode.COST_FIRST, m -> m.call(new Prompt("x")));

        assertThat(tracker.get()).isEqualTo(0);
    }

    // ── Overload (429/402/503) circuit-breaker blocking ───────────────────────

    private static HttpClientErrorException tooManyRequests(HttpHeaders headers) {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                headers, new byte[0], null);
    }

    private long blockedSecondsFromNow(String providerName) {
        Instant until = breaker.getBlockedProviders().get(providerName);
        assertThat(until).as("provider [%s] should be blocked", providerName).isNotNull();
        return Duration.between(Instant.now(), until).getSeconds();
    }

    @Test
    @DisplayName("폴백 없는 단일 프로바이더의 429(Retry-After 헤더 없음)는 기본 차단시간 대신 짧게(30초) 차단된다")
    void overloadWithoutFallback_usesShortBlockInsteadOfFullDuration() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(tooManyRequests(HttpHeaders.EMPTY));
        var local = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = router(RoutingMode.COST_FIRST, local); // breaker 기본 차단 = 2분(120s)

        assertThatThrownBy(() -> r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x"))))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(blockedSecondsFromNow("lm")).isBetween(20L, 40L);
    }

    @Test
    @DisplayName("폴백이 있는 프로바이더의 429(헤더 없음)는 그대로 정상 차단(기본 시간)되고 다음 프로바이더로 폴백된다")
    void overloadWithFallback_usesFullBlockDurationAndFallsOver() {
        ChatModel localModel = mock(ChatModel.class);
        when(localModel.call(any(Prompt.class))).thenThrow(tooManyRequests(HttpHeaders.EMPTY));
        var local = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                localModel, null);
        ChatModel normalModel = mock(ChatModel.class);
        when(normalModel.call(any(Prompt.class))).thenReturn(chatResponse("ok"));
        var normal = new LlmProvider("openai", TaskType.TEXT, ProviderRole.NORMAL, 2, "sk", null, null, true,
                normalModel, null);
        // router() passes a null usageRepo — fine for tests that only ever throw, but this one
        // needs a real fallback success, which records usage.
        var r = new LlmRouter(List.of(local, normal), mock(LlmUsageRepository.class), breaker,
                RoutingMode.COST_FIRST, 0.6);

        String result = r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x")));

        assertThat(result).isEqualTo("ok");
        assertThat(blockedSecondsFromNow("lm")).isGreaterThan(60L); // 짧은 차단(30s)이 아니라 기본 2분 차단 유지
    }

    @Test
    @DisplayName("폴백이 없어도 서버가 명시한 Retry-After 헤더는 그대로 존중된다(짧은 차단으로 덮어쓰지 않음)")
    void overloadWithoutFallback_stillHonorsExplicitRetryAfterHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "5");
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(tooManyRequests(headers));
        var local = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = router(RoutingMode.COST_FIRST, local);

        assertThatThrownBy(() -> r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x"))))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(blockedSecondsFromNow("lm")).isBetween(0L, 10L);
    }

    @Test
    @DisplayName("폴백 없는 단일 프로바이더의 503(헤더 없음)도 429와 동일하게 짧게 차단된다")
    void serviceUnavailableWithoutFallback_usesShortBlock() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null));
        var local = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = router(RoutingMode.COST_FIRST, local);

        assertThatThrownBy(() -> r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x"))))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(blockedSecondsFromNow("lm")).isBetween(20L, 40L);
    }

    // ── Least-in-flight load balancing across same-priority providers ─────────

    @Test
    @DisplayName("동일 role·동일 priority에서 둘 다 여유 있으면 먼저 등록된 프로바이더를 선택한다(결정적 tie-break)")
    void samePriorityBothIdle_picksFirstRegistered() {
        var local1 = p("local-1", ProviderRole.LOCAL, TaskType.TEXT, 0);
        var local2 = p("local-2", ProviderRole.LOCAL, TaskType.TEXT, 0);
        var r = router(RoutingMode.COST_FIRST, local1, local2);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("local-1");
    }

    @Test
    @DisplayName("동일 role·동일 priority에서 한쪽이 포화 상태면(permit 0) 여유 있는 쪽을 선택한다(least-in-flight)")
    void samePriorityOneSaturated_picksLeastInFlight() {
        var local1 = p("local-1", ProviderRole.LOCAL, TaskType.TEXT, 0);
        var local2 = p("local-2", ProviderRole.LOCAL, TaskType.TEXT, 0);
        var r = new LlmRouter(List.of(local1, local2), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of("local-1", 1, "local-2", 1), 1, 1);

        LlmRouter.Permit held = r.acquirePermit(local1); // local-1의 유일한 슬롯을 점유
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("local-2");
        held.close();

        // 슬롯 반환 후에는 다시 tie 상태 → 먼저 등록된 local-1로 돌아옴
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("local-1");
    }

    @Test
    @DisplayName("priority가 다르면 부하와 무관하게 낮은 priority가 항상 우선한다(동일 priority 그룹 내부에서만 분산)")
    void differentPriorityIgnoresLoad() {
        var local1 = p("local-1", ProviderRole.LOCAL, TaskType.TEXT, 0);
        var local2 = p("local-2", ProviderRole.LOCAL, TaskType.TEXT, 1); // 더 높은(나중) priority
        var r = new LlmRouter(List.of(local1, local2), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of("local-1", 1, "local-2", 1), 1, 1);

        LlmRouter.Permit held = r.acquirePermit(local1); // local-1이 포화 상태여도
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("local-1"); // priority가 우선
        held.close();
    }

    @Test
    @DisplayName("동일 priority 프로바이더 2대 등록 시 한쪽이 포화되면 실제 호출이 다른 쪽으로 분산된다")
    void executeGated_distributesAcrossSamePriorityProvidersUnderLoad() {
        ChatModel model1 = mock(ChatModel.class);
        when(model1.call(any(Prompt.class))).thenReturn(chatResponse("from-1"));
        ChatModel model2 = mock(ChatModel.class);
        when(model2.call(any(Prompt.class))).thenReturn(chatResponse("from-2"));
        var local1 = new LlmProvider("local-1", TaskType.TEXT, ProviderRole.LOCAL, 0, "k", null, null, true,
                model1, null);
        var local2 = new LlmProvider("local-2", TaskType.TEXT, ProviderRole.LOCAL, 0, "k", null, null, true,
                model2, null);
        var r = new LlmRouter(List.of(local1, local2), mock(LlmUsageRepository.class), breaker,
                RoutingMode.COST_FIRST, 0.6, 180, Map.of("local-1", 1, "local-2", 1), 1, 5);

        LlmRouter.Permit held = r.acquirePermit(local1); // local-1 포화
        String result = r.executeGated(TaskType.TEXT, RoutingMode.COST_FIRST, m -> m.call(new Prompt("x")));

        assertThat(result).isEqualTo("from-2");
        held.close();
    }

    @Test
    @DisplayName("localTier1Concurrency — LOCAL priority=1 프로바이더가 없으면 empty (priority=0 이나 non-LOCAL 은 집계 제외)")
    void localTier1Concurrency_noPriority1LocalProvider_empty() {
        var localFast = p("local-fast", ProviderRole.LOCAL, TaskType.MICRO_TEXT, 0); // priority=0 → 제외
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 1); // priority=1 이지만 LOCAL 아님 → 제외
        var r = router(RoutingMode.COST_FIRST, localFast, normal);

        assertThat(r.localTier1Concurrency()).isEmpty();
    }

    @Test
    @DisplayName("localTier1Concurrency — 단일 LOCAL priority=1 프로바이더의 capacity 를 그대로 반환한다")
    void localTier1Concurrency_singleProvider_reportsCapacity() {
        var local = p("local", ProviderRole.LOCAL, TaskType.BOTH, 1);
        var r = new LlmRouter(List.of(local), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of("local", 5), 3, 20);

        var snap = r.localTier1Concurrency();

        assertThat(snap).isPresent();
        assertThat(snap.get().capacity()).isEqualTo(5);
        assertThat(snap.get().inUse()).isEqualTo(0);
    }

    @Test
    @DisplayName("localTier1Concurrency — 동일 role+priority 로 등록된 프로바이더 여러 대의 capacity 를 합산한다")
    void localTier1Concurrency_multipleProviders_sumsCapacity() {
        var local1 = p("local", ProviderRole.LOCAL, TaskType.BOTH, 1);
        var local2 = p("local-2", ProviderRole.LOCAL, TaskType.BOTH, 1);
        var r = new LlmRouter(List.of(local1, local2), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of("local", 3, "local-2", 4), 3, 20);

        var snap = r.localTier1Concurrency().orElseThrow();

        assertThat(snap.capacity()).isEqualTo(7);
        assertThat(snap.inUse()).isEqualTo(0);
    }

    @Test
    @DisplayName("localTier1Concurrency — 획득한 permit 만큼 inUse 가 증가하고, 반환하면 다시 줄어든다")
    void localTier1Concurrency_reflectsAcquiredPermits() {
        var local = p("local", ProviderRole.LOCAL, TaskType.BOTH, 1);
        var r = new LlmRouter(List.of(local), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of("local", 3), 3, 20);

        LlmRouter.Permit permit = r.acquirePermit(local);
        try {
            var snap = r.localTier1Concurrency().orElseThrow();
            assertThat(snap.inUse()).isEqualTo(1);
            assertThat(snap.capacity()).isEqualTo(3);
        } finally {
            permit.close();
        }

        assertThat(r.localTier1Concurrency().orElseThrow().inUse()).isEqualTo(0);
    }

    @Test
    @DisplayName("localTier1Concurrency — 서킷브레이커 차단된 프로바이더는 capacity엔 남고 전체가 inUse로 집계된다(완전히 제외되지 않음)")
    void localTier1Concurrency_blockedProviderCountsFullyAsInUse() {
        var local1 = p("local", ProviderRole.LOCAL, TaskType.BOTH, 1);
        var local2 = p("local-2", ProviderRole.LOCAL, TaskType.BOTH, 1);
        var r = new LlmRouter(List.of(local1, local2), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of("local", 3, "local-2", 4), 3, 20);

        var before = r.localTier1Concurrency().orElseThrow();
        assertThat(before.capacity()).isEqualTo(7);
        assertThat(before.inUse()).isEqualTo(0);

        breaker.block("local", null);
        var afterBlock = r.localTier1Concurrency().orElseThrow();
        assertThat(afterBlock.capacity()).isEqualTo(7); // 차단돼도 capacity 합계에는 그대로 남는다
        assertThat(afterBlock.inUse()).isEqualTo(3); // local의 capacity(3) 전체가 "사용 중"으로 집계됨

        breaker.block("local-2", null);
        var bothBlocked = r.localTier1Concurrency().orElseThrow();
        assertThat(bothBlocked.capacity()).isEqualTo(7);
        assertThat(bothBlocked.inUse()).isEqualTo(7); // 전부 차단 → 완전 포화로 표시(사라지지 않음)
    }

    @Test
    @DisplayName("localTier1Concurrency — 런타임 비활성화(/settings 토글)된 프로바이더는 여전히 집계에서 완전히 제외한다")
    void localTier1Concurrency_disabledProviderStillExcluded() {
        var local1 = p("local", ProviderRole.LOCAL, TaskType.BOTH, 1);
        var local2 = p("local-2", ProviderRole.LOCAL, TaskType.BOTH, 1);
        var toggle = new ProviderToggle();
        var r = new LlmRouter(List.of(local1, local2), null, breaker, RoutingMode.COST_FIRST, 0.6, 180,
                Map.of("local", 3, "local-2", 4), 3, 20, toggle);

        toggle.setEnabled("local-2", false);
        assertThat(r.localTier1Concurrency().orElseThrow().capacity()).isEqualTo(3); // local-2만 제외

        toggle.setEnabled("local", false);
        assertThat(r.localTier1Concurrency()).isEmpty(); // 남은 프로바이더가 없으면 empty
    }
}
