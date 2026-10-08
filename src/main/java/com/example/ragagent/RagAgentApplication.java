package com.example.ragagent;

import com.example.ragagent.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} 은 {@code CuratedImageStore.sweepOrphans()} 하나를 위해 켠다 — 인증 없이
 * 부를 수 있는 업로드가 남긴 초안 이미지를 기동 때만이 아니라 주기적으로도 치워야 하기 때문이다.
 * 다른 {@code @Scheduled} 빈은 없다({@code StreamingAgentService}·{@code IndexingProgressService} 의
 * 하트비트는 각자 자기 {@code ScheduledExecutorService} 를 들고 있고, 그 스케줄러에 무엇을 얹어도
 * 되는지에 대한 제약이 그쪽 주석에 따로 있으므로 여기로 옮기지 않는다).
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class RagAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagAgentApplication.class, args);
    }
}
