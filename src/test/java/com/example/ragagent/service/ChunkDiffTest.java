package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §10.14 신고 검토의 "신고 시점 원문 ↔ 현재 내용" 비교. 순수 클래스라 여기서 규칙 전체를 본다 —
 * 화면에 두었다면 붙일 수 없었을 테스트다(이 프로젝트에는 JS 테스트 하네스가 없다).
 */
class ChunkDiffTest {

    private static String render(ChunkDiff.Result r) {
        return r.lines().stream()
                .map(l -> switch (l.kind()) {
                    case SAME -> "  " + l.text();
                    case ADDED -> "+ " + l.text();
                    case REMOVED -> "- " + l.text();
                })
                .collect(Collectors.joining("\n"));
    }

    private static List<ChunkDiff.Span> spansOf(ChunkDiff.Result r, ChunkDiff.Kind kind) {
        return r.lines().stream()
                .filter(l -> l.kind() == kind)
                .findFirst().orElseThrow()
                .spans();
    }

    /** 바뀐 구간만 이어 붙인다 — "무엇이 강조되는가"를 문자열 하나로 확인하려고. */
    private static String changed(List<ChunkDiff.Span> spans) {
        return spans.stream().filter(ChunkDiff.Span::changed)
                .map(ChunkDiff.Span::text).collect(Collectors.joining("|"));
    }

    // ── 비교할 수 없는 경우 ──────────────────────────────────────────────────

    /**
     * "비교할 것이 없다"와 "차이가 없다"는 다른 말이다. null 을 빈 문자열로 바꿔 비교하면 스냅샷이
     * 없는 신고가 "전부 삭제됨"으로 그려져, 관리자가 멀쩡한 청크를 지운 것으로 읽는다.
     */
    @Test
    @DisplayName("한쪽이 없으면 null — 빈 문자열로 바꿔 비교하지 않는다")
    void missingSideYieldsNoComparison() {
        assertThat(ChunkDiff.compare(null, "본문")).isNull();
        assertThat(ChunkDiff.compare("본문", null)).isNull();
        assertThat(ChunkDiff.compare(null, null)).isNull();
    }

    // ── 동일 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같으면 identical + 모든 줄이 SAME (비교 화면이 본문 읽기로도 쓰인다)")
    void identicalTextsStillRenderEveryLine() {
        ChunkDiff.Result r = ChunkDiff.compare("첫 줄\n둘째 줄", "첫 줄\n둘째 줄");

        assertThat(r.identical()).isTrue();
        assertThat(r.addedLines()).isZero();
        assertThat(r.removedLines()).isZero();
        assertThat(r.lines()).hasSize(2).allMatch(ChunkDiff.Line::same);
        assertThat(r.lines().get(1).oldNo()).isEqualTo(2);
        assertThat(r.lines().get(1).newNo()).isEqualTo(2);
    }

    /**
     * 업로드 문서에서 온 텍스트라 개행 표기가 섞일 수 있다. 그것만으로 "모든 줄이 바뀌었다"가 되면
     * 비교가 통째로 쓸모없어진다.
     */
    @Test
    @DisplayName("개행 표기(CRLF/CR)만 다른 것은 차이가 아니다")
    void newlineStyleIsNotADifference() {
        ChunkDiff.Result r = ChunkDiff.compare("첫 줄\r\n둘째 줄", "첫 줄\n둘째 줄");

        assertThat(r.addedLines()).isZero();
        assertThat(r.removedLines()).isZero();
        assertThat(r.lines()).allMatch(ChunkDiff.Line::same);
    }

    // ── 줄 단위 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("가운데 한 줄만 바뀌면 그 줄만 삭제+추가로 나온다")
    void singleChangedLineInTheMiddle() {
        ChunkDiff.Result r = ChunkDiff.compare(
                "머리말\n기본 포트는 8080입니다\n꼬리말",
                "머리말\n기본 포트는 9090입니다\n꼬리말");

        assertThat(r.identical()).isFalse();
        assertThat(render(r)).isEqualTo("""
                  머리말
                - 기본 포트는 8080입니다
                + 기본 포트는 9090입니다
                  꼬리말""");
        assertThat(r.addedLines()).isEqualTo(1);
        assertThat(r.removedLines()).isEqualTo(1);
    }

