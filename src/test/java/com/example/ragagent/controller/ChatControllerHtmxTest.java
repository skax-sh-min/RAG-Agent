package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.model.ChatResponse;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.model.VerificationSnapshot;
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
import static org.hamcrest.Matchers.containsString;
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
                true, null, null,    // 검증 통과 → 사유 없음, 환경 의존 값 안내도 없음
                false, java.util.List.of()); // 생성 모드 아님 → 발명된 심볼도 없음
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

    /**
     * 회귀 — 8자보다 짧은 thread id 로 채팅 페이지가 500 이 되던 사고.
     *
     * <p>사이드바가 id 를 8자로 줄여 보여주는데, 그 자르기를 템플릿이
     * {@code #strings.substring(meta.threadId, 0, 8)} 으로 직접 하고 있었다. 스레드 id 는 보통
     * UUID 지만 전부 그렇지는 않아서, 실배포에 있던 레거시 스레드 {@code "default"}(7자)를 열면
     * {@code StringIndexOutOfBoundsException} 이 나고 페이지 전체가 죽었다.
     *
     * <p>단위 테스트({@code ThreadMetaTest.shortThreadId_*})만으로는 이 사고를 잡을 수 없다 —
     * 터진 곳이 템플릿이었기 때문에, 실제로 렌더까지 가는 이 테스트가 함께 있어야 한다.
     */
    @Test
    @DisplayName("GET /chat/{threadId} — id가 8자보다 짧아도 페이지가 렌더된다 (500 회귀)")
    void chat_shortThreadId_rendersWithoutError() throws Exception {
        when(threadMetaService.findById(any(), eq("default"))).thenReturn(Optional.of(
                new ThreadMeta("default", "user", "제목", "latest", "now", "now", "COST_FIRST", "")));
        when(memoryService.getTurns(any(), eq("default"))).thenReturn(List.of());

        AppUserDetails principal = new AppUserDetails("id-1", "user@local", "", "User", "USER", true, false);

        mvc.perform(get("/chat/default").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(view().name("chat"))
                .andExpect(content().string(containsString("default")));
    }

    /** C(응용) 턴 — 검증 통과(생성) + 발명된 이름 하나. */
    private ChatResponse creativeResponse() {
        return new ChatResponse(
                "## 구현\n생성된 코드", "manual",
                List.of(), List.of(),
                120, 80, 2, 0.42,
                null, "local", 1L,
                true, null, "포트는 환경마다 다릅니다",
                true, List.of("parseDateEx"));
    }

    @Test
    @DisplayName("배지는 새로고침 전후가 같다 — 방금 보낸 응답과 되살린 기록이 같은 문구를 낸다 (§6.24 Step 4-b)")
    void verificationBadges_survivePageReload() throws Exception {
        AppUserDetails principal = new AppUserDetails("id-1", "user@local", "", "User", "USER", true, false);

        // ① 방금 보낸 메시지 (HTMX 폴백 프래그먼트)
        when(agentService.chat(any(), any())).thenReturn(creativeResponse());
        String justSent = mvc.perform(post("/ui/chat")
                        .param("question", "날짜 함수로 예제 만들어줘")
                        .param("threadId", "thread-01")
                        .param("version", "latest")
                        .param("routingMode", "COST_FIRST")
                        .param("responseMode", "C")
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // ② 새로고침 후 (chat.html 의 기록 루프 — 저장해 둔 검증 결과로 되살린다)
        when(threadMetaService.findById(any(), eq("thread-01"))).thenReturn(Optional.of(
                new ThreadMeta("thread-01", "user", "제목", "latest", "now", "now", "COST_FIRST", "")));
        when(memoryService.getTurns(any(), eq("thread-01"))).thenReturn(List.of(
                new MemoryRepository.Turn(1L, "날짜 함수로 예제 만들어줘", "## 구현\n생성된 코드",
                        null, null, 0, 0, 0, "local", 1, null, "C", null)));
        when(memoryService.getVerifications(List.of(1L))).thenReturn(java.util.Map.of(
                1L, new VerificationSnapshot(true, true, null, "포트는 환경마다 다릅니다",
                        List.of("parseDateEx"))));
        String afterReload = mvc.perform(get("/chat/thread-01").with(user(principal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 세 신호가 양쪽에 모두 있어야 한다. 예전에는 기록 쪽이 검증 배지를 아예 렌더하지 않아
        // 새로고침 한 번으로 "문서 밖 이름" 경고가 사라졌다 — C 에서 그것은 안전 신호다.
        // 렌더된 마크업만 겨냥한다 — 두 템플릿 모두 HTML 주석에 배지 문구를 설명으로 적어 두고
        // 있어서, 평문으로 대조하면 주석이 걸려 통과해 버린다(실제로 그렇게 한 번 새 통과했다).
        for (String signal : new String[]{">생성<", "문서 밖 이름", "parseDateEx", "포트는 환경마다 다릅니다"}) {
            assertThat(justSent).as("방금 보낸 응답에 '%s' 가 없다", signal).contains(signal);
            assertThat(afterReload).as("새로고침 후 '%s' 가 사라졌다", signal).contains(signal);
        }
        // 생성 배지는 초록 '검증됨' 이 아니라 파랑이다 — 통과한 검증의 질문 자체가 다르기 때문이다.
        // 부정 신호도 렌더된 배지 문구로 잡는다: bg-success 는 페이지 전체(사이드바 등)에서도 쓰이고,
        // 평문 '검증됨' 은 템플릿 주석에도 있어 둘 다 이 대조에는 쓸 수 없다.
        assertThat(justSent).contains("bg-primary").doesNotContain(">검증됨<");
        assertThat(afterReload).contains("bg-primary").doesNotContain(">검증됨<");
    }

    @Test
    @DisplayName("검증 기록이 없는 턴은 기록에서 배지를 띄우지 않는다 (구 데이터 하위호환)")
    void turnsWithoutVerification_renderNoBadge() throws Exception {
        AppUserDetails principal = new AppUserDetails("id-1", "user@local", "", "User", "USER", true, false);
        when(threadMetaService.findById(any(), eq("thread-01"))).thenReturn(Optional.of(
                new ThreadMeta("thread-01", "user", "제목", "latest", "now", "now", "COST_FIRST", "")));
        when(memoryService.getTurns(any(), eq("thread-01"))).thenReturn(List.of(
                new MemoryRepository.Turn(1L, "q", "a", null, null, 0, 0, 0, "local", 1, null, "N", null)));
        when(memoryService.getVerifications(List.of(1L))).thenReturn(java.util.Map.of());

        String html = mvc.perform(get("/chat/thread-01").with(user(principal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 이 컬럼이 생기기 전의 모든 턴이 이 상태다 — 배지가 없는 예전 화면 그대로여야 한다.
        // (평문 '검증됨' 은 주석에도 있으므로 렌더된 배지 마크업으로 대조한다.)
        assertThat(html).doesNotContain(">검증됨<", ">생성<", ">미검증<", "문서 밖 이름");
    }

    @Test
    @DisplayName("대화 기록의 질문 앞에 응답 모드가 '[S] 질문' 형태로 붙고, 구 M/L 은 N 으로 표기된다")
    void historyQuestionsCarryTheResponseModePrefix() throws Exception {
        AppUserDetails principal = new AppUserDetails("id-1", "user@local", "", "User", "USER", true, false);
        when(threadMetaService.findById(any(), eq("thread-01"))).thenReturn(Optional.of(
                new ThreadMeta("thread-01", "user", "제목", "latest", "now", "now", "COST_FIRST", "")));
        when(memoryService.getTurns(any(), eq("thread-01"))).thenReturn(List.of(
                new MemoryRepository.Turn(1L, "요약 질문", "a", null, null, 0, 0, 0, "local", 1, null, "S", null),
                new MemoryRepository.Turn(2L, "응용 질문", "a", null, null, 0, 0, 0, "local", 1, null, "C", null),
                new MemoryRepository.Turn(3L, "옛 기록 질문", "a", null, null, 0, 0, 0, "local", 1, null, "M", null),
                new MemoryRepository.Turn(4L, "모드 없는 질문", "a", null, null, 0, 0, 0, "local", 1, null, null, null)));
        when(memoryService.getVerifications(any())).thenReturn(java.util.Map.of());

        String html = mvc.perform(get("/chat/thread-01").with(user(principal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("[S] ", "[C] ");
        // 구 M/L 과 NULL 은 ResponseMode.parse 를 거쳐 N 으로 읽힌다 — 표기가 저장값이 아니라
        // '그 턴이 실제로 답한 방식'을 가리켜야 하므로 화면에도 N 으로 나와야 한다.
        assertThat(html).as("구 M 기록이 그대로 노출되면 안 된다").doesNotContain("[M] ", "[L] ", "[null] ");
        assertThat(html).contains("data-response-mode=\"N\"");

        // 질문 원본에는 표기가 섞이지 않는다 — data-question 은 추천/재사용이 쓰는 값이라,
        // 여기에 대괄호가 끼면 그 소비자들에게 그대로 딸려간다.
        assertThat(html).contains("data-question=\"요약 질문\"");
    }

    @Test
    @DisplayName("좋아요가 무동작인 모드(S·C)에서는 대화 기록의 👍가 비활성으로, 사유와 함께 그려진다")
    void likeButtonIsDisabledWithReason_whenModeIsNotCuratable() throws Exception {
        AppUserDetails principal = new AppUserDetails("id-1", "user@local", "", "User", "USER", true, false);
        when(threadMetaService.findById(any(), eq("thread-01"))).thenReturn(Optional.of(
                new ThreadMeta("thread-01", "user", "제목", "latest", "now", "now", "COST_FIRST", "")));
        when(memoryService.getTurns(any(), eq("thread-01"))).thenReturn(List.of(
                new MemoryRepository.Turn(1L, "q", "a", null, null, 0, 0, 0, "local", 1, null, "C", null),
                new MemoryRepository.Turn(2L, "q2", "a2", null, null, 0, 0, 0, "local", 1, null, "N", null)));
        when(memoryService.getVerifications(any())).thenReturn(java.util.Map.of());

        String html = mvc.perform(get("/chat/thread-01").with(user(principal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 사유 문구가 실제로 해석돼야 한다 — 템플릿이 #{${turn.curationBlockedMessageKey}} 로
        // 키를 변수에서 읽으므로, 이 표기가 깨지면 예외가 아니라 ??key_ko?? 가 화면에 찍힌다.
        assertThat(html).contains("공유 지식으로 등록되지 않습니다");
        assertThat(html).doesNotContain("??feedback.like.disabled");
        // C 턴의 👍 는 disabled 로, N 턴의 👍 는 평소대로.
        assertThat(html).contains("feedback-btn opacity-50");
        assertThat(html).contains("data-feedback=\"LIKE\"");
        // 싫어요는 모드와 무관하게 살아 있어야 한다 — 이 수정이 건드리는 것은 좋아요뿐이다.
        assertThat(html).contains("data-feedback=\"DISLIKE\"");
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
