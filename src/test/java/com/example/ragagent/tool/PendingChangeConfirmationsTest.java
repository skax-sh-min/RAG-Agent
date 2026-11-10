package com.example.ragagent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 채팅의 유지보수 되묻기 — 결정적인 해소 규칙(LLM 이 "네"를 해석하지 않는다). */
class PendingChangeConfirmationsTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private PendingChangeConfirmations store;

    private static final PendingChangeConfirmations.Pending P = new PendingChangeConfirmations.Pending("A 를 B 로", "A", "B");

    @BeforeEach
    void setUp() {
        store = new PendingChangeConfirmations(now::get);
    }

    @Test
    @DisplayName("긍정 표현과 정확히 같은 다음 메시지만 확정하고, 확정은 한 번만 쓰인다")
    void affirmativeConfirmsOnceAndOnlyOnce() {
        store.put("u1", "t1", P);

        assertThat(store.resolve("u1", "t1", "네")).contains(P);
        assertThat(store.resolve("u1", "t1", "네")).isEmpty();      // 이미 꺼냈다
        assertThat(store.isPending("u1", "t1")).isFalse();
    }

    @Test
    @DisplayName("긍정이 아닌 어떤 말이든 취소다 — 대기는 지워지고 확정되지 않는다(\"네 근데 …\"처럼 섞인 말도)")
    void anythingElseCancels() {
        for (String other : new String[]{"아니요", "취소", "네, 그런데 B 가 아니라 C 로 바꿔 줘", "네 근데 잠깐", "다른 질문인데요", ""}) {
            store.put("u1", "t1", P);
            assertThat(store.resolve("u1", "t1", other)).as(other).isEmpty();
            assertThat(store.isPending("u1", "t1")).as("취소 뒤 " + other).isFalse();
        }
    }

    @Test
    @DisplayName("긍정 판정은 전체 일치 — 앞뒤 공백·끝 문장부호·대소문자는 무시하고 일부 포함은 인정하지 않는다")
    void affirmativeMatchingIsExact() {
        for (String yes : new String[]{"네", " 네 ", "네.", "네!", "네~", "넵", "응", "좋아요", "진행해 주세요", "만들어줘", "ㅇㅇ", "OK", "Yes", "yes!"}) {
            assertThat(PendingChangeConfirmations.isAffirmative(yes)).as(yes).isTrue();
        }
        for (String no : new String[]{"네 그런데", "아니요", "no", "네네 진행", "만들지 마", "진행하지 마세요", "yes please do not", null, "   "}) {
            assertThat(PendingChangeConfirmations.isAffirmative(no)).as(String.valueOf(no)).isFalse();
        }
    }

    @Test
    @DisplayName("대기는 (사용자, 대화) 단위다 — 다른 대화·다른 사용자의 \"네\" 는 확정하지도 지우지도 못한다")
    void pendingIsPerUserAndThread() {
        store.put("u1", "t1", P);

        assertThat(store.resolve("u1", "t2", "네")).isEmpty();
        assertThat(store.resolve("u2", "t1", "네")).isEmpty();
        assertThat(store.isPending("u1", "t1")).as("남의 \"네\" 가 대기를 지우지 않는다").isTrue();
        assertThat(store.resolve("u1", "t1", "네")).contains(P);
    }

    @Test
    @DisplayName("새 되묻기는 같은 (사용자, 대화) 의 이전 대기를 덮어쓴다")
    void newAskReplacesTheOldOne() {
        store.put("u1", "t1", P);
        PendingChangeConfirmations.Pending second = new PendingChangeConfirmations.Pending("C 를 D 로", "C", "D");
        store.put("u1", "t1", second);

        assertThat(store.resolve("u1", "t1", "네")).contains(second);
    }

    @Test
    @DisplayName("5분이 지나면 \"네\" 라고 해도 확정되지 않는다")
    void expiredPendingNeverConfirms() {
        store.put("u1", "t1", P);
        now.addAndGet(PendingChangeConfirmations.TTL_MINUTES * 60_000L + 1);

        assertThat(store.isPending("u1", "t1")).isFalse();
        assertThat(store.resolve("u1", "t1", "네")).isEmpty();
    }

    @Test
    @DisplayName("대기가 없으면 \"네\" 는 아무 일도 아니다")
    void nothingPendingNothingHappens() {
        assertThat(store.resolve("u1", "t1", "네")).isEmpty();
    }

    @Test
    @DisplayName("상한이 차면(만료분을 치운 뒤에도) 새 되묻기는 기록하지 않는다 — 확정이 없으니 시작도 없다")
    void aFullStoreRefusesToRecord() {
        for (int i = 0; i < PendingChangeConfirmations.MAX_ENTRIES; i++) store.put("u" + i, "t", P);

        store.put("overflow", "t", P);

        assertThat(store.resolve("overflow", "t", "네")).isEmpty();
        now.addAndGet(PendingChangeConfirmations.TTL_MINUTES * 60_000L + 1);   // 만료되면 다시 받는다
        store.put("later", "t", P);
        assertThat(store.resolve("later", "t", "네")).contains(P);
    }
}
