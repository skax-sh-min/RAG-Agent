package com.example.ragagent.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import com.example.ragagent.ingestion.ChunkSplitter;
import com.example.ragagent.service.ChunkDiff;

/**
 * 변환 MD 되쓰기(§6.33 4b-2) — 청크를 고친 수정을 {@code converted/{docId}_corrected.md}(없으면 {@code {docId}.md})에도 반영한다. 문서 단위
 * 재인덱싱은 청크를 이 MD 에서 다시 만들므로, 되쓰지 않으면 다음 재인덱싱이 수정을 되돌린다.
 *
 * <p><b>구간 일치에 기대지 않는다.</b> 청크에는 부모 제목·{@code (2)} 제목·이어짐 마커·표 머리글이 더해져 MD 본문과 다르다. 그래서 청크 텍스트로
 * MD 를 찾지 않고 <b>바꿀 말의 발생 수</b>만 대조한다: ① 이 문서의 어느 청크에도 바꿀 말이 남아 있지 않아야 하고(일부만 고친 문서에서 MD 만 바꾸면
 * 둘이 갈라진다) ② MD 의 발생 수가 1 이상이며 ③ 적용한 청크들에서 센 발생 수 이하여야 한다(청크는 겹침 구간 때문에 같은 자리를 두 번 셀 수는 있어도
 * MD 의 자리를 빠뜨리지는 않는다). 하나라도 어긋나면 <b>건드리지 않고</b> {@link #CONFLICT} 로 남긴다. 임시 파일에 쓴 뒤 원자적으로 옮긴다.
 *
 * <p>MD 가 없으면({@code MD_MISSING} — 스캔 PDF 처럼 변환 MD 를 만들지 않는 문서) 아무것도 하지 않는다. 이 되쓰기는 <b>원본 파일(DOCX/PDF)은 고치지
 * 않는다</b> — 원본을 다시 올리면 이 수정은 사라진다.
 */
public final class MarkdownWriteBack {

    public static final String APPLIED = "applied";
    public static final String CONFLICT = "MD_CONFLICT";
    public static final String MISSING = "MD_MISSING";

    public record Result(String status, int replaced, int mdOccurrences, String detail) {}

    private MarkdownWriteBack() {}

    /**
     * @param chunkOccurrences 이번에 적용한 청크들의 수정 전 본문에서 센 {@code from} 의 발생 수 합
     * @param noChunkLeftWithTerm 적용 뒤 이 문서의 어느 청크에도 {@code from} 이 남아 있지 않은가
     */
    public static Result apply(Path convertedDir, String docId, String from, String to,
                               int chunkOccurrences, boolean noChunkLeftWithTerm) {
        return apply(convertedDir, docId, TermReplacement.exact(from, to), chunkOccurrences, noChunkLeftWithTerm);
    }

