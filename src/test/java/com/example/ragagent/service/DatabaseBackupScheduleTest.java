package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 정기 백업의 cron 은 {@code app.time-zone} 의 시계로 풀린다 — {@code @Scheduled(zone = "${app.time-zone:…}")} 의 자리표시자를
 * 스프링이 실제로 풀어 만든 트리거를 본다. 시간대를 적지 않은 cron 은 스케줄러 시계의 시간대(JVM 기본값)로 풀리므로, 트리거에
 * UTC 시계를 주고 설정한 시간대의 03:30 이 나오는지 확인한다(TZ 설정 없는 컨테이너에서 "03:30" 이 KST 낮 12:30 이던 자리).
 * 반시간 오프셋인 시간대를 고른 이유는 어느 JVM 의 기본값과도 우연히 같지 않게 하려는 것이다.
 */
@SpringJUnitConfig(DatabaseBackupScheduleTest.Config.class)
@TestPropertySource(properties = {"app.time-zone=America/St_Johns", "app.backup.cron=0 30 3 * * *"})
@ResourceLock("global-state")
class DatabaseBackupScheduleTest {

    @Configuration
    @EnableScheduling
    @Import(DatabaseBackupService.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    @Autowired
    ScheduledTaskHolder scheduler;

    @Test
    @DisplayName("cron 은 app.time-zone 기준 — 03:30 은 그 시간대의 03:30 이다(스케줄러·JVM 시계의 시간대가 아니다)")
    void cronResolvesOnTheConfiguredClock() {
        CronTask task = scheduler.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .findFirst()
                .orElseThrow();
        Instant from = Instant.parse("2026-10-14T00:00:00Z");

        Instant next = task.getTrigger().nextExecution(new SimpleTriggerContext(Clock.fixed(from, ZoneOffset.UTC)));

        assertThat(next).isAfter(from);
        assertThat(next.atZone(ZoneId.of("America/St_Johns")).toLocalTime()).isEqualTo(LocalTime.of(3, 30));
    }
}
