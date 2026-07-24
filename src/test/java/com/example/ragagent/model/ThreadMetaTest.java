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
}
