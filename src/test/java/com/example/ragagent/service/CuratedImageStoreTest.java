package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA — 지식 제안 본문 이미지의 순수 로직(마커 추출 / Vision 설명 주입 / 개수 상한).
 *
 * <p>업로드·삭제·Vision 호출은 파일시스템과 LLM에 의존하므로 여기서 다루지 않는다. 여기서 지키는
 * 계약은 "마커의 위치가 곧 이미지의 위치"라는 것 하나다 — 설명이 주입되어도 마커는 자기 자리에
 * 그대로 남아야 하고, 그래야 승인 시 본문을 청크로 나눌 때 이미지가 자기 문단을 따라간다.
 */
class CuratedImageStoreTest {

    private static final String IMG = "images/submissions/abc1234567890def.png";

    // ── 마커 추출 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("마커 추출 — 문서 인덱싱과 같은 [이미지: ...] 형식을 순서대로 뽑는다")
    void extracts_markers_in_order() {
        String body = """
                첫 문단입니다.

                [이미지: images/submissions/aaa.png]

                둘째 문단입니다.

                [이미지(변환불가): images/submissions/bbb.svg]
                """;
        assertThat(CuratedImageStore.markerPaths(body))
                .containsExactly("images/submissions/aaa.png", "images/submissions/bbb.svg");
    }

    @Test
    @DisplayName("마커 추출 — 마커가 없거나 본문이 비면 빈 목록")
    void no_markers_is_empty() {
        assertThat(CuratedImageStore.markerPaths("이미지 없는 본문")).isEmpty();
        assertThat(CuratedImageStore.markerPaths("")).isEmpty();
        assertThat(CuratedImageStore.markerPaths(null)).isEmpty();
    }

    // ── 개수 상한 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("개수 상한 — 상한 초과 본문은 사용자 메시지와 함께 거부된다")
    void rejects_too_many_images() {
        CuratedImageStore store = new CuratedImageStore(null, null, null, Optional.empty());
        StringBuilder body = new StringBuilder();
        for (int i = 0; i <= CuratedImageStore.MAX_IMAGES_PER_SUBMISSION; i++) {
            body.append("[이미지: images/submissions/").append(i).append(".png]\n");
        }
        assertThatThrownBy(() -> store.validateImageCount(body.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(CuratedImageStore.MAX_IMAGES_PER_SUBMISSION));

        // 상한과 같은 개수는 통과한다 (경계 포함)
        String atLimit = "[이미지: images/submissions/x.png]\n"
                .repeat(CuratedImageStore.MAX_IMAGES_PER_SUBMISSION);
        store.validateImageCount(atLimit);
    }

    // ── Vision 설명 주입 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("설명 주입 — 마커는 제자리에 남고 바로 다음 줄에 설명이 붙는다")
    void injects_description_after_marker() {
        String body = "설명 앞 문장.\n\n[이미지: " + IMG + "]\n\n설명 뒤 문장.";
        String out = CuratedImageStore.injectDescriptions(body, Map.of(IMG, "로그인 화면 스크린샷."));

        assertThat(out).isEqualTo(
                "설명 앞 문장.\n\n[이미지: " + IMG + "]\n[이미지 설명: 로그인 화면 스크린샷.]\n\n설명 뒤 문장.");
    }

    @Test
    @DisplayName("설명 주입 — 여러 줄 설명은 한 줄로 눌러 담는다(마커 한 개 = 설명 한 줄)")
    void flattens_multiline_description() {
        String out = CuratedImageStore.injectDescriptions(
                "[이미지: " + IMG + "]", Map.of(IMG, "첫 줄.\n둘째  줄."));
        assertThat(out).isEqualTo("[이미지: " + IMG + "]\n[이미지 설명: 첫 줄. 둘째 줄.]");
    }

    @Test
    @DisplayName("설명 주입 — 표 셀 안에서는 개행 대신 <br>, 행이 두 줄로 갈라지지 않는다")
    void uses_br_inside_table_row() {
        String body = "| 단계 | 화면 |\n|---|---|\n| 1 | [이미지: " + IMG + "] |";
        String out = CuratedImageStore.injectDescriptions(body, Map.of(IMG, "첫 화면"));

        assertThat(out).contains("[이미지: " + IMG + "]<br>[이미지 설명: 첫 화면]");
        // 표는 여전히 3줄이다 — 개행이 들어갔다면 4줄이 되고 행이 깨진다.
        assertThat(out.split("\n")).hasSize(3);
    }

    @Test
    @DisplayName("설명 주입 — 이미 설명이 붙은 마커는 건드리지 않는다(재승인·관리자 편집 대비)")
    void skips_already_described_marker() {
        String body = "[이미지: " + IMG + "]\n[이미지 설명: 손으로 쓴 설명]";
        assertThat(CuratedImageStore.injectDescriptions(body, Map.of(IMG, "새 설명"))).isEqualTo(body);
    }

    @Test
    @DisplayName("설명 주입 — 설명이 없거나 비어 있으면 본문을 그대로 둔다")
    void leaves_body_untouched_without_description() {
        String body = "[이미지: " + IMG + "]";
        assertThat(CuratedImageStore.injectDescriptions(body, Map.of())).isEqualTo(body);
        assertThat(CuratedImageStore.injectDescriptions(body, Map.of(IMG, "   "))).isEqualTo(body);
    }

    @Test
    @DisplayName("설명 주입 — 설명이 있는 마커와 없는 마커가 섞여 있어도 각자 처리된다")
    void handles_mixed_described_and_undescribed() {
        String a = "images/submissions/a.png";
        String b = "images/submissions/b.png";
        String out = CuratedImageStore.injectDescriptions(
                "[이미지: " + a + "]\n\n[이미지: " + b + "]", Map.of(b, "B 설명"));

        assertThat(out).isEqualTo("[이미지: " + a + "]\n\n[이미지: " + b + "]\n[이미지 설명: B 설명]");
        // 주입 후에도 마커 자체는 둘 다 그대로 — 위치가 곧 이미지의 자리다.
        assertThat(CuratedImageStore.markerPaths(out)).containsExactly(a, b);
    }

    @Test
    @DisplayName("설명 주입 — 주입된 본문을 다시 나눠도 마커는 유효한 경로를 유지한다")
    void marker_paths_survive_injection() {
        String out = CuratedImageStore.injectDescriptions(
                "본문\n[이미지: " + IMG + "]\n끝", Map.of(IMG, "설명"));
        assertThat(CuratedImageStore.markerPaths(out)).isEqualTo(List.of(IMG));
    }
}
