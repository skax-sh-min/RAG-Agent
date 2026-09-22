package com.example.ragagent.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 클라이언트가 보낸 {@code X-Trace-Id} 는 <b>모든 로그 줄</b>의 {@code [%X{traceId}]} 자리에 그대로
 * 들어간다. 게스트에게 열린 배포에서는 누구나 보낼 수 있는 헤더이므로, 줄바꿈으로 가짜 로그 줄을
 * 만들거나 긴 값으로 로그를 부풀리는 것을 막아야 한다.
 */
class TraceIdFilterTest {

    @Test
    @DisplayName("정상 모양(영숫자·하이픈·언더스코어)은 그대로 통과한다")
    void safeValuesPassThrough() {
        assertThat(TraceIdFilter.sanitize("abc123")).isEqualTo("abc123");
        assertThat(TraceIdFilter.sanitize("3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
                .isEqualTo("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
        assertThat(TraceIdFilter.sanitize("job_42-retry")).isEqualTo("job_42-retry");
    }

    @Test
    @DisplayName("줄바꿈이 든 값은 거부된다 — 로그 한 줄을 두 줄로 위조할 수 있다")
    void newlineIsRejected() {
        String forged = "abc\n12:00:00.000 [x] ERROR fake - 관리자가 문서를 삭제함";

        String sanitized = TraceIdFilter.sanitize(forged);

        assertThat(sanitized).doesNotContain("\n").doesNotContain("fake");
        assertThat(sanitized).hasSize(12);   // 새로 발급된 값
    }

    @Test
    @DisplayName("캐리지 리턴·탭·공백도 거부된다")
    void otherControlCharactersAreRejected() {
        for (String bad : new String[]{"abc\r\ndef", "abc\tdef", "abc def", "abc\u0000def"}) {
            assertThat(TraceIdFilter.sanitize(bad)).as("입력 %s", bad).hasSize(12);
        }
    }

    @Test
    @DisplayName("64자를 넘는 값은 거부된다 — 매 로그 줄에 반복되므로 로그 파일이 부푼다")
    void overlongValueIsRejected() {
        assertThat(TraceIdFilter.sanitize("a".repeat(64))).hasSize(64);   // 경계값은 통과
        assertThat(TraceIdFilter.sanitize("a".repeat(65))).hasSize(12);
    }

    @Test
    @DisplayName("헤더가 없거나 비어 있으면 새로 발급한다 (기존 동작)")
    void missingOrBlankGeneratesOne() {
        assertThat(TraceIdFilter.sanitize(null)).hasSize(12);
        assertThat(TraceIdFilter.sanitize("")).hasSize(12);
        assertThat(TraceIdFilter.sanitize("   ")).hasSize(12);
        // 매번 다른 값이어야 요청을 구분할 수 있다.
        assertThat(TraceIdFilter.sanitize(null)).isNotEqualTo(TraceIdFilter.sanitize(null));
    }

    /**
     * SSE 워커가 completeWithError 하면 컨테이너가 같은 요청을 ASYNC 로 다시 디스패치해 예외 핸들러를
     * 부른다. 그 통과에서도 MDC 에 <b>같은</b> id 가 있어야 {@code [RAG-INT-001][null]} 이 아니라 첫
     * 통과의 로그와 묶인다. 헤더가 없는 재디스패치를 흉내 내려면 첫 통과가 남긴 요청 속성만으로 충분해야 한다.
     */
    @Test
    @DisplayName("ASYNC 재디스패치에도 돌고, 첫 통과가 정한 id 를 그대로 다시 쓴다")
    void asyncDispatchReusesTheFirstPassId() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse();

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/ui/chat/stream");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> firstPass = new AtomicReference<>();
        filter.doFilter(req, res, new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse p) {
                firstPass.set(MDC.get("traceId"));
            }
        });
        assertThat(firstPass.get()).hasSize(12);
        assertThat(res.getHeader("X-Trace-Id")).isEqualTo(firstPass.get());
        assertThat(MDC.get("traceId")).as("요청이 끝나면 MDC 는 비운다").isNull();

        // 같은 요청 객체의 재디스패치 — 헤더는 없고 속성만 남아 있다.
        AtomicReference<String> secondPass = new AtomicReference<>();
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse p) {
                secondPass.set(MDC.get("traceId"));
            }
        });
        assertThat(secondPass.get()).isEqualTo(firstPass.get());
    }
}
