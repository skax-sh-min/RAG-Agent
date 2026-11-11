package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ServerModelResolverTest {

    @Test
    @DisplayName("설정한 이름이 서버 id 에 대소문자 무시로 포함되면 그 서버 id 를 쓰고, 한 번만 묻는다")
    void resolvesToTheServerIdOnce() {
        AtomicInteger asks = new AtomicInteger();
        var r = new ServerModelResolver("http://a", "BGE-M3", () -> {
            asks.incrementAndGet();
            return List.of("text-embedding-bge-m3-q8_0", "other");
        });

        assertThat(r.modelName()).isEqualTo("text-embedding-bge-m3-q8_0");
        assertThat(r.modelName()).isEqualTo("text-embedding-bge-m3-q8_0");
        assertThat(asks.get()).as("풀린 뒤에는 다시 묻지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("못 풀면(없음 · 모호함 · 서버 오류) 설정한 이름 그대로, 30초 안에는 다시 묻지 않고 지나면 다시 푼다")
    void fallsBackToTheConfiguredNameAndRetriesLater() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        AtomicInteger asks = new AtomicInteger();
        List<List<String>> answers = List.of(List.of("a", "b"), List.of("m-one", "m-two"), List.of("xx-m-yy"));
        var r = new ServerModelResolver("http://a", "m", () -> {
            int i = asks.getAndIncrement();
            if (i == 0) throw new IllegalStateException("down");
            return answers.get(i);   // i=1: 모호 한 쪽은 'm' 이 둘에 포함, i=2: 하나
        }, now::get);

        assertThat(r.modelName()).as("서버 오류").isEqualTo("m");
        assertThat(r.modelName()).as("30초 안").isEqualTo("m");
        assertThat(asks.get()).isEqualTo(1);

        now.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertThat(r.modelName()).as("모호함").isEqualTo("m");

        now.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertThat(r.modelName()).as("이번엔 하나만 맞는다").isEqualTo("xx-m-yy");
    }
}
