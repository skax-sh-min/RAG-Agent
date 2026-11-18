package com.example.ragagent.workflow;

import com.example.ragagent.service.MarkdownCorrectionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원문 인용에 고정된 편집(AGENT_MCP_DESIGN §10.4) — 모델(외부 에이전트든 앱 안 워크플로든)은 본문을 다시 쓰지 않고 "이 문장을 바꿔라 · 여기부터 여기까지
 * 지워라 · 이 섹션을 지워라 · 이 문장 뒤에 넣어라"만 낸다. 이 클래스가 인용을 원문에서 찾아 적용하고, 그 과정에서 가드(§10.5)를 건다. 순수 클래스다.
 *
 * <p><b>인용 찾기(G1).</b> 정확히 일치를 먼저 보고, 없으면 공백 정규화(연속 공백·줄바꿈을 한 칸으로) 일치를 원래 위치로 되짚는다. <b>정확히 한 곳</b>이어야
 * 한다 — 여러 곳이면 모델이 무엇을 가리켰는지 코드가 알 수 없다.
 *
 * <p><b>보호 구간(G2).</b> 그림 마커(+ 바로 뒤 설명), {@code [페이지: N]}, 코드 펜스 줄, 표 구분선을 <b>일부만</b> 건드리는 작업은 버린다. 지우기·섹션 지우기가
 * 그것들을 통째로 지우는 것은 허용하고, 그때는 결과의 마커 목록이 "원래 목록 − 지운 구간 안의 마커"와 정확히 같아야 한다(G6).
 *
 * <p><b>하나라도 어기면 그 작업만 버린다.</b> 남은 작업으로 결과를 만들고 버린 작업과 사유를 함께 돌려준다 — 남은 것이 없으면 수정안을 만들지 않는 것은
 * 호출자의 몫이다.
 */
public final class AnchoredEdit {

    /** 한 수정안의 작업 수 상한(G5). */
    public static final int MAX_OPS = 5;
    /** 넣는 블록의 글자 상한(G5). */
    public static final int MAX_INSERT_CHARS = 500;
    /** 바꾸기의 새 구간 글자 상한(G5). */
    public static final int MAX_REPLACE_CHARS = 2_000;

    public enum Op { REPLACE, DELETE, DELETE_SECTION, INSERT_AFTER }

    /**
     * 편집 작업 하나.
     *
     * @param anchor    {@code REPLACE}: 바꿀 구간 전체 · {@code DELETE}: 지울 구간의 앞부분 · {@code DELETE_SECTION}: 제목 줄 · {@code INSERT_AFTER}: 이 문장 뒤
     * @param endAnchor {@code DELETE} 의 뒷부분(없으면 {@code anchor} 만 지운다), 그 밖에는 {@code null}
     * @param text      {@code REPLACE} 의 새 구간 · {@code INSERT_AFTER} 의 넣을 블록, 그 밖에는 {@code null}
     */
    public record Operation(Op op, String anchor, String endAnchor, String text) {
        public static Operation replace(String original, String replacement) { return new Operation(Op.REPLACE, original, null, replacement); }
        public static Operation delete(String start, String end) { return new Operation(Op.DELETE, start, end, null); }
        public static Operation deleteSection(String heading) { return new Operation(Op.DELETE_SECTION, heading, null, null); }
        public static Operation insertAfter(String anchor, String block) { return new Operation(Op.INSERT_AFTER, anchor, null, block); }
    }

    /** 버린 작업 — 몇 번째(0부터)였고 왜 버렸나. */
    public record Rejected(int index, Operation operation, String reason) {}

    /**
     * @param text     남은 작업을 적용한 본문(아무것도 적용되지 않았으면 원문)
     * @param applied  적용된 작업
     * @param rejected 버린 작업과 사유
     * @param problem  작업 전체가 무효인 사유(작업 수 초과·결과 불변식 위반·변경 없음) — 있으면 {@code text} 를 쓰지 않는다
     */
    public record Result(String text, List<Operation> applied, List<Rejected> rejected, String problem) {
        public boolean usable() {
            return problem == null && !applied.isEmpty();
        }
    }