    @Test
    @DisplayName("줄 번호는 각자 기준 — 없는 쪽은 null")
    void lineNumbersAreSideLocal() {
        ChunkDiff.Result r = ChunkDiff.compare("A\nB", "A\nX\nB");

        ChunkDiff.Line added = r.lines().stream().filter(ChunkDiff.Line::added).findFirst().orElseThrow();
        assertThat(added.text()).isEqualTo("X");
        assertThat(added.oldNo()).isNull();
        assertThat(added.newNo()).isEqualTo(2);

        ChunkDiff.Line last = r.lines().get(r.lines().size() - 1);
        assertThat(last.text()).isEqualTo("B");
        assertThat(last.oldNo()).isEqualTo(2);
        assertThat(last.newNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("삭제만 / 추가만")
    void pureInsertAndPureDelete() {
        assertThat(render(ChunkDiff.compare("A\nB\nC", "A\nC")))
                .isEqualTo("  A\n- B\n  C");
        assertThat(render(ChunkDiff.compare("A\nC", "A\nB\nC")))
                .isEqualTo("  A\n+ B\n  C");
    }

    // ── 글자 단위 ───────────────────────────────────────────────────────────

    /**
     * 이 화면을 두는 이유 자체다. 청크 본문은 한국어 산문이라 한 문단이 곧 한 줄인 일이 흔해서,
     * 줄 단위로만 보여주면 오타 하나 고친 것도 "문단 통째로 교체"로 보인다.
     */
    @Test
    @DisplayName("바뀐 줄 안에서 실제로 달라진 구간만 강조한다")
    void inlineSpansMarkOnlyTheChangedPart() {
        ChunkDiff.Result r = ChunkDiff.compare("기본 포트는 8080입니다", "기본 포트는 9090입니다");

        assertThat(changed(spansOf(r, ChunkDiff.Kind.REMOVED))).isEqualTo("8080");
        assertThat(changed(spansOf(r, ChunkDiff.Kind.ADDED))).isEqualTo("9090");

        // 구간을 이어 붙이면 원래 줄 그대로 — 렌더가 글자를 잃거나 더하지 않는다.
        assertThat(spansOf(r, ChunkDiff.Kind.ADDED).stream()
                .map(ChunkDiff.Span::text).collect(Collectors.joining()))
                .isEqualTo("기본 포트는 9090입니다");
    }

    /**
     * 완전히 다른 두 문단에 글자 LCS 를 돌리면 조사와 공백만 "같은 부분"으로 남아 색이 흩뿌려지고,
     * 줄 단위 표시보다 오히려 읽기 어려워진다 — 재인덱싱으로 청크 경계가 바뀐 경우가 이 모양이다.
     */
    @Test
    @DisplayName("서로 닮지 않은 두 줄은 글자 단위로 칠하지 않는다")
    void unrelatedLinesGetNoInlineHighlight() {
        ChunkDiff.Result r = ChunkDiff.compare(
                "배포는 ArgoCD 가 자동으로 반영합니다",
                "회의록: 4분기 예산안 검토 및 승인");

        assertThat(spansOf(r, ChunkDiff.Kind.REMOVED)).isEmpty();
        assertThat(spansOf(r, ChunkDiff.Kind.ADDED)).isEmpty();
    }

    @Test
    @DisplayName("짝이 남는 줄은 줄 단위 표시로 남는다")
    void unpairedLinesKeepLineLevelOnly() {
        // 삭제 2줄 : 추가 1줄 — 앞의 한 쌍만 글자 단위로 짝지어진다.
        ChunkDiff.Result r = ChunkDiff.compare("포트는 8080입니다\n버려질 줄\n꼬리말",
                                               "포트는 9090입니다\n꼬리말");

        List<ChunkDiff.Line> removed = r.lines().stream().filter(ChunkDiff.Line::removed).toList();
        assertThat(removed).hasSize(2);
        assertThat(changed(removed.get(0).spans())).isEqualTo("8080");
        assertThat(removed.get(1).spans()).isEmpty();
    }

    // ── 상한 ────────────────────────────────────────────────────────────────

    /**
     * 비교 대상의 크기는 문서에서 온다. 상한을 넘으면 관리자 요청 하나가 수백 MB 를 잡는 대신
     * 비교를 포기하고 그렇게 말한다 — 화면은 나란히 보기로 떨어진다.
     */
    @Test
    @DisplayName("너무 크면 비교를 포기하고 tooLarge 로 말한다 (조용히 '차이 없음'이 되지 않는다)")
    void oversizedInputIsRefusedNotFaked() {
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            a.append("a").append(i).append('\n');
            b.append("b").append(i).append('\n');
        }
        ChunkDiff.Result r = ChunkDiff.compare(a.toString(), b.toString());

        assertThat(r.tooLarge()).isTrue();
        assertThat(r.identical()).isFalse();
        assertThat(r.lines()).isEmpty();
    }

    /** 앞뒤로 같은 줄을 먼저 걷어내므로, 긴 문서라도 실제 편집이 좁으면 상한에 닿지 않는다. */
    @Test
    @DisplayName("공통 머리·꼬리를 걷어내 큰 문서도 비교한다")
    void commonPrefixAndSuffixKeepLargeDocumentsComparable() {
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < 600; i++) head.append("줄 ").append(i).append('\n');

        ChunkDiff.Result r = ChunkDiff.compare(head + "포트 8080", head + "포트 9090");

        assertThat(r.tooLarge()).isFalse();
        assertThat(r.addedLines()).isEqualTo(1);
        assertThat(r.removedLines()).isEqualTo(1);
        assertThat(changed(spansOf(r, ChunkDiff.Kind.ADDED))).isEqualTo("9090");
    }
}
