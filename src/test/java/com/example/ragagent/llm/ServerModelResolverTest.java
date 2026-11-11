package com.example.ragagent.llm;

import com.example.ragagent.llm.ServerModelResolver.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ServerModelResolverTest {

    @Test
    @DisplayName("설정한 이름이 서버 id 에 대소문자 무시로 포함되면 그 id 를 쓰고(OK), 풀린 뒤에는 다시 묻지 않는다")
    void resolvesToTheServerIdOnce() {
        AtomicInteger asks = new AtomicInteger();
        var r = new ServerModelResolver("http://a", "BGE-M3", () -> {
            asks.incrementAndGet();
            return List.of("text-embedding-bge-m3-q8_0", "other");
        }, System::nanoTime, Runnable::run);

        assertThat(r.state()).isEqualTo(State.OK);
        assertThat(r.modelName()).isEqualTo("text-embedding-bge-m3-q8_0");
        assertThat(r.modelName()).isEqualTo("text-embedding-bge-m3-q8_0");
        assertThat(asks.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("조회는 요청 경로를 막지 않는다 — 실행기가 아직 안 돌렸으면 설정한 이름 그대로, 상태는 UNKNOWN")
    void neverBlocksTheCaller() {
        List<Runnable> queued = new ArrayList<>();
        var r = new ServerModelResolver("http://a", "m", () -> List.of("xx-m"), System::nanoTime, queued::add);

        assertThat(r.modelName()).isEqualTo("m");
        assertThat(r.state()).isEqualTo(State.UNKNOWN);
        assertThat(queued).as("백그라운드 한 건만 예약된다(중복 예약 없음)").hasSize(1);

        queued.get(0).run();
        assertThat(r.state()).isEqualTo(State.OK);
        assertThat(r.modelName()).isEqualTo("xx-m");
    }

    @Test
    @DisplayName("부분 일치가 여럿이어도 일치다 — 설정값에 가장 가까운(가장 짧은) id 를 쓴다")
    void severalPartialMatchesPickTheClosest() {
        var r = new ServerModelResolver("http://a", "bge-m3",
                () -> List.of("text-embedding-bge-m3-q8_0", "bge-m3-q4", "other"), System::nanoTime, Runnable::run);

        assertThat(r.state()).isEqualTo(State.OK);
        assertThat(r.modelName()).isEqualTo("bge-m3-q4");
    }

    @Test
    @DisplayName("서버 오류는 UNREACHABLE(30초 뒤 재시도), 어떤 id 에도 포함되지 않으면 MODEL_MISSING(5분 뒤 재시도)")
    void retryIntervalsDifferByState() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        AtomicInteger asks = new AtomicInteger();
        var r = new ServerModelResolver("http://a", "m", () -> {
            int i = asks.getAndIncrement();
            if (i == 0) throw new IllegalStateException("down");
            if (i == 1) return List.of("alpha", "beta");   // 'm' 이 어느 id 에도 없다
            return List.of("xx-m-yy");
        }, now::get, Runnable::run);

        assertThat(r.state()).as("첫 조회 실패").isEqualTo(State.UNREACHABLE);
        assertThat(r.state()).as("30초 안에는 다시 묻지 않는다").isEqualTo(State.UNREACHABLE);
        assertThat(asks.get()).isEqualTo(1);

        now.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertThat(r.state()).as("모델없음").isEqualTo(State.MODEL_MISSING);
        assertThat(r.detail()).contains("not part of any model id");

        now.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertThat(r.state()).as("모델없음은 30초로는 다시 묻지 않는다").isEqualTo(State.MODEL_MISSING);
        assertThat(asks.get()).isEqualTo(2);

        now.addAndGet(TimeUnit.MINUTES.toNanos(5));
        assertThat(r.state()).as("5분이 지나면 다시 푼다").isEqualTo(State.OK);
        assertThat(r.modelName()).isEqualTo("xx-m-yy");
    }
}
