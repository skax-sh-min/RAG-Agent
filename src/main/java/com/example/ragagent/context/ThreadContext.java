package com.example.ragagent.context;

import java.util.Locale;

/**
 * @param admin 이 요청의 인증이 {@code ROLE_ADMIN} 인가 — <b>요청 스레드에서 구한다</b>. 채팅 답변은 SSE 워커(가상 스레드)에서 돌아 거기에는
 *              {@code SecurityContext} 가 없다. plain no-auth 의 채팅 요청은 늘 게스트라 {@code false} 다(관리자 신원은 {@code /admin/**} 에만 주입된다).
 */
public record ThreadContext(String threadId, String userId, Locale locale, Boolean admin) {
    /**
     * {@code admin} 은 기본형이 아니라 {@link Boolean} 이다 — 리졸버가 지원하지 않는 경로(슬라이스 테스트 등)에서는 Spring 이 이 레코드를 요청
     * 파라미터로 바인딩하는데, 파라미터가 없을 때 기본형은 바인딩 오류(500)가 되고 객체 타입은 {@code null} 이 된다. {@code null} 은 여기서 {@code false} 로
     * 정규화하므로 {@link #admin()} 은 {@code null} 을 돌려주지 않는다.
     */
    public ThreadContext {
        if (admin == null) admin = false;
    }

    // 생성자를 하나만 둔다 — 컴포넌트가 늘어난 레코드에 편의 생성자를 더하면 Spring 의 데이터 바인더가 어느 생성자를 쓸지 고르지 못해
    // @ModelAttribute 로 바인딩되는 모든 컨트롤러 테스트가 500 이 된다(리졸버가 지원하지 않는 요청 경로에서 실제로 그렇게 깨졌다).


    public static ThreadContext anonymous(String threadId) {
        return new ThreadContext(threadId, "anonymous", Locale.KOREAN, false);
    }

    public boolean isAuthenticated() {
        return !"anonymous".equals(userId);
    }
}