    private record Span(int start, int end) {
        boolean overlaps(Span o) { return start < o.end && o.start < end; }
        boolean contains(Span o) { return start <= o.start && o.end <= end; }
    }

    private record Located(Operation op, int index, Span span, String insert) {}

    private static final Pattern IMAGE_MARKER = Pattern.compile("\\[이미지(?:\\(변환불가\\))?:[^\\]\\n]*](?:\\s*\\[이미지 설명:[^\\]\\n]*])?");
    private static final Pattern PAGE_MARKER = Pattern.compile("\\[페이지:\\s*\\d+]");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+\\S.*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|[\\s:|-]+\\|?\\s*$");

    private AnchoredEdit() {}

    /** 작업들을 원문에 적용한다. */
    public static Result apply(String before, List<Operation> ops) {
        String text = before == null ? "" : before;
        List<Operation> list = ops == null ? List.of() : ops;
        if (list.isEmpty()) return new Result(text, List.of(), List.of(), "편집 작업이 없다");
        if (list.size() > MAX_OPS) return new Result(text, List.of(), List.of(), "편집 작업이 너무 많다(" + MAX_OPS + "개 이하)");

        List<Span> protectedSpans = protectedSpans(text);
        List<Located> located = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Operation op = list.get(i);
            Object r = locate(text, op);
            if (r instanceof String reason) { rejected.add(new Rejected(i, op, reason)); continue; }
            Located l = (Located) r;
            l = new Located(op, i, l.span(), l.insert());
            String guard = guardProtected(l, protectedSpans);
            if (guard != null) { rejected.add(new Rejected(i, op, guard)); continue; }
            located.add(l);
        }
        // 작업끼리 구간이 겹치면 둘 다 버린다 — 어느 쪽이 먼저인지에 따라 결과가 달라진다.
        List<Located> keep = new ArrayList<>();
        for (Located a : located) {
            boolean clash = false;
            for (Located b : located) {
                if (a == b) continue;
                Span sa = a.span(), sb = b.span();
                boolean bothPoints = sa.start == sa.end && sb.start == sb.end && sa.start == sb.start;
                if (sa.overlaps(sb) || bothPoints
                        || (sa.start == sa.end && sb.start < sa.start && sa.start < sb.end)
                        || (sb.start == sb.end && sa.start < sb.start && sb.start < sa.end)) { clash = true; break; }
            }
            if (clash) rejected.add(new Rejected(a.index(), a.op(), "다른 작업과 구간이 겹친다"));
            else keep.add(a);
        }
        if (keep.isEmpty()) return new Result(text, List.of(), sorted(rejected), null);

        keep.sort(Comparator.comparingInt((Located l) -> l.span().start).reversed());
        StringBuilder out = new StringBuilder(text);
        List<Span> deleted = new ArrayList<>();
        for (Located l : keep) {
            out.replace(l.span().start, l.span().end, l.insert());
            if (l.op().op() == Op.DELETE || l.op().op() == Op.DELETE_SECTION) deleted.add(l.span());
        }
        String after = out.toString();
        List<Operation> applied = keep.stream().sorted(Comparator.comparingInt(Located::index)).map(Located::op).toList();

