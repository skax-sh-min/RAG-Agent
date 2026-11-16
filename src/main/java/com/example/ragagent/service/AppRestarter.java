package com.example.ragagent.service;

/**
 * 앱을 정해진 종료 코드로 끝내 감독 스크립트가 다시 띄우게 한다. {@link UpdateApplyService} 만 쓴다.
 *
 * <p>인터페이스로 둔 이유는 테스트가 진짜 {@code System.exit} 를 부르지 않게 하려는 것이다 — 단위 테스트에서는 호출만 기록하는
 * 가짜를 넣는다.
 */
public interface AppRestarter {

    /**
     * 잠시 뒤(응답이 나갈 시간을 준다) 스프링 컨텍스트를 닫고 {@code exitCode} 로 JVM 을 끝낸다. 호출한 스레드는 기다리지 않는다.
     */
    void restart(int exitCode);
}
