package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영향도 리포트 화면(§6.33 4a) — 요청자·관리자만 보고, 남의 작업은 없음(404)과 같고, 진행 중일 때만 폴링하며, 리포트를 실제 템플릿으로 그린다.
 */
@WebMvcTest(value = ImpactJobController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class ImpactJobControllerTest {

    private static final AppUserDetails OWNER = new AppUserDetails("u1", "u1@example.com", null, "사용자", "USER", true, false);
    private static final AppUserDetails ADMIN = new AppUserDetails("root", "root@example.com", null, "관리자", "ADMIN", true, false);

    private static final String REPORT = """
            {"subject":"ABC API","changeNote":null,"mentions":3,"truncated":false,"tooShort":false,"judged":2,
             "findings":[
               {"docId":"d1","file":"guide.pdf","chunkId":"c1","page":"4","verdict":"impacted","reason":"인증 설명","quote":"ABC API 는 토큰을 요구한다"},
               {"docId":"d2","file":"memo.pdf","chunkId":"c2","page":"1","verdict":"unverified","reason":"호출","quote":"지어낸 문장"}],
             "unjudged":[{"docId":"d3","file":"tail.pdf","chunks":1}]}""";

    @Autowired MockMvc mvc;

    @MockitoBean ChangeJobRepository jobs;
    @MockitoBean AppProperties props;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;

    @BeforeEach
    void setUp() throws Exception {
        when(threadContextResolver.supportsParameter(any())).thenReturn(true);
        when(threadContextResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new ThreadContext("t1", "u1", Locale.KOREAN));
    }

    private static ChangeJobRepository.Job job(long id, String owner, String status, String summary) {
        return new ChangeJobRepository.Job(id, "impact", "ABC API", owner, status, summary, status.equals("failed") ? "boom" : null,
                "2026-11-10 01:00:00", "2026-11-10 01:00:00");
    }

    @Test
    @DisplayName("끝난 작업 — 판정 배지·인용·검토하지 못한 곳이 그려지고 폴링 속성은 없다")
    void rendersTheFinishedReport() throws Exception {
        when(jobs.find(5L)).thenReturn(Optional.of(job(5, "u1", "done", REPORT)));

        mvc.perform(get("/ui/impact-jobs/5").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("guide.pdf")))
                .andExpect(content().string(containsString("영향 있음")))
                .andExpect(content().string(containsString("근거 미확인")))
                .andExpect(content().string(containsString("ABC API 는 토큰을 요구한다")))
                .andExpect(content().string(containsString("tail.pdf")))
                .andExpect(content().string(not(containsString("every 3s"))));
    }

    @Test
    @DisplayName("진행 중이면 본문 조각이 3초 폴링을 달고, 조각 경로도 같은 내용이다")
    void runningJobPolls() throws Exception {
        when(jobs.find(6L)).thenReturn(Optional.of(job(6, "u1", "running", null)));

        mvc.perform(get("/ui/impact-jobs/6/body").with(user(OWNER)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("every 3s")))
                .andExpect(content().string(containsString("/ui/impact-jobs/6/body")));
    }

    @Test
    @DisplayName("3글자 미만이면 '영향 없음'이 아니라고 밝힌다 · 실패는 사유를 보인다")
    void tooShortAndFailedAreExplained() throws Exception {
        when(jobs.find(7L)).thenReturn(Optional.of(job(7, "u1", "done",
                "{\"subject\":\"DB\",\"mentions\":0,\"truncated\":false,\"tooShort\":true,\"judged\":0,\"findings\":[],\"unjudged\":[]}")));
        when(jobs.find(8L)).thenReturn(Optional.of(job(8, "u1", "failed", null)));

        mvc.perform(get("/ui/impact-jobs/7").with(user(OWNER)))
                .andExpect(content().string(containsString("3글자 미만")));
        mvc.perform(get("/ui/impact-jobs/8").with(user(OWNER)))
                .andExpect(content().string(containsString("분석에 실패했습니다")))
                .andExpect(content().string(containsString("boom")));
    }

    @Test
    @DisplayName("남의 작업은 없는 작업과 같은 404 — 관리자는 볼 수 있다")
    void foreignJobIsNotFoundUnlessAdmin() throws Exception {
        when(jobs.find(9L)).thenReturn(Optional.of(job(9, "someone-else", "done", REPORT)));
        when(jobs.find(10L)).thenReturn(Optional.empty());

        mvc.perform(get("/ui/impact-jobs/9").with(user(OWNER))).andExpect(status().isNotFound());
        mvc.perform(get("/ui/impact-jobs/10").with(user(OWNER))).andExpect(status().isNotFound());
        mvc.perform(get("/ui/impact-jobs/9").with(user(ADMIN))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("영향도가 아닌 작업(kind)은 이 화면에서 열리지 않는다")
    void otherKindsAreNotShown() throws Exception {
        when(jobs.find(11L)).thenReturn(Optional.of(new ChangeJobRepository.Job(11, "maintenance", "x", "u1", "done", null, null, "t", "t")));
        mvc.perform(get("/ui/impact-jobs/11").with(user(OWNER))).andExpect(status().isNotFound());
    }
}
