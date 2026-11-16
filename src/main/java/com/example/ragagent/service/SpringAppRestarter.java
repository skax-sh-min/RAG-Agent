package com.example.ragagent.service;

import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@link AppRestarter} 의 실제 구현 — {@link SpringApplication#exit} 로 컨텍스트를 정상 종료(진행 중 요청 마무리·풀 닫기)한 뒤
 * 그 종료 코드로 {@code System.exit} 한다. 다시 띄우는 것은 이 클래스가 아니라 감독 스크립트(종료 코드 42 를 보고 jar 를 바꿔 재시작)다.
 */
@Component
public class SpringAppRestarter implements AppRestarter {

    private static final Logger log = LoggerFactory.getLogger(SpringAppRestarter.class);
    /** 호출한 요청의 응답(진행 상태 JSON)이 클라이언트에 닿을 시간. */
    static final long GRACE_MILLIS = 1_500;

    private final ApplicationContext context;

    public SpringAppRestarter(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void restart(int exitCode) {
        Thread.ofVirtual().name("update-restart").start(MdcPropagation.wrap(() -> {
            try {
                Thread.sleep(GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.warn("[UPDATE-APPLY] 업데이트를 위해 종료 코드 {} 로 앱을 끝낸다 — 감독 스크립트가 jar 를 바꿔 다시 띄운다", exitCode);
            int code = SpringApplication.exit(context, () -> exitCode);
            System.exit(code);
        }));
    }
}
