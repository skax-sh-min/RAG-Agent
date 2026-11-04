package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 청크 본문의 이미지 마커·설명 편집 — 추가·삭제·교체의 텍스트 규칙. */
class ChunkImageEditorTest {

    private static final String A = "images/chunks/aaaa.png";
    private static final String B = "images/chunks/bbbb.png";

    // ── 읽기 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("마커와 바로 뒤 설명을 순서대로 읽는다 — 일반 줄은 다음 줄, 표 셀은 <br> 뒤")
    void readsMarkersWithTheirDescriptions() {
        String text = "앞\n[이미지: " + A + "]\n[이미지 설명: 순서도: 로그인 흐름]\n뒤\n| 열 | 그림 |\n|---|---|\n| a | [이미지: " + B
                + "]<br>[이미지 설명: 표: 환경별 URL] |";

        List<ChunkImageEditor.Marker> markers = ChunkImageEditor.markers(text);

        assertThat(markers).hasSize(2);
        assertThat(markers.get(0).path()).isEqualTo(A);
        assertThat(markers.get(0).description()).isEqualTo("순서도: 로그인 흐름");
        assertThat(markers.get(1).path()).isEqualTo(B);
        assertThat(markers.get(1).description()).isEqualTo("표: 환경별 URL");
        assertThat(markers.get(1).index()).isEqualTo(1);
    }

    @Test
    @DisplayName("설명이 마커 바로 뒤가 아니면 그 마커의 것이 아니다 · (변환불가) 마커도 마커다")
    void descriptionMustFollowTheMarker() {
        String text = "[이미지: " + A + "]\n\n문단\n[이미지 설명: 떨어진 설명]\n[이미지(변환불가): images/x/y.emf]";

        List<ChunkImageEditor.Marker> markers = ChunkImageEditor.markers(text);

        assertThat(markers).hasSize(2);
        assertThat(markers.get(0).hasDescription()).isFalse();
        assertThat(markers.get(1).unconvertible()).isTrue();
        assertThat(ChunkImageEditor.markers("")).isEmpty();
    }

    // ── 삭제 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("삭제: 마커가 한 줄을 혼자 차지하면 설명과 함께 줄이 통째로 사라지고 빈 줄이 겹치지 않는다")
    void removeDropsTheWholeLines() {
        String text = "앞 문단\n\n[이미지: " + A + "]\n[이미지 설명: 순서도: 흐름]\n\n뒤 문단";

        assertThat(ChunkImageEditor.remove(text, 0)).isEqualTo("앞 문단\n\n뒤 문단");
    }

    @Test
    @DisplayName("삭제: 문서 끝의 마커도 깔끔하게 — 남는 줄바꿈이 없다")
    void removeAtTheEnd() {
        assertThat(ChunkImageEditor.remove("본문\n[이미지: " + A + "]", 0)).isEqualTo("본문");
        assertThat(ChunkImageEditor.remove("[이미지: " + A + "]", 0)).isEmpty();
    }

    @Test
    @DisplayName("삭제: 여러 개 중 순번으로 고른 하나만 지운다")
    void removeByIndex() {
        String text = "[이미지: " + A + "]\n\n중간\n\n[이미지: " + B + "]\n[이미지 설명: 표: 값]";

        String out = ChunkImageEditor.remove(text, 1);

        assertThat(out).isEqualTo("[이미지: " + A + "]\n\n중간\n");
        assertThat(ChunkImageEditor.paths(out)).containsExactly(A);
    }

    @Test
    @DisplayName("삭제: 표 셀 안의 마커는 설명(<br>)과 함께 빠지고, 매달린 <br> 이나 행 구조가 남지 않는다")
    void removeInsideTableCell() {
        String text = "| 구분 | 그림 |\n|---|---|\n| A | 설명<br>[이미지: " + A + "]<br>[이미지 설명: 표: 값] |";

        String out = ChunkImageEditor.remove(text, 0);

        assertThat(out).isEqualTo("| 구분 | 그림 |\n|---|---|\n| A | 설명 |");
    }

