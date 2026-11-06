package com.example.ragagent.mcp.server;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.GuestIdentityResolver;
import com.example.ragagent.security.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 엔드포인트의 보안 계약 — <b>세 보안 모드에서 똑같아야 한다</b>(AGENT_MCP_DESIGN §7 Phase 2 완료 기준). 모드마다 이 클래스를 상속한
 * 슬라이스 테스트가 하나씩 있고 케이스는 여기 한 벌이다.
 *
 * <p>실제 MCP SDK 엔드포인트 대신 같은 경로({@code /api/v1/mcp})에 가짜 컨트롤러를 둔다. 여기서 보려는 것은 SDK가 아니라 <b>그 앞의 문</b>
 * — 전용 체인이 모드별 체인보다 먼저 이 경로를 잡는지, 키 없는 요청이 401 로 막히는지, 키로 만든 호출자가 컨트롤러(= Tool 이 도는 요청
 * 스레드)에서 읽히는지다. 실제 SDK 와의 왕복은 앱을 띄워 {@code initialize → tools/list → tools/call} 로 확인했다(운영 매뉴얼).
 */
@ResourceLock("global-state")
abstract class McpSecurityModesBase {

    static final String READ_KEY = "read-key-0123456789ab";
    static final String ADMIN_KEY = "admin-key-0123456789a";

    @Autowired MockMvc mvc;

    // @WebMvcTest 가 HandlerMethodArgumentResolver 빈을 함께 올리는데 CurrentUser 를 요구한다 — 이 슬라이스에는 필요 없다
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.example.ragagent.context.ThreadContextResolver threadContextResolver;

    // WebConfig 의 chatClient 빈이 요구한다 — 이 슬라이스는 LlmConfig 를 로드하지 않는다
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.chat.model.ChatModel chatModel;

    static AppProperties propsFor(AppProperties.AuthConfig auth) {
        AppProperties props = mock(AppProperties.class);
        when(props.authSafe()).thenReturn(auth);
        return props;
    }

    static GuestIdentityResolver guestResolver(AppProperties props) {
        return new GuestIdentityResolver(props, new ClientIpResolver(false),
                mock(com.example.ragagent.repository.AppSecretRepository.class));
    }

    @Test
    @DisplayName("키가 없으면 401 이고 WWW-Authenticate 가 붙는다 — CSRF 403 도 로그인 리다이렉트도 아니다")
    void noKeyIs401() throws Exception {
        mvc.perform(post("/api/v1/mcp").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("WWW-Authenticate", org.hamcrest.Matchers.containsString("Bearer")));
        mvc.perform(get("/api/v1/mcp")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("틀린 키 · 빈 Bearer · 읽기 키의 앞부분은 401")
    void wrongKeysAre401() throws Exception {
        mvc.perform(post("/api/v1/mcp").header("Authorization", "Bearer nope-nope-nope-nope")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mcp").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mcp").header("Authorization", "Basic " + READ_KEY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mcp").header("Authorization", "Bearer " + READ_KEY.substring(0, 10))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mcp").header("X-API-Key", " ")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("읽기 키(Bearer · X-API-Key)는 통과하고 호출자는 관리자가 아니다 — CSRF 토큰 없이도")
    void readKeyIsAllowedAsNonAdmin() throws Exception {
        mvc.perform(post("/api/v1/mcp").header("Authorization", "Bearer " + READ_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.user").value("mcp-read"))
                .andExpect(jsonPath("$.channel").value("mcp"));
        mvc.perform(post("/api/v1/mcp").header("X-API-Key", READ_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(false));
        mvc.perform(post("/api/v1/mcp").header("Authorization", "bearer " + READ_KEY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("관리자 키는 관리자 호출자를 만든다")
    void adminKeyMakesAnAdminCaller() throws Exception {
        mvc.perform(post("/api/v1/mcp").header("Authorization", "Bearer " + ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.user").value("mcp-admin"));
    }

    @Test
    @DisplayName("하위 경로도 같은 문이다 · 세션을 만들지 않는다")
    void subPathsAreGuardedAndStateless() throws Exception {
        mvc.perform(post("/api/v1/mcp/sub")).andExpect(status().isUnauthorized());
        MvcResult ok = mvc.perform(post("/api/v1/mcp/sub").header("Authorization", "Bearer " + READ_KEY))
                .andExpect(status().isOk()).andReturn();
        assertThat(ok.getRequest().getSession(false)).as("STATELESS").isNull();
        assertThat(ok.getResponse().getHeader("Set-Cookie")).isNull();
    }
}
