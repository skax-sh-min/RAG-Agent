package com.example.ragagent.workflow;

import com.example.ragagent.service.ChunkDiff;

import java.util.ArrayList;
import java.util.List;

/**
 * 수정안 검토 화면(6e, AGENT_MCP_DESIGN §10.15 R5)의 두 칸 강조 — 순수 클래스. {@link ChunkDiff}(줄 LCS + 바뀐 줄 안 낱말 단위)의 결과를
 * <b>수정 전 칸</b>(같은 줄 + 사라진 줄)과 <b>수정 후 강조 층</b>(같은 줄 + 더한 줄)으로 나눈다.
 *
 * <p><b>강조 층은 {@code textarea} 뒤에 깔린다</b> — 둘의 글이 한 글자라도 다르면 엉뚱한 곳이 칠해진다. 그래서 수정 후 쪽 줄을 이은 글이 입력과
 * 정확히 같은지 확인하고, 다르면 강조 없이 글만 돌려준다({@link Panes#aligned()} = false — 어긋난 강조보다 없는 편이 낫다). 비교가 상한을 넘으면
 * ({@code tooLarge}) 역시 강조 없이 글만.
 */
public final class ReviewDiff {

    private ReviewDiff() {}

    /** 줄 안의 한 구간 — {@code changed} 이면 낱말 강조. */
    public record Seg(boolean changed, String text) {}

    /**
     * 화면의 한 줄. {@code mark} 는 {@code ""}(같음) · {@code "add"} · {@code "del"}.
     */
    public record Row(String mark, List<Seg> segs) {
        public boolean changed() {
            return !mark.isEmpty();
        }
    }

    /**
     * @param before    수정 전 칸의 줄
     * @param after     수정 후 강조 층의 줄 — 이은 글이 입력과 같다(그렇지 않으면 강조 없는 입력 그대로)
     * @param aligned   강조를 칠했는가(아니면 글만)
     * @param tooLarge  비교 상한을 넘었다
     * @param identical 두 글이 같다
     */
    public record Panes(List<Row> before, List<Row> after, boolean aligned, boolean tooLarge, boolean identical,
                        int addedLines, int removedLines) {}

    public static Panes of(String before, String after) {
        String b = before == null ? "" : before.replace("\r\n", "\n");
        String a = after == null ? "" : after.replace("\r\n", "\n");
        ChunkDiff.Result d = ChunkDiff.compare(b, a);
        if (d == null || d.tooLarge()) {
            return new Panes(plain(b), plain(a), false, d != null && d.tooLarge(), false, 0, 0);
        }
        List<Row> bs = new ArrayList<>();
        List<Row> as = new ArrayList<>();
        StringBuilder rebuilt = new StringBuilder();
        for (ChunkDiff.Line l : d.lines()) {
            Row row = new Row(l.added() ? "add" : l.removed() ? "del" : "", segs(l));
            if (!l.added()) bs.add(row);
            if (!l.removed()) {
                if (!as.isEmpty()) rebuilt.append('\n');
                rebuilt.append(l.text());
                as.add(row);
            }
        }
        if (!rebuilt.toString().equals(a)) {
            return new Panes(bs, plain(a), false, false, d.identical(), d.addedLines(), d.removedLines());
        }
        return new Panes(bs, as, true, false, d.identical(), d.addedLines(), d.removedLines());
    }

    private static List<Seg> segs(ChunkDiff.Line l) {
        if (l.spans().isEmpty()) return List.of(new Seg(false, l.text()));
        List<Seg> out = new ArrayList<>(l.spans().size());
        for (ChunkDiff.Span s : l.spans()) out.add(new Seg(s.changed(), s.text()));
        return out;
    }

    private static List<Row> plain(String text) {
        List<Row> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) out.add(new Row("", List.of(new Seg(false, line))));
        return out;
    }
}