    @Test
    @DisplayName("삭제: 같은 줄에 글이 있으면 줄은 남긴다")
    void removeKeepsLineWithOtherText() {
        assertThat(ChunkImageEditor.remove("그림 [이미지: " + A + "] 참고", 0)).isEqualTo("그림  참고");
    }

    // ── 교체 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("교체: 경로만 바뀌고 옛 설명은 지워진다 — 새 그림 옆에 옛 그림의 설명이 남지 않게")
    void replaceDropsTheStaleDescription() {
        String text = "앞\n[이미지: " + A + "]\n[이미지 설명: 순서도: 옛 흐름]\n뒤";

        assertThat(ChunkImageEditor.replace(text, 0, B)).isEqualTo("앞\n[이미지: " + B + "]\n뒤");
    }

    @Test
    @DisplayName("교체: (변환불가) 표시는 떼고, 다른 마커는 건드리지 않는다")
    void replaceLeavesOthersAlone() {
        String text = "[이미지(변환불가): images/x/y.emf]\n[이미지: " + A + "]";

        assertThat(ChunkImageEditor.replace(text, 0, B)).isEqualTo("[이미지: " + B + "]\n[이미지: " + A + "]");
    }

    // ── 추가 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("추가: 커서가 문장 한가운데여도 마커는 자기 줄에, 앞뒤로 빈 줄을 두고 들어간다")
    void insertGetsItsOwnLine() {
        assertThat(ChunkImageEditor.insert("앞뒤", 1, A)).isEqualTo("앞\n\n[이미지: " + A + "]\n\n뒤");
        assertThat(ChunkImageEditor.insert("앞\n\n뒤", 3, A)).isEqualTo("앞\n\n[이미지: " + A + "]\n\n뒤");
        assertThat(ChunkImageEditor.insert("", 0, A)).isEqualTo("[이미지: " + A + "]");
        assertThat(ChunkImageEditor.insert("본문", 999, A)).isEqualTo("본문\n\n[이미지: " + A + "]");
    }

    // ── 설명 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("설명 붙이기: 없으면 마커 뒤에 한 줄, 표 셀 안이면 <br>, 있으면 그 자리를 바꾼다")
    void setDescription() {
        assertThat(ChunkImageEditor.setDescription("[이미지: " + A + "]\n뒤", 0, "순서도:  흐름\n설명"))
                .isEqualTo("[이미지: " + A + "]\n[이미지 설명: 순서도: 흐름 설명]\n뒤");
        assertThat(ChunkImageEditor.setDescription("| a | [이미지: " + A + "] |", 0, "표: 값"))
                .isEqualTo("| a | [이미지: " + A + "]<br>[이미지 설명: 표: 값] |");
        assertThat(ChunkImageEditor.setDescription("[이미지: " + A + "]\n[이미지 설명: 옛]\n뒤", 0, "새"))
                .isEqualTo("[이미지: " + A + "]\n[이미지 설명: 새]\n뒤");
        assertThat(ChunkImageEditor.setDescription("[이미지: " + A + "]\n[이미지 설명: 옛]\n뒤", 0, " "))
                .isEqualTo("[이미지: " + A + "]\n뒤");
    }

    // ── 경로·오류 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("paths(): 중복 없이 나온 순서대로")
    void pathsAreDistinctAndOrdered() {
        assertThat(ChunkImageEditor.paths("[이미지: " + A + "] [이미지: " + B + "] [이미지: " + A + "]"))
                .containsExactly(A, B);
    }

    @Test
    @DisplayName("없는 순번은 IllegalArgumentException — 본문에 있는 개수를 알려 준다")
    void unknownIndexIsRejected() {
        assertThatThrownBy(() -> ChunkImageEditor.remove("[이미지: " + A + "]", 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("본문에는 1개");
        assertThatThrownBy(() -> ChunkImageEditor.replace("본문", 0, A))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
