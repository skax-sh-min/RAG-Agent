package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 이미지 설명 — 형식 + 한두 문장 프롬프트와 응답 정리. */
class VisionCaptionTest {

    @Test
    @DisplayName("프롬프트: 형식 어휘 · 한두 문장 · 일반 서술 금지 · 구조를 깨는 문자 금지를 담는다")
    void promptStatesTheContract() {
        String p = VisionDescriptionService.CAPTION_PROMPT;

        assertThat(p).contains("<형식>: <설명>")
                .contains("표", "다이어그램", "순서도", "IDE 화면", "소스코드", "로그", "풍경사진", "인물사진")
                .contains("한두 문장")
                .contains("소프트웨어 개발 환경")      // 금지 예시로 실제 코퍼스의 상투구를 든다
                .contains("대괄호와 세로선");
    }

    @Test
    @DisplayName("응답 정리: 첫 줄만, 따옴표·글머리·서두를 벗기고 공백을 접는다")
    void cleansTheResponse() {
        assertThat(VisionDescriptionService.cleanCaption("\"순서도: 로그인 요청 → 세션 생성\"\n다른 줄"))
                .isEqualTo("순서도: 로그인 요청 → 세션 생성");
        assertThat(VisionDescriptionService.cleanCaption("- IDE 화면:   UserService.java  12행"))
                .isEqualTo("IDE 화면: UserService.java 12행");
        assertThat(VisionDescriptionService.cleanCaption("이 이미지는 소프트웨어 개발 환경을 보여줍니다"))
                .isEqualTo("소프트웨어 개발 환경을 보여줍니다");
        assertThat(VisionDescriptionService.cleanCaption("\n\n  ")).isEmpty();
        assertThat(VisionDescriptionService.cleanCaption(null)).isEmpty();
    }

    @Test
    @DisplayName("응답 정리: 설명 마커와 표를 깨는 [ ] | 는 바꾼다")
    void removesStructureBreakingCharacters() {
        assertThat(VisionDescriptionService.cleanCaption("표: 열 [구분] | [URL]")).isEqualTo("표: 열 (구분) / (URL)");
    }

    @Test
    @DisplayName("응답 정리: 300자를 넘으면 마지막 공백에서 자르고 말줄임표")
    void capsTheLength() {
        String out = VisionDescriptionService.cleanCaption("로그: " + "오류 ".repeat(200));

        assertThat(out.length()).isLessThanOrEqualTo(VisionDescriptionService.MAX_CAPTION_CHARS + 1);
        assertThat(out).endsWith("…");
    }

    @Test
    @DisplayName("자리표시자(실패) 판별: 성공한 설명은 [ 로 시작할 수 없으므로 구분된다")
    void placeholderDetection() {
        assertThat(VisionDescriptionService.isPlaceholder("[이미지 설명 불가: Vision 프로바이더 미등록]")).isTrue();
        assertThat(VisionDescriptionService.isPlaceholder("[이미지 설명 생성 오류]")).isTrue();
        assertThat(VisionDescriptionService.isPlaceholder("")).isTrue();
        assertThat(VisionDescriptionService.isPlaceholder(null)).isTrue();
        assertThat(VisionDescriptionService.isPlaceholder(VisionDescriptionService.cleanCaption("[이미지 설명: 표]"))).isFalse();
    }
}
