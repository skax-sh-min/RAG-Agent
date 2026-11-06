package com.example.ragagent.workflow;

import com.example.ragagent.service.ChunkDiff;

import java.util.List;

/**
 * {@link ChunkDiff.Result} 를 모델과 사람이 읽는 <b>축약된 unified diff</b> 로 바꾼다 (AGENT_MCP_DESIGN §3.3 {@code generateDiff}).
 *
 * <p>{@code ChunkDiff} 의 {@code lines} 에는 같은 줄이 전부 들어 있다(비교 화면이 본문 읽기로도 쓰이므로). 그대로 내보내면 한 줄 고친
 * 문서가 문서 크기만큼의 출력이 되므로, 바뀐 줄 주위 {@value #CONTEXT}줄만 남기고 나머지는 접는다. 출력 줄 수에도 상한이 있다.
 * 순수 클래스다.
 */
public final class UnifiedDiffFormatter {

    public static final int CONTEXT = 2;
    public static final int MAX_OUTPUT_LINES = 200;

    private UnifiedDiffFormatter() {}

    /**
     * @param text      축약된 diff. 같은 텍스트이거나 너무 커서 비교하지 못했으면 빈 문자열
     * @param truncated 출력 줄 상한에 걸려 뒤가 잘렸다
     */
    public record Formatted(String text, int addedLines, int removedLines, boolean identical, boolean tooLarge,
                            boolean truncated) {}

    public static Formatted format(ChunkDiff.Result result) {
        if (result == null) return new Formatted("", 0, 0, false, false, false);
        if (result.tooLarge() || result.identical()) {
            return new Formatted("", result.addedLines(), result.removedLines(), result.identical(), result.tooLarge(), false);
        }
        List<ChunkDiff.Line> lines = result.lines();
        int n = lines.size();
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (lines.get(i).same()) continue;
            for (int j = Math.max(0, i - CONTEXT); j <= Math.min(n - 1, i + CONTEXT); j++) keep[j] = true;
        }
        StringBuilder sb = new StringBuilder();
        int out = 0;
        boolean truncated = false;
        int i = 0;
        while (i < n) {
            if (!keep[i]) { i++; continue; }
            // 구간 머리 — 옛/새 파일에서의 시작 줄 번호
            int start = i;
            while (i < n && keep[i]) i++;
            if (out >= MAX_OUTPUT_LINES) { truncated = true; break; }
            sb.append(header(lines, start)).append('\n');
            out++;
            for (int k = start; k < i; k++) {
                if (out >= MAX_OUTPUT_LINES) { truncated = true; break; }
                ChunkDiff.Line l = lines.get(k);
                char mark = l.added() ? '+' : l.removed() ? '-' : ' ';
                sb.append(mark).append(l.text()).append('\n');
                out++;
            }
            if (truncated) break;
        }
        return new Formatted(sb.toString().stripTrailing(), result.addedLines(), result.removedLines(), false, false, truncated);
    }

    private static String header(List<ChunkDiff.Line> lines, int start) {
        Integer oldNo = null;
        Integer newNo = null;
        for (int k = start; k < lines.size() && (oldNo == null || newNo == null); k++) {
            if (oldNo == null && lines.get(k).oldNo() != null) oldNo = lines.get(k).oldNo();
            if (newNo == null && lines.get(k).newNo() != null) newNo = lines.get(k).newNo();
        }
        return "@@ -" + (oldNo == null ? "?" : oldNo) + " +" + (newNo == null ? "?" : newNo) + " @@";
    }
}
