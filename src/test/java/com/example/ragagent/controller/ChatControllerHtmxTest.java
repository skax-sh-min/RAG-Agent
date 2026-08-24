package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.model.ChatResponse;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.repository.MemoryRepository;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.AgentService;
import com.example.ragagent.service.ChatImageAnalysisSkipRegistry;
import com.example.ragagent.service.ConversationSummarizerService;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.RetrievalMetricsService;
import com.example.ragagent.service.SettingsService;
import com.example.ragagent.service.StreamingAgentService;
import com.example.ragagent.service.ThreadMetaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * QA — ChatController HTMX 계약 보호
 *
 * Covers (per refactoring/01-test-safety-net.md):
 *  - POST /ui/chat 가 정상 응답 시 'fragments/message-assistant :: message' 반환
 *  - 빈 질문 → 'fragments/message-error :: message'
 *  - 서비스 예외 → 'fragments/message-error :: message'
 *  - directMode 누락 시 400 방지
 */
@WebMvcTest(value = ChatController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@WithMockUser
@ResourceLock("global-state")
class ChatControllerHtmxTest {

    @Autowired MockMvc mvc;

    @MockitoBean AgentService agentService;
    @MockitoBean StreamingAgentService streamingAgentService;
    @MockitoBean ThreadMetaService threadMetaService;
    @MockitoBean MemoryService memoryService;
    @MockitoBean ConversationSummarizerService summarizerService;
    @MockitoBean CuratedQaService curatedQaService;
    @MockitoBean AppProperties props;
    @MockitoBean LlmRouter llmRouter;
        @MockitoBean SettingsService settingsService;
    @MockitoBean RetrievalMetricsService retrievalMetricsService;
    @MockitoBean ChatModel chatModel;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean ChatImageAnalysisSkipRegistry imageSkipRegistry;

    private ChatResponse sampleResponse() {
        return new ChatResponse(
                "## 요약\n핵심 답변",
                "manual",
                List.of(new SourceRef("doc.pdf | v1 | p.3", "snippet preview", "chunk-1", "doc_abc", 3)),
                List.of(),
                120, 80, 2, 0.42,
                null, "gemini-flash", 1L,
                true, null, null);   // 검증 통과 → 사유 없음, 환경 의존 값 안내도 없음
    }

    @Test
    @DisplayName("GET / — 응답 모드 토글은 S/N/C 세 개이고 기본 선택은 N (PLAN §6.24 Step 4-a)")
    void chatPage_rendersAllThreeResponseModes() throws Exception {
        // base.html 이 principal.displayName 을 읽으므로 @WithMockUser 기본 principal 로는 렌더되지 않는다.
        AppUserDetails principal = new AppUserDetails("id-1", "user@local", "", "User", "USER", true, false);

        String html = mvc.perform(get("/").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(view().name("chat"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"response-mode-s\"", "id=\"response-mode-n\"",
                                  "id=\"response-mode-c\"");
        assertThat(html).contains("name=\"responseMode\" value=\"N\"");
        // L/M 버튼의 잔재가 남아 있으면 사용자가 서버에 존재하지 않는 모드를 고를 수 있게 된다.
        assertThat(html).doesNotContain("response-mode-l", "response-mode-m");
        // 메시지 번들 키를 못 찾으면 Thymeleaf 는 ??key_locale?? 를 그대로 찍는다 — 모드를 추가하면서
        // chat.response.* 키를 빠뜨리는 것이 이 화면의 대표적인 실수다.
        assertThat(html).doesNotContain("??chat.response");
        // 없는 요소를 참조하는 JS 가 남으면 getElementById 가 null 을 주고, 그걸 건드리는 순간
        // TypeError 로 DOMContentLoaded 블록 전체(라우팅 모드 동기화·툴팁·태그 입력)가 죽는다.
        // HTML 은 멀쩡해 보이므로 위 단언들로는 절대 잡히지 않는다 — 제거된 L 참조가 그 사례였다.
        assertThat(html).doesNotContain("responseModeLRadio");
        // 반대로 C 는 스크립트가 참조하는 것과 버튼이 반드시 짝이어야 한다(Direct 배타 비활성화 대상).
        assertThat(html).contains("responseModeCRadio", "updateResponseModeAvailability");
    }

    @Test
    @DisplayName("POST /ui/chat — 정상 응답 시 message-assistant fragment 반환")
    void postChat_returnsAssistantFragment() throws Exception {
        when(agentService.chat(any(), any())).thenReturn(sampleResponse());

        mvc.perform(post("/ui/chat")
                        .param("question", "테스트 질문")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("routingMode", "COST_FIRST")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-assistant :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat — 빈 질문 → message-error fragment")
    void postChat_blankQuestion_returnsErrorFragment() throws Exception {
        mvc.perform(post("/ui/chat")
                        .param("question", "")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-error :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat — 서비스 예외 → message-error fragment")
    void postChat_serviceException_returnsErrorFragment() throws Exception {
        when(agentService.chat(any(), any())).thenThrow(new RuntimeException("LLM down"));

        mvc.perform(post("/ui/chat")
                        .param("question", "q")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(view().name("fragments/message-error :: message"));
    }

    // ── 회귀: directMode 파라미터 누락 시 400 방지 ────────────────────────

    @Test
    @DisplayName("POST /ui/chat — directMode 누락 시 400 아닌 정상 응답")
    void postChat_missingDirectMode_doesNotReturn400() throws Exception {
        when(agentService.chat(any(), any())).thenReturn(sampleResponse());

        mvc.perform(post("/ui/chat")
                        .param("question", "테스트")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-assistant :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat/stream — directMode 누락 시 SSE 정상 시작")
    void streamChat_missingDirectMode_doesNotReturn400() throws Exception {
        when(props.sseTimeoutMs()).thenReturn(300_000L);
        mvc.perform(post("/ui/chat/stream")
                        .param("question", "테스트")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("§6.10 — POST /ui/chat/summary/precompute → 202 Accepted (백그라운드 발화 후 즉시 응답)")
    void precomputeSummary_returnsAccepted() throws Exception {
        mvc.perform(post("/ui/chat/summary/precompute")
                        .param("threadId", "t1")
                        .with(csrf()))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /ui/chat — 전송된 태그 선택을 스레드에 스냅샷 저장 (사이드바 목록용)")
    void postChat_snapshotsSelectedTagsOntoThread() throws Exception {
        when(agentService.chat(any(), any())).thenReturn(sampleResponse());

        mvc.perform(post("/ui/chat")
                        .param("question", "테스트 질문")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("tags", "billing, policy")
                        .with(csrf()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(threadMetaService)
                .updateTags(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("t1"),
                        org.mockito.ArgumentMatchers.eq(List.of("billing", "policy")));
    }

        @Test
        @DisplayName("POST /ui/chat — response-mode-radio=S 이면 hidden 값과 무관하게 ResponseMode.S 전달")
        void postChat_responseModeRadioOverridesHiddenMode() throws Exception {
                when(agentService.chat(any(), any())).thenReturn(sampleResponse());

                mvc.perform(post("/ui/chat")
                                                .param("question", "테스트 질문")
                                                .param("threadId", "t1")
                                                .param("version", "latest")
                                                .param("responseMode", "N")
                                                .param("response-mode-radio", "S")
                                                .with(csrf()))
                                .andExpect(status().isOk())
                                .andExpect(view().name("fragments/message-assistant :: message"));

                verify(agentService).chat(any(), argThat(req -> req.responseMode() == com.example.ragagent.model.ResponseMode.S));
        }

    @Test
    @DisplayName("POST /ui/chat/stream — 전송된 태그 선택을 스레드에 스냅샷 저장 (사이드바 목록용)")
    void streamChat_snapshotsSelectedTagsOntoThread() throws Exception {
        when(props.sseTimeoutMs()).thenReturn(300_000L);

        mvc.perform(post("/ui/chat/stream")
                        .param("question", "테스트")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("tags", "onboarding")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        org.mockito.Mockito.verify(threadMetaService)
                .updateTags(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("t1"),
                        org.mockito.ArgumentMatchers.eq(List.of("onboarding")));
    }

    @Test
    @DisplayName("POST /ui/chat/stream — response-mode-radio=S 이면 StreamingAgentService에 S가 전달")
    void streamChat_responseModeRadioPropagatesToStreamingService() throws Exception {
        when(props.sseTimeoutMs()).thenReturn(300_000L);

        mvc.perform(post("/ui/chat/stream")
                        .param("question", "테스트")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("responseMode", "N")
                        .param("response-mode-radio", "S")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(streamingAgentService,
                org.mockito.Mockito.timeout((int) TimeUnit.SECONDS.toMillis(1)))
                .run(any(), argThat(form -> form.responseModeOrDefault() == com.example.ragagent.model.ResponseMode.S), any());
    }

    @Test
    @DisplayName("GET /chat/{threadId} — 히스토리 turn id들로 curatedQaService.findFailedTurnIds를 호출해 모델에 노출")
    void chat_existingThread_exposesCuratedEmbedFailedTurnIds() throws Exception {
        when(threadMetaService.findById(any(), eq("thread-01"))).thenReturn(Optional.of(
                new ThreadMeta("thread-01", "user", "제목", "latest", "now", "now", "COST_FIRST", "")));
        List<MemoryRepository.Turn> turns = List.of(
                new MemoryRepository.Turn(1L, "q1", "a1", null, null, 0, 0, 0, "local", 1, "LIKE", "M", null),
                new MemoryRepository.Turn(2L, "q2", "a2", null, null, 0, 0, 0, "local", 1, null, "M", null));
        when(memoryService.getTurns(any(), eq("thread-01"))).thenReturn(turns);
        when(curatedQaService.findFailedTurnIds(List.of(1L, 2L))).thenReturn(Set.of(1L));

        // base.html reads principal.displayName — needs a real AppUserDetails, not @WithMockUser's default.
        AppUserDetails principal = new AppUserDetails("id-1", "user@local", "", "User", "USER", true, false);

        mvc.perform(get("/chat/thread-01").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("curatedEmbedFailedTurnIds", Set.of(1L)));

        verify(curatedQaService).findFailedTurnIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("POST /ui/chat/stream/skip-images — registry.requestSkip(threadId) 호출 + 204")
    void skipImageAnalysis_forwardsThreadIdToRegistry() throws Exception {
        mvc.perform(post("/ui/chat/stream/skip-images")
                        .param("threadId", "t1")
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(imageSkipRegistry).requestSkip("t1");
    }
}
