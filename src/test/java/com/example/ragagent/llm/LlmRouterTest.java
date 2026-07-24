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
}