        if (after.equals(text)) return new Result(text, List.of(), sorted(rejected), "변경 없음 — 결과가 원문과 같다");
        String invariant = invariantAfterDeletes(text, after, deleted);
        if (invariant != null) return new Result(text, List.of(), sorted(rejected), invariant);
        return new Result(after, applied, sorted(rejected), null);
    }

    private static List<Rejected> sorted(List<Rejected> in) {
        return in.stream().sorted(Comparator.comparingInt(Rejected::index)).toList();
    }

    /** 작업 하나의 구간과 넣을 글을 정한다 — {@link Located} 또는 거절 사유. */
    private static Object locate(String text, Operation op) {
        if (op == null || op.op() == null) return "작업 종류가 없다";
        String anchor = op.anchor();
        if (anchor == null || anchor.isBlank()) return "인용이 비었다";
        return switch (op.op()) {
            case REPLACE -> {
                String repl = op.text() == null ? "" : op.text();
                if (repl.isBlank()) yield "새 구간이 비었다 — 지우려면 지우기 작업을 쓴다";
                if (repl.length() > MAX_REPLACE_CHARS) yield "새 구간이 너무 길다(" + MAX_REPLACE_CHARS + "자 이하)";
                Object s = find(text, anchor);
                if (s instanceof String reason) yield reason;
                yield new Located(op, 0, (Span) s, repl);
            }
            case DELETE -> {
                Object s = find(text, anchor);
                if (s instanceof String reason) yield reason;
                Span start = (Span) s;
                if (op.endAnchor() == null || op.endAnchor().isBlank()) yield new Located(op, 0, start, "");
                Object e = find(text, op.endAnchor());
                if (e instanceof String reason) yield "끝 " + reason;
                Span end = (Span) e;
                if (end.end <= start.start) yield "끝 인용이 처음 인용보다 앞에 있다";
                yield new Located(op, 0, new Span(start.start, Math.max(start.end, end.end)), "");
            }
            case DELETE_SECTION -> {
                Object s = find(text, anchor);
                if (s instanceof String reason) yield reason;
                Span hit = (Span) s;
                int lineStart = text.lastIndexOf('\n', hit.start - 1) + 1;
                int lineEnd = lineEnd(text, lineStart);
                Matcher h = HEADING.matcher(text.substring(lineStart, lineEnd).strip());
                if (!h.matches()) yield "섹션 지우기의 인용이 제목 줄이 아니다";
                int level = h.group(1).length();
                int end = text.length();
                int pos = lineEnd < text.length() ? lineEnd + 1 : text.length();
                while (pos < text.length()) {
                    int le = lineEnd(text, pos);
                    Matcher next = HEADING.matcher(text.substring(pos, le).strip());
                    if (next.matches() && next.group(1).length() <= level && !inFence(text, pos)) { end = pos; break; }
                    pos = le < text.length() ? le + 1 : text.length();
                }
                yield new Located(op, 0, new Span(lineStart, end), "");
            }
            case INSERT_AFTER -> {
                String block = op.text() == null ? "" : op.text().strip();
                if (block.isEmpty()) yield "넣을 글이 비었다";
                if (block.length() > MAX_INSERT_CHARS) yield "넣을 글이 너무 길다(" + MAX_INSERT_CHARS + "자 이하)";
                if (normalize(text).contains(normalize(block))) yield "같은 글이 이미 있다";   // G7 — 같은 지시를 두 번 돌려도 두 번 들어가지 않는다
                Object s = find(text, anchor);
                if (s instanceof String reason) yield reason;
                int at = blockEnd(text, ((Span) s).end);
                String insert = (at > 0 ? "\n\n" : "") + block + (at < text.length() && !text.startsWith("\n", at) ? "\n\n" : "");
                yield new Located(op, 0, new Span(at, at), insert);
            }
        };
    }

    /**
     * 인용 하나를 원문에서 찾는다 — 정확히 일치, 없으면 공백 정규화 일치. 한 곳이면 {@link Span}, 아니면 거절 사유.
     */
    static Object find(String text, String quote) {
        int first = text.indexOf(quote);
        if (first >= 0) {
            if (text.indexOf(quote, first + 1) >= 0) return "인용이 여러 곳에 있다: " + preview(quote);
            return new Span(first, first + quote.length());
        }
        StringBuilder norm = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!space && norm.length() > 0) { norm.append(' '); map.add(i); }
                space = true;
            } else {
                norm.append(c);
                map.add(i);
                space = false;
            }
        }
        String q = quote.strip().replaceAll("\\s+", " ");
        if (q.isEmpty()) return "인용이 비었다";
        String n = norm.toString();
        int at = n.indexOf(q);
        if (at < 0) return "인용을 원문에서 찾지 못했다: " + preview(quote);
        if (n.indexOf(q, at + 1) >= 0) return "인용이 여러 곳에 있다: " + preview(quote);
        return new Span(map.get(at), map.get(at + q.length() - 1) + 1);
    }

    /** 넣을 자리 — 인용 끝이 속한 문단·표·목록 블록의 끝, 코드 펜스 안이면 펜스를 닫는 줄 뒤. */
    static int blockEnd(String text, int from) {
        int pos = lineEnd(text, Math.max(0, Math.min(from, text.length())));
        while (pos < text.length()) {
            int next = pos + 1;
            if (next >= text.length()) break;
            int le = lineEnd(text, next);
            if (text.substring(next, le).isBlank()) break;
            pos = le;
        }
        if (inFence(text, pos)) {
            int scan = pos < text.length() ? pos + 1 : pos;
            while (scan < text.length()) {
                int le = lineEnd(text, scan);
                if (text.substring(scan, le).strip().startsWith("```")) return le;
                scan = le < text.length() ? le + 1 : text.length();
            }
            return text.length();
        }
        return pos;
    }

    private static int lineEnd(String text, int from) {
        int nl = text.indexOf('\n', from);
        return nl < 0 ? text.length() : nl;
    }

    /** {@code pos} 앞에 열린 코드 펜스가 있는가(펜스 줄 수가 홀수). */
    private static boolean inFence(String text, int pos) {
        int count = 0;
        int i = 0;
        while (i < pos && i < text.length()) {
            int le = lineEnd(text, i);
            if (text.substring(i, le).strip().startsWith("```") && le <= pos) count++;
            i = le + 1;
        }
        return count % 2 == 1;
    }

    /** 보호 구간 — 그림 마커(+설명), 페이지 마커, 펜스 줄, 표 구분선. */
    private static List<Span> protectedSpans(String text) {
        List<Span> out = new ArrayList<>();
        for (Pattern p : List.of(IMAGE_MARKER, PAGE_MARKER)) {
            Matcher m = p.matcher(text);
            while (m.find()) out.add(new Span(m.start(), m.end()));
        }
        int i = 0;
        while (i < text.length()) {
            int le = lineEnd(text, i);
            String line = text.substring(i, le).strip();
            if (line.startsWith("```") || TABLE_SEPARATOR.matcher(line).matches()) out.add(new Span(i, le));
            i = le + 1;
        }
        return out;
    }

    private static String guardProtected(Located l, List<Span> protectedSpans) {
        Span s = l.span();
        boolean deletes = l.op().op() == Op.DELETE || l.op().op() == Op.DELETE_SECTION;
        for (Span p : protectedSpans) {
            if (s.start == s.end) {
                if (p.start < s.start && s.start < p.end) return "그림·페이지 표지나 코드 펜스·표 구분선 한가운데에 넣으려 했다";
                continue;
            }
            if (!s.overlaps(p)) continue;
            if (deletes && s.contains(p)) continue;   // 통째로 지우는 것은 허용 — 결과 검사(G6)가 맞춰 본다
            return "그림·페이지 표지나 코드 펜스·표 구분선을 일부만 건드렸다";
        }
        return null;
    }

    /**
     * 결과 불변식(G6) — 마커 목록이 "원래 목록 − 지운 구간 안의 마커"와 같고, 코드 펜스 결함이 늘지 않았다. 위반 사유 또는 {@code null}.
     */
    private static String invariantAfterDeletes(String before, String after, List<Span> deleted) {
        if (!markersOutside(before, deleted, IMAGE_MARKER).equals(markers(after, IMAGE_MARKER))) return "그림 표지가 바뀌었다";
        if (!markersOutside(before, deleted, PAGE_MARKER).equals(markers(after, PAGE_MARKER))) return "페이지 표지가 바뀌었다";
        if (MarkdownCorrectionService.findFenceProblems(after).size() > MarkdownCorrectionService.findFenceProblems(before).size()) {
            return "코드 펜스가 깨졌다";
        }
        return null;
    }

    private static List<String> markers(String text, Pattern p) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group().replaceAll("\\s+", " "));
        return out;
    }

    private static List<String> markersOutside(String text, List<Span> deleted, Pattern p) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            Span s = new Span(m.start(), m.end());
            if (deleted.stream().noneMatch(d -> d.contains(s))) out.add(m.group().replaceAll("\\s+", " "));
        }
        return out;
    }

    static String normalize(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String preview(String quote) {
        String q = quote.strip().replaceAll("\\s+", " ");
        return q.length() > 40 ? q.substring(0, 40) + "…" : q;
    }
}
