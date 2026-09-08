package com.example.ragagent.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 두 텍스트의 <b>줄 단위</b> 차이 + 바뀐 줄 안의 <b>낱말 단위</b> 차이. §10.14 청크 오류 신고
 * 검토 화면이 "신고 시점 원문"과 "현재 내용"을 비교하는 데 쓴다.
 *
 * <p><b>왜 줄 안까지 들어가는가.</b> 청크 본문은 한국어 산문이라 한 문단이 곧 한 줄인 일이 흔하다.
 * 줄 단위로만 표시하면 오타 하나 고친 것도 "이 문단 통째로 삭제 + 통째로 추가"로 보여서, 정작
 * 관리자가 알고 싶은 "어디가 달라졌나"를 눈으로 다시 찾아야 한다 — 비교 화면을 두는 이유 자체가
 * 사라진다. 줄 안의 단위는 글자가 아니라 <b>낱말</b>이다({@link #tokenize} 참고).
 *
 * <p><b>왜 서버인가.</b> 화면(admin.html)에 두면 테스트를 붙일 수가 없다(이 프로젝트에는 JS 테스트
 * 하네스가 없다). 신고 검토 프래그먼트는 이미 서버 렌더이고, {@code AdminService.ChunkRow} 의
 * {@code chunk_context} 분해도 같은 이유로 화면에서 서버로 옮겨 온 선례가 있다.
 *
 * <p><b>상한이 있는 이유.</b> LCS 는 O(n×m) 이고 비교 대상의 크기는 문서에서 온다. 정상 범위는
 * 청크 하나(기본 1,500자)지만 설정이 과대하거나 스냅샷과 현재 내용이 전혀 다른 텍스트일 수 있어,
 * 상한을 넘으면 <b>비교를 포기하고 그렇게 말한다</b>(화면은 나란히 보기로 떨어진다) — 관리자 요청
 * 하나가 수백 MB 를 잡는 것보다 낫다.
 */
public final class ChunkDiff {

    /** 줄 단위 DP 상한 — 500×500 줄. 청크 하나는 보통 수십 줄이라 이상값 방어다. */
    private static final long MAX_LINE_MATRIX_CELLS = 250_000L;

    /** 한 쌍의 줄에 낱말 단위 비교를 걸 수 있는 상한(토큰 수 × 토큰 수). */
    private static final long MAX_INLINE_CELLS = 250_000L;

    /** 비교 한 번에 낱말 단위로 쓸 수 있는 총량 — 바뀐 줄이 많을 때 전체 비용을 묶는다. */
    private static final long INLINE_CELL_BUDGET = 1_000_000L;

    /**
     * 이만큼도 안 닮은 두 줄은 낱말 단위로 칠하지 않는다(같은 글자 수 ÷ 긴 쪽 길이). 완전히 다른
     * 문단끼리 비교하면 공백과 짧은 조사만 "같은 부분"으로 남아 색이 흩뿌려지고, 줄 단위 표시보다
     * 오히려 읽기 어려워진다 — 재인덱싱으로 청크 경계가 바뀐 경우가 정확히 이 모양이다.
     */
    private static final double MIN_INLINE_SIMILARITY = 0.4;

    private ChunkDiff() {}

    public enum Kind { SAME, ADDED, REMOVED }

    /** 한 줄 안의 한 구간. {@code changed=true} 인 구간만 강조된다. */
    public record Span(boolean changed, String text) {}

    /**
     * 결과 한 줄. {@code oldNo}/{@code newNo} 는 각각 신고 시점/현재 기준 줄 번호이며, 그쪽에
     * 존재하지 않는 줄에서는 {@code null} 이다.
     *
     * @param spans 줄 안의 낱말 단위 구간. 비어 있으면 줄 전체를 그대로 렌더한다(짝이 없는
     *              줄이거나, 두 줄이 너무 안 닮아 낱말 단위 강조를 포기한 경우).
     */
    public record Line(Kind kind, Integer oldNo, Integer newNo, String text, List<Span> spans) {
        public boolean same()    { return kind == Kind.SAME; }
        public boolean added()   { return kind == Kind.ADDED; }
        public boolean removed() { return kind == Kind.REMOVED; }
    }

    /**
     * @param identical 두 텍스트가 완전히 같다(그때도 {@code lines} 는 전부 SAME 으로 채워져 있어
     *                  비교 화면이 본문 읽기로도 쓰인다)
     * @param tooLarge  상한을 넘어 비교하지 않았다 — {@code lines} 는 비어 있다
     */
    public record Result(List<Line> lines, int addedLines, int removedLines,
                         boolean identical, boolean tooLarge) {}

    /**
     * 줄 단위로 비교하고, 바뀐 줄끼리는 낱말 단위까지 들어간다.
     *
     * @return 한쪽이라도 {@code null} 이면 {@code null} — 비교할 것이 없는 것과 "차이가 없다"는
     *         다른 말이라, 없는 스냅샷을 빈 문자열로 바꿔 "전부 삭제됨"으로 그리지 않는다
     */
    public static Result compare(String before, String after) {
        if (before == null || after == null) return null;

        List<String> a = splitLines(before);
        List<String> b = splitLines(after);
        if (before.equals(after)) {
            List<Line> same = new ArrayList<>(a.size());
            for (int i = 0; i < a.size(); i++) same.add(line(Kind.SAME, i + 1, i + 1, a.get(i)));
            return new Result(List.copyOf(same), 0, 0, true, false);
        }

        // 앞뒤로 같은 줄을 먼저 걷어낸다 — 실제 편집은 대부분 가운데 몇 줄이라, 이것만으로 DP 가
        // 상한에 닿을 일이 거의 없어진다.
        int head = 0;
        while (head < a.size() && head < b.size() && a.get(head).equals(b.get(head))) head++;
        int tail = 0;
        while (tail < a.size() - head && tail < b.size() - head
                && a.get(a.size() - 1 - tail).equals(b.get(b.size() - 1 - tail))) tail++;

        List<String> midA = a.subList(head, a.size() - tail);
        List<String> midB = b.subList(head, b.size() - tail);
        if ((long) midA.size() * midB.size() > MAX_LINE_MATRIX_CELLS) {
            return new Result(List.of(), 0, 0, false, true);
        }

        List<Line> lines = new ArrayList<>(a.size() + b.size());
        for (int i = 0; i < head; i++) lines.add(line(Kind.SAME, i + 1, i + 1, a.get(i)));

        int[][] dp = lcsTable(midA, midB);
        int i = 0, j = 0;
        int oldNo = head, newNo = head;
        while (i < midA.size() && j < midB.size()) {
            if (midA.get(i).equals(midB.get(j))) {
                lines.add(line(Kind.SAME, ++oldNo, ++newNo, midA.get(i)));
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                lines.add(line(Kind.REMOVED, ++oldNo, null, midA.get(i++)));
            } else {
                lines.add(line(Kind.ADDED, null, ++newNo, midB.get(j++)));
            }
        }
        while (i < midA.size())  lines.add(line(Kind.REMOVED, ++oldNo, null, midA.get(i++)));
        while (j < midB.size())  lines.add(line(Kind.ADDED,   null, ++newNo, midB.get(j++)));

        for (int k = 0; k < tail; k++) {
            int idx = a.size() - tail + k;
            lines.add(line(Kind.SAME, ++oldNo, ++newNo, a.get(idx)));
        }

        markInlineChanges(lines);

        int added = 0, removed = 0;
        for (Line l : lines) {
            if (l.added()) added++;
            else if (l.removed()) removed++;
        }
        return new Result(List.copyOf(lines), added, removed, false, false);
    }

    // ── 줄 안의 낱말 단위 ───────────────────────────────────────────────────

    /**
     * 삭제된 줄 뒤에 바로 추가된 줄이 오는 구간을 <b>순서대로 짝지어</b> 낱말 단위 차이를 붙인다.
     * 짝이 남는 쪽(삭제만 3줄, 추가는 1줄 같은 경우)은 줄 단위 표시로 남는다 — 대응이 없는 줄에
     * 억지로 짝을 붙이면 관계없는 두 문단을 비교한 색이 나온다.
     */
    private static void markInlineChanges(List<Line> lines) {
        long budget = INLINE_CELL_BUDGET;
        int idx = 0;
        while (idx < lines.size()) {
            if (!lines.get(idx).removed()) { idx++; continue; }
            int removedStart = idx;
            while (idx < lines.size() && lines.get(idx).removed()) idx++;
            int addedStart = idx;
            while (idx < lines.size() && lines.get(idx).added()) idx++;

            int pairs = Math.min(addedStart - removedStart, idx - addedStart);
            for (int k = 0; k < pairs && budget > 0; k++) {
                Line rem = lines.get(removedStart + k);
                Line add = lines.get(addedStart + k);
                List<String> tx = tokenize(rem.text()), ty = tokenize(add.text());
                long cells = (long) tx.size() * ty.size();
                if (cells > MAX_INLINE_CELLS) continue;
                budget -= cells;
                Span[][] spans = inlineSpans(tx, ty, rem.text(), add.text());
                if (spans == null) continue;
                lines.set(removedStart + k, withSpans(rem, spans[0]));
                lines.set(addedStart + k,   withSpans(add, spans[1]));
            }
        }
    }

    /**
     * @return {@code [before 구간, after 구간]}, 또는 두 줄이 {@link #MIN_INLINE_SIMILARITY} 만큼도
     *         닮지 않았으면 {@code null}
     */
    private static Span[][] inlineSpans(List<String> tx, List<String> ty, String x, String y) {
        if (tx.isEmpty() || ty.isEmpty()) return null;

        int[][] dp = lcsTable(tx, ty);
        List<Span> xs = new ArrayList<>(), ys = new ArrayList<>();
        int i = 0, j = 0, common = 0;
        while (i < tx.size() && j < ty.size()) {
            if (tx.get(i).equals(ty.get(j))) {
                common += tx.get(i).length();
                push(xs, false, tx.get(i++));
                push(ys, false, ty.get(j++));
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                push(xs, true, tx.get(i++));
            } else {
                push(ys, true, ty.get(j++));
            }
        }
        while (i < tx.size()) push(xs, true, tx.get(i++));
        while (j < ty.size()) push(ys, true, ty.get(j++));

        if (common < MIN_INLINE_SIMILARITY * Math.max(x.length(), y.length())) return null;
        return new Span[][] { xs.toArray(new Span[0]), ys.toArray(new Span[0]) };
    }

    /**
     * 한 줄을 <b>낱말</b>로 자른다: 글자 연속, 숫자 연속, 그 밖의 글자 하나.
     *
     * <p><b>글자 단위로 비교하지 않는 이유</b>는 최소 편집이 사람이 읽고 싶은 답이 아니어서다.
     * {@code 8080} → {@code 9090} 을 글자로 비교하면 가운데 {@code 0} 이 "같은 부분"으로 남아
     * 8·8 과 9·9 만 칠해진다 — 편집 거리로는 맞지만 화면에서는 색이 흩뿌려진 숫자가 된다.
     *
     * <p><b>숫자를 글자에서 떼는 이유</b>도 같다. 한국어에서는 {@code 8080입니다} 가 한 낱말이라,
     * 떼지 않으면 포트 번호만 바뀐 줄에서 어미까지 통째로 칠해지고 — 그런 줄은 대개 짧아서 —
     * "안 닮았다"로 판정돼 강조가 통째로 사라진다.
     */
    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int j = i + 1;
            if (Character.isDigit(c))       while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            else if (Character.isLetter(c)) while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
            out.add(s.substring(i, j));
            i = j;
        }
        return out;
    }

    /** 같은 성격의 낱말은 한 구간으로 합친다 — 낱말마다 엘리먼트를 만들면 렌더가 못 쓰게 커진다. */
    private static void push(List<Span> spans, boolean changed, String token) {
        if (!spans.isEmpty()) {
            Span last = spans.get(spans.size() - 1);
            if (last.changed() == changed) {
                spans.set(spans.size() - 1, new Span(changed, last.text() + token));
                return;
            }
        }
        spans.add(new Span(changed, token));
    }

    // ── 도구 ────────────────────────────────────────────────────────────────

    private static int[][] lcsTable(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.get(i).equals(b.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        return dp;
    }

    private static Line line(Kind kind, Integer oldNo, Integer newNo, String text) {
        return new Line(kind, oldNo, newNo, text, List.of());
    }

    private static Line withSpans(Line l, Span[] spans) {
        return new Line(l.kind(), l.oldNo(), l.newNo(), l.text(), List.of(spans));
    }

    /**
     * {@code \r\n}·{@code \r} 도 줄바꿈으로 본다 — 업로드 문서에서 온 텍스트라 개행 표기가 섞일 수
     * 있고, 그것만으로 "모든 줄이 바뀌었다"가 되면 비교가 쓸모없어진다.
     */
    private static List<String> splitLines(String text) {
        return List.of(text.split("\r\n|\r|\n", -1));
    }
}
