package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — §6.25 결정 2: {@code conversation_turns.asked_at} 은 UTC 로 저장되고 화면에는 KST 로 나간다.
 *
 * <p>이 앱은 두 가지 시각 표기를 저장한다 — {@code asked_at} 은 {@code Instant.now()} 를 UTC 로,
 * {@code thread_meta.updated_at} 은 {@code LocalDateTime.now()} 라 시스템 로컬로. 같은 대화에서
 * 두 값이 정확히 9시간 어긋나 보이는 것을 관리자 패널 실데이터로 확인했고
 * ({@code asked_at=04:48:12} vs {@code updated_at=13:44:10}), {@code /admin} 검색 진단 수치 패널은
 * 이 변환이 생기기 전까지 UTC 를 그대로 찍고 있었다.
 *
 * <p>표시용 변환이라 <b>깨진 입력이 화면을 깨뜨리면 안 된다</b> — 파싱 실패는 원본을 그대로 돌려준다.
 */
class KstDateFormatTest {

    @Test
    @DisplayName("UTC 스탬프를 KST(+9)로 옮긴다 — 초까지 유지")
    void convertsUtcStampToKst() {
        assertThat(KstDateFormat.utcStampToKst("2026-08-27 03:00:00"))
                .isEqualTo("2026-08-27 12:00:00");
    }

    @Test
    @DisplayName("날짜 경계를 넘기는 시각도 정확히 넘어간다")
    void crossesTheDateBoundary() {
        assertThat(KstDateFormat.utcStampToKst("2026-08-27 15:30:45"))
                .isEqualTo("2026-08-28 00:30:45");
        assertThat(KstDateFormat.utcStampToKst("2026-12-31 23:00:00"))
                .isEqualTo("2027-01-01 08:00:00");
    }

    /** KST 는 DST 가 없다 — 여름/겨울 어느 쪽이든 오프셋은 +9 로 같아야 한다. */
    @Test
    @DisplayName("계절과 무관하게 +9 — KST 에는 서머타임이 없다")
    void offsetIsConstantAcrossSeasons() {
        assertThat(KstDateFormat.utcStampToKst("2026-01-15 00:00:00"))
                .isEqualTo("2026-01-15 09:00:00");
        assertThat(KstDateFormat.utcStampToKst("2026-07-15 00:00:00"))
                .isEqualTo("2026-07-15 09:00:00");
    }

    @Test
    @DisplayName("null/빈 값/파싱 불가는 입력을 그대로 돌려준다 — 표시가 깨지지 않는다")
    void degradesToTheRawInput() {
        assertThat(KstDateFormat.utcStampToKst(null)).isNull();
        assertThat(KstDateFormat.utcStampToKst("")).isEmpty();
        assertThat(KstDateFormat.utcStampToKst("   ")).isEqualTo("   ");
        assertThat(KstDateFormat.utcStampToKst("어제")).isEqualTo("어제");
        // ISO instant 는 이 메서드의 입력 형식이 아니다 — toKst() 담당이고, 여기선 그대로 통과한다.
        assertThat(KstDateFormat.utcStampToKst("2026-08-27T03:00:00Z"))
                .isEqualTo("2026-08-27T03:00:00Z");
    }

    /**
     * 두 메서드를 갈라 둔 이유가 입력 형식이라, 각자의 형식에서만 동작한다는 것을 함께 고정한다.
     * 하나로 합치면 "존 표기 없는 스탬프는 UTC 로 읽는다"는 규칙이 형식 추측에 묻힌다.
     */
    @Test
    @DisplayName("toKst 는 ISO instant 담당 — 두 변환의 입력 형식이 서로 다르다")
    void toKstHandlesIsoInstantsInstead() {
        assertThat(KstDateFormat.toKst("2026-08-27T03:00:00Z")).isEqualTo("2026-08-27 12:00");
        assertThat(KstDateFormat.toKst("2026-08-27 03:00:00")).isEqualTo("2026-08-27 03:00:00");
    }
}
