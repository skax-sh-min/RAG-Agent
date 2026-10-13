package com.example.ragagent;

import com.example.ragagent.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} 의 {@code @Scheduled} 빈은 둘이다: {@code CuratedImageStore.sweepOrphans()} — 인증 없이
 * 부를 수 있는 업로드가 남긴 초안 이미지를 기동 때만이 아니라 주기적으로도 치운다 — 와
 * {@code DatabaseBackupService.scheduled()} — SQLite DB 의 정기 백업(기본 매일 03:30). 기본 스케줄러는 스레드 하나라
 * 둘이 겹치면 줄을 서지만, 둘 다 짧다(백업은 별도 연결의 {@code VACUUM INTO} 라 수십 MB 에 1초 안쪽).
 * 그 밖의 {@code @Scheduled} 빈은 없다({@code StreamingAgentService}·{@code IndexingProgressService} 의
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
