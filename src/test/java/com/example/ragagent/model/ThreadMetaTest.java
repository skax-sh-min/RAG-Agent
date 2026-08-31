package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — ThreadMeta.displayTitle() / tagsDisplay() (사이드바 스레드 목록 표시용 파생 값).
 */
class ThreadMetaTest {

    private static ThreadMeta meta(String title, String tags) {
        return new ThreadMeta("t1", "u1", title, "latest", "now", "now", "COST_FIRST", tags);
    }

    @Test
    @DisplayName("displayTitle — 선행 [버전] 브래킷 제거")
    void displayTitle_stripsLeadingVersionBracket() {
        assertThat(meta("[latest] 문서 요약 질문", "").displayTitle()).isEqualTo("문서 요약 질문");
    }

    @Test
    @DisplayName("displayTitle — 브래킷 없는 제목은 그대로")
    void displayTitle_noBracket_returnsAsIs() {
        assertThat(meta("이미 지정된 제목", "").displayTitle()).isEqualTo("이미 지정된 제목");
    }

    @Test
    @DisplayName("displayTitle — version 필드와 무관하게 선행 [..] 패턴이면 무엇이든 제거")
    void displayTitle_matchesAnyBracketContent_notJustVersionField() {
        assertThat(meta("[v1.2+beta] 제목", "").displayTitle()).isEqualTo("제목");
    }

    @Test
    @DisplayName("tagsDisplay — CSV를 쉼표+공백으로 재조인")
    void tagsDisplay_joinsCsvWithSpacing() {
        assertThat(meta("t", "billing,policy").tagsDisplay()).isEqualTo("billing, policy");
    }

    @Test
    @DisplayName("tagsDisplay — 태그 없으면 빈 문자열")
    void tagsDisplay_noTags_returnsEmpty() {
        assertThat(meta("t", "").tagsDisplay()).isEmpty();
        assertThat(meta("t", null).tagsDisplay()).isEmpty();
    }

    // ── shortThreadId — 채팅 사이드바의 축약 id ────────────────────────────────

    private static ThreadMeta withId(String threadId) {
        return new ThreadMeta(threadId, "u1", "제목", "latest", "now", "now", "COST_FIRST", "");
    }

    @Test
    @DisplayName("shortThreadId — UUID는 앞 8자만")
    void shortThreadId_uuid_isTruncated() {
        assertThat(withId("3608dfb5-4aca-4e9a-805d-69c635b0b422").shortThreadId())
                .isEqualTo("3608dfb5");
    }

    /**
     * 8자보다 짧은 id를 자르려다 채팅 페이지 전체가 500으로 죽었다 — 템플릿이
     * {@code #strings.substring(meta.threadId, 0, 8)}을 직접 호출하고 있었고, 실배포에
     * {@code "default"}(7자)라는 레거시 스레드가 있어 {@code /chat/default}가 열리지 않았다.
     */
    @Test
    @DisplayName("shortThreadId — 8자 이하 id는 그대로 (예외 대신)")
    void shortThreadId_shorterThanLimit_returnedWhole() {
        assertThat(withId("default").shortThreadId()).isEqualTo("default");   // 7자 — 실제 사고 케이스
        assertThat(withId("12345678").shortThreadId()).isEqualTo("12345678"); // 경계값
        assertThat(withId("a").shortThreadId()).isEqualTo("a");
        assertThat(withId("").shortThreadId()).isEmpty();
    }

    @Test
    @DisplayName("shortThreadId — null id도 화면을 깨뜨리지 않는다")
    void shortThreadId_nullIsSafe() {
        assertThat(withId(null).shortThreadId()).isEmpty();
    }
}