    /** 청크를 고친 것과 <b>같은 규칙</b>({@link TermReplacement} — 대소문자 변형 포함 여부까지)으로 MD 를 센다·바꾼다. */
    public static Result apply(Path convertedDir, String docId, TermReplacement rule,
                               int chunkOccurrences, boolean noChunkLeftWithTerm) {
        Path md = resolve(convertedDir, docId);
        if (md == null) return new Result(MISSING, 0, 0, "변환 MD 없음");
        if (!noChunkLeftWithTerm) return new Result(CONFLICT, 0, 0, "일부 청크에 바꿀 말이 남아 있어 MD 만 바꾸면 청크와 갈라진다");
        try {
            String text = Files.readString(md, StandardCharsets.UTF_8);
            int count = rule.count(text);
            if (count == 0) return new Result(CONFLICT, 0, 0, "MD 에 바꿀 말이 없다(MD 가 청크와 다르다)");
            if (count > chunkOccurrences) {
                return new Result(CONFLICT, 0, count, "MD 의 발생 수(" + count + ")가 청크에서 센 수(" + chunkOccurrences + ")보다 많다");
            }
            Path tmp = md.resolveSibling(md.getFileName() + ".tmp");
            Files.writeString(tmp, rule.apply(text), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, md, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, md, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(APPLIED, count, count, "");
        } catch (IOException e) {
            return new Result(CONFLICT, 0, 0, "MD 를 쓰지 못했다: " + e.getMessage());
        }
    }

    /** 적용된 수정안 하나의 수정 전·후 본문 — {@link #applyEdits} 의 입력. 청크 삭제는 {@code after} 가 빈 문자열이다. */
    public record Edit(String before, String after) {}

    /** MD 줄 범위 [{@code start}, {@code end}) 를 {@code replacement} 로 — 넣기만 하는 구간은 {@code start == end}. */
    record Hunk(int start, int end, List<String> replacement) {}

    /**
     * 글자 치환이 아닌 수정(앵커 편집·의미 변경·관리자가 고친 것·청크 삭제)을 변환 MD 에 되쓴다(AGENT_MCP_DESIGN §10.8, E6).
     *
     * <p>수정안마다 수정 전·후를 줄 단위로 비교해({@link ChunkDiff}) 바뀐 구간을 얻고, <b>지운 줄 묶음이 MD 에 정확히 한 번</b> 나오는 자리를
     * 바꾼다(줄 비교는 공백 정규화). 넣기만 한 구간은 바로 앞의 바뀌지 않은 줄(빈 줄 제외)이 MD 에 한 번 나와야 하고, 청크 맨 앞에 넣었으면 바로 뒤의
     * 줄로 찾는다. 청크 삭제처럼 지우기만 하는 구간은 청크가 덧붙인 앞머리(부모 제목·다시 넣은 제목·코드 이어짐 표시)를 하나씩 떼어 가며 다시 찾는다 —
     * 그 줄들은 MD 의 그 자리에 없다. 겹침 짝이 만든 같은 구간은 하나로 줄인다.
     *
     * <p><b>문서 단위로 전부 아니면 전무다.</b> 구간 하나라도 못 찾거나(없음·여러 곳) 구간끼리 겹치면 MD 를 건드리지 않고 {@link #CONFLICT} —
     * 반쯤 바뀐 MD 는 청크와 갈라져 다음 재인덱싱이 일부만 되돌린다. 임시 파일 → 원자적 이동은 {@link #apply} 와 같다.
     */
    public static Result applyEdits(Path convertedDir, String docId, List<Edit> edits) {
        Path md = resolve(convertedDir, docId);
        if (md == null) return new Result(MISSING, 0, 0, "변환 MD 없음");
        try {
            String text = Files.readString(md, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
            List<Hunk> hunks = new ArrayList<>();
            for (Edit e : edits) {
                String problem = locate(e, lines, hunks);
                if (problem != null) return new Result(CONFLICT, 0, 0, problem);
            }
            List<Hunk> unique = new ArrayList<>(new LinkedHashSet<>(hunks));
            unique.sort(Comparator.comparingInt(Hunk::start).thenComparingInt(Hunk::end));
            for (int i = 1; i < unique.size(); i++) {
                Hunk a = unique.get(i - 1), b = unique.get(i);
                if (b.start() < a.end() || (b.start() == a.start() && a.start() == a.end())) {
                    return new Result(CONFLICT, 0, 0, "수정 구간끼리 MD 에서 겹친다");
                }
            }
            if (unique.isEmpty()) return new Result(APPLIED, 0, 0, "MD 에 바꿀 줄이 없다(공백만 바뀜)");
            for (int i = unique.size() - 1; i >= 0; i--) {
                Hunk h = unique.get(i);
                lines.subList(h.start(), h.end()).clear();
                lines.addAll(h.start(), h.replacement());
            }
            write(md, String.join("\n", lines));
            return new Result(APPLIED, unique.size(), unique.size(), "");
        } catch (IOException e) {
            return new Result(CONFLICT, 0, 0, "MD 를 쓰지 못했다: " + e.getMessage());
        }
    }

    /** 한 수정안의 바뀐 구간들을 MD 줄 위치로 옮겨 {@code out} 에 더한다. 못 옮기면 사유. */
    private static String locate(Edit edit, List<String> md, List<Hunk> out) {
        if (edit.after() == null || edit.after().isBlank()) {
            // 청크 삭제 — 줄 비교에 맡기면 수정 전의 빈 줄이 수정 후의 빈 문자열과 "같은 줄"로 짝지어져 구간이 쪼개진다. 본문 전체를 한 덩어리로 찾는다.
            List<String> block = trimBlank(List.of((edit.before() == null ? "" : edit.before()).split("\r\n|\r|\n", -1)));
            if (block.isEmpty()) return null;
            List<List<String>> candidates = new ArrayList<>(List.of(block));
            candidates.addAll(withoutChunkHead(block));
            for (List<String> c : candidates) {
                if (c.isEmpty()) continue;
                List<Integer> hits = findBlock(md, c);
                if (hits.size() > 1) return "지울 청크가 MD 에 여러 번 나온다: " + preview(c);
                if (hits.size() == 1) {
                    out.add(new Hunk(hits.get(0), hits.get(0) + c.size(), List.of()));
                    return null;
                }
            }
            return "지울 청크를 MD 에서 찾지 못했다: " + preview(block);
        }
        ChunkDiff.Result diff = ChunkDiff.compare(edit.before() == null ? "" : edit.before(), edit.after() == null ? "" : edit.after());
        if (diff == null || diff.tooLarge()) return "수정 전후를 비교하기에 너무 크다";
        if (diff.identical()) return null;
        List<ChunkDiff.Line> ls = diff.lines();
        int i = 0;
        while (i < ls.size()) {
            if (ls.get(i).same()) { i++; continue; }
            int from = i;
            List<String> removed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            while (i < ls.size() && !ls.get(i).same()) {
                if (ls.get(i).removed()) removed.add(ls.get(i).text()); else added.add(ls.get(i).text());
                i++;
            }
            boolean removedBlank = removed.stream().allMatch(String::isBlank);
            boolean addedBlank = added.stream().allMatch(String::isBlank);
            if (removedBlank && addedBlank) continue;   // 공백만 바뀜
            if (!removedBlank) {
                List<String> block = trimBlank(removed);
                List<String> replacement = addedBlank ? List.of() : added;
                List<List<String>> candidates = new ArrayList<>(List.of(block));
                if (addedBlank) candidates.addAll(withoutChunkHead(block));
                Integer at = null;
                List<String> found = null;
                for (List<String> c : candidates) {
                    if (c.isEmpty()) continue;
                    List<Integer> hits = findBlock(md, c);
                    if (hits.size() > 1 && c == block) {
                        // 같은 줄 묶음이 여러 번 — 앞뒤의 바뀌지 않은 줄을 붙여 한 곳으로 좁힌다
                        int lead = 0;
                        while (lead < removed.size() && removed.get(lead).isBlank()) lead++;
                        int tail = 0;
                        while (tail < removed.size() - lead && removed.get(removed.size() - 1 - tail).isBlank()) tail++;
                        int pos = narrow(md, sameBefore(ls, from), blanks(lead), block, blanks(tail), sameAfter(ls, i));
                        if (pos >= 0) { at = pos; found = c; break; }
                    }
                    if (hits.size() > 1) return "지울 줄이 MD 에 여러 번 나온다: " + preview(c);
                    if (hits.size() == 1) { at = hits.get(0); found = c; break; }
                }
                if (at == null) return "지울 줄을 MD 에서 찾지 못했다: " + preview(block);
                out.add(new Hunk(at, at + found.size(), List.copyOf(replacement)));
            } else {
                String problem = locateInsertion(ls, from, i, added, md, out);
                if (problem != null) return problem;
            }
        }
        return null;
    }

    /** 넣기만 한 구간 — 앞의(없으면 뒤의) 바뀌지 않은 빈 줄 아닌 줄을 MD 에서 한 번 찾아 그 옆에 넣는다. */
    private static String locateInsertion(List<ChunkDiff.Line> ls, int from, int to, List<String> added,
                                          List<String> md, List<Hunk> out) {
        int gap = 0;
        for (int k = from - 1; k >= 0; k--) {
            ChunkDiff.Line l = ls.get(k);
            if (!l.same()) break;
            if (l.text().isBlank()) { gap++; continue; }
            List<Integer> hits = findBlock(md, List.of(l.text()));
            if (hits.size() > 1) {
                // 앞 줄이 흔한 줄(`---`·빈 표 칸 등)이면 그 위의 바뀌지 않은 줄을 더 붙여 한 곳으로 좁힌다
                int at = narrow(md, sameBefore(ls, from), List.of(), List.of(), List.of(), sameAfter(ls, to));
                if (at >= 0) { out.add(new Hunk(at, at, List.copyOf(added))); return null; }
            }
            if (hits.size() != 1) return "넣을 자리(앞 줄)를 MD 에서 하나로 찾지 못했다: " + preview(List.of(l.text()));
            int pos = hits.get(0) + 1;
            for (int g = 0; g < gap; g++) {
                if (pos + g >= md.size() || !md.get(pos + g).isBlank()) return "넣을 자리 앞뒤의 빈 줄이 MD 와 다르다";
            }
            out.add(new Hunk(pos + gap, pos + gap, List.copyOf(added)));
            return null;
        }
        gap = 0;
        for (int k = to; k < ls.size(); k++) {
            ChunkDiff.Line l = ls.get(k);
            if (!l.same()) break;
            if (l.text().isBlank()) { gap++; continue; }
            List<Integer> hits = findBlock(md, List.of(l.text()));
            if (hits.size() > 1) {
                int at = narrow(md, List.of(), List.of(), List.of(), List.of(), sameAfter(ls, to));
                if (at >= 0) { out.add(new Hunk(at, at, List.copyOf(added))); return null; }
            }
            if (hits.size() != 1) return "넣을 자리(뒤 줄)를 MD 에서 하나로 찾지 못했다: " + preview(List.of(l.text()));
            int pos = hits.get(0) - gap;
            for (int g = 0; g < gap; g++) {
                if (pos + g < 0 || !md.get(pos + g).isBlank()) return "넣을 자리 앞뒤의 빈 줄이 MD 와 다르다";
            }
            if (pos < 0) return "넣을 자리 앞뒤의 빈 줄이 MD 와 다르다";
            out.add(new Hunk(pos, pos, List.copyOf(added)));
            return null;
        }
        return "넣을 자리를 가리킬 바뀌지 않은 줄이 없다";
    }

    /**
     * 수정 구간이 MD 에 여러 번 나올 때 앞뒤 맥락으로 한 곳을 고른다 — {@code block} 앞에 청크의 바뀌지 않은 줄({@code before}, 가까운 것이 끝)을
     * 하나씩 더 붙여 보고, 그래도 여러 곳이면 뒤({@code after}, 가까운 것이 앞)를 붙여 본다. 붙인 맥락이 MD 에 없으면(청크가 덧붙인 앞머리 등) 그 방향은
     * 멈춘다. {@code lead}·{@code tail} 은 구간 앞뒤의 빈 줄(맥락과 구간 사이에 그대로 있어야 한다). 반환은 {@code block} 이 시작하는 MD 줄(빈 구간이면
     * 넣을 자리), 한 곳으로 못 좁히면 -1.
     */
    static int narrow(List<String> md, List<String> before, List<String> lead, List<String> block, List<String> tail,
                      List<String> after) {
        List<String> core = new ArrayList<>(lead);
        core.addAll(block);
        core.addAll(tail);
        for (int n = 1; n <= before.size(); n++) {
            List<String> ctx = new ArrayList<>(before.subList(before.size() - n, before.size()));
            ctx.addAll(core);
            List<Integer> hits = findBlock(md, ctx);
            if (hits.size() == 1) return hits.get(0) + n + lead.size();
            if (hits.isEmpty()) break;
        }
        for (int m = 1; m <= after.size(); m++) {
            List<String> ctx = new ArrayList<>(core);
            ctx.addAll(after.subList(0, m));
            List<Integer> hits = findBlock(md, ctx);
            if (hits.size() == 1) return hits.get(0) + lead.size();
            if (hits.isEmpty()) break;
        }
        return -1;
    }

    /**
     * {@code from} 앞의 <b>수정 전</b> 줄들(바뀌지 않은 줄 + 지운 줄, 청크 순서) — 찾는 MD 는 아직 수정 전이라, 다른 구간이 바꾼 줄도 옛 모양 그대로
     * 맥락이 된다. 넣은 줄은 MD 에 없으니 건너뛴다.
     */
    private static List<String> sameBefore(List<ChunkDiff.Line> ls, int from) {
        List<String> out = new ArrayList<>();
        for (int k = from - 1; k >= 0; k--) {
            if (ls.get(k).same() || ls.get(k).removed()) out.add(0, ls.get(k).text());
        }
        return out;
    }

    /** {@code to} 부터의 수정 전 줄들. */
    private static List<String> sameAfter(List<ChunkDiff.Line> ls, int to) {
        List<String> out = new ArrayList<>();
        for (int k = to; k < ls.size(); k++) {
            if (ls.get(k).same() || ls.get(k).removed()) out.add(ls.get(k).text());
        }
        return out;
    }

    private static List<String> blanks(int n) {
        return java.util.Collections.nCopies(n, "");
    }

    /**
     * 지우기만 하는 구간의 다른 후보들 — 청크가 덧붙인 앞머리를 하나씩 뗀 것. 끝의 코드 이어짐 표시(+ 그 앞의 펜스)를 먼저 떼고, 그다음 앞쪽에서 제목 줄과
     * 코드 이어짐 표시(+ 바로 뒤의 펜스)를 하나씩 뗀다.
     */
    static List<List<String>> withoutChunkHead(List<String> block) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>(block);
        int last = cur.size() - 1;
        if (last >= 0 && ChunkSplitter.CODE_CONTINUATION_AFTER.equals(cur.get(last).strip())) {
            cur.remove(last);
            if (!cur.isEmpty() && cur.get(cur.size() - 1).strip().startsWith("```")) cur.remove(cur.size() - 1);
            cur = trimBlank(cur);
            out.add(List.copyOf(cur));
        }
        while (!cur.isEmpty()) {
            String head = cur.get(0).strip();
            if (head.startsWith("#")) {
                cur.remove(0);
            } else if (ChunkSplitter.CODE_CONTINUATION_BEFORE.equals(head)) {
                cur.remove(0);
                if (!cur.isEmpty() && cur.get(0).strip().startsWith("```")) cur.remove(0);
            } else {
                break;
            }
            cur = trimBlank(cur);
            out.add(List.copyOf(cur));
        }
        return out;
    }

    /** {@code block} 이 MD 에서 시작하는 줄 번호들(줄마다 공백 정규화 비교). */
    static List<Integer> findBlock(List<String> md, List<String> block) {
        List<Integer> hits = new ArrayList<>();
        outer:
        for (int s = 0; s + block.size() <= md.size(); s++) {
            for (int k = 0; k < block.size(); k++) {
                if (!norm(md.get(s + k)).equals(norm(block.get(k)))) continue outer;
            }
            hits.add(s);
        }
        return hits;
    }

    private static String norm(String line) {
        return line.replace('\r', ' ').strip().replaceAll("\\s+", " ");
    }

    private static List<String> trimBlank(List<String> in) {
        int a = 0, b = in.size();
        while (a < b && in.get(a).isBlank()) a++;
        while (b > a && in.get(b - 1).isBlank()) b--;
        return new ArrayList<>(in.subList(a, b));
    }

    private static String preview(List<String> block) {
        String first = block.isEmpty() ? "" : block.get(0).strip();
        return first.length() > 60 ? first.substring(0, 60) + "…" : first;
    }

    private static void write(Path md, String text) throws IOException {
        Path tmp = md.resolveSibling(md.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, md, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, md, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 재인덱싱이 읽는 MD 와 같은 우선순위 — 교정본 먼저, 없으면 원본 변환본. */
    static Path resolve(Path convertedDir, String docId) {
        Path corrected = convertedDir.resolve(docId + "_corrected.md");
        if (Files.exists(corrected)) return corrected;
        Path raw = convertedDir.resolve(docId + ".md");
        return Files.exists(raw) ? raw : null;
    }

    /** 겹치지 않는 발생 수. */
    static int occurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) return 0;
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
