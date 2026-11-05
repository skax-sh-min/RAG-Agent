package com.example.ragagent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 청크 본문 속 이미지 마커({@code [이미지: images/…/file.png]})와 그 설명({@code [이미지 설명: …]})을 다루는 <b>순수 텍스트 변환</b> —
 * {@code /admin} 청크 편집의 그림 추가·삭제·교체.
 *
 * <p><b>왜 서버에 있는가.</b> 마커·설명을 어디서 시작해 어디서 끝내는지, 설명이 줄바꿈 뒤에 붙는지 표 셀 안의 {@code <br>} 뒤에 붙는지,
 * 지울 때 빈 줄이 남지 않게 하는 규칙은 틀리기 쉬운데 이 프로젝트에는 JS 테스트 하네스가 없다({@code AdminService.ChunkRow} 가
 * {@code chunk_context} 를 서버에서 나누는 이유와 같다). 화면은 텍스트를 보내고 바뀐 텍스트를 받아 {@code <textarea>} 에 넣을 뿐이다.
 *
 * <p>설명은 마커 <b>바로 뒤</b>에 붙은 것만 그 마커의 것으로 본다 — 문서 파이프라인이 만드는 모양이 그것뿐이다
 * ({@code MarkdownCorrectionService}, {@code CuratedImageStore.injectDescriptions}): 일반 줄에서는 다음 줄, 표 셀 안에서는
 * {@code <br>} 로 이어진다(셀 안의 줄바꿈은 표를 깬다).
 */
public final class ChunkImageEditor {

    /** {@code [이미지: 경로]} 와 {@code [이미지(변환불가): 경로]} — {@code CuratedImageStore} 의 마커와 같은 모양. */
    private static final Pattern MARKER = Pattern.compile("\\[이미지(\\([^)]*\\))?:[ \\t]*([^\\]]+?)[ \\t]*]");

    /** 마커 바로 뒤에 붙은 설명 — 줄바꿈 또는 {@code <br>} 로 이어진다. */
    private static final Pattern DESCRIPTION =
            Pattern.compile("(?:[ \\t]*\\r?\\n|<br>)[ \\t]*\\[이미지 설명:[^\\]]*]");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ChunkImageEditor() {}

    /**
     * 마커 하나. {@code descStart}/{@code descEnd} 는 마커에 딸린 설명(앞의 줄바꿈·{@code <br>} 포함) 범위이고, 설명이 없으면 둘 다 {@code -1}.
     * {@code description} 은 {@code [이미지 설명: …]} 의 안쪽 글이다.
     */
    public record Marker(int index, String path, boolean unconvertible, int start, int end,
                         int descStart, int descEnd, String description) {
        public boolean hasDescription() {
            return descEnd >= 0;
        }

        /** 마커와 설명을 합친 끝 위치. */
        int blockEnd() {
            return hasDescription() ? descEnd : end;
        }
    }

    /** 본문 속 마커를 나온 순서대로. {@code index} 는 그 순서(0부터)이고, 편집 요청이 마커를 가리키는 키다. */
    public static List<Marker> markers(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<Marker> out = new ArrayList<>();
        Matcher m = MARKER.matcher(text);
        while (m.find()) {
            int descStart = -1;
            int descEnd = -1;
            String description = "";
            Matcher d = DESCRIPTION.matcher(text);
            d.region(m.end(), text.length());
            if (d.lookingAt()) {
                descStart = m.end();
                descEnd = d.end();
                String block = text.substring(d.start(), d.end());
                description = block.substring(block.indexOf("[이미지 설명:") + "[이미지 설명:".length(), block.length() - 1).strip();
            }
            out.add(new Marker(out.size(), m.group(2).strip(), m.group(1) != null, m.start(), m.end(),
                    descStart, descEnd, description));
        }
        return List.copyOf(out);
    }

    /**
     * 마커와 그 설명을 지운다. 마커가 한 줄을 혼자 차지했으면 그 줄을 통째로 없애고(빈 줄이 남지 않게), 표 셀 안에서 앞에 붙어 있던
     * {@code <br>} 가 매달리면 함께 없앤다. 이어진 빈 줄은 하나로 접는다.
     */
    public static String remove(String text, int index) {
        Marker mk = pick(text, index);
        int from = mk.start();
        int to = mk.blockEnd();
        boolean lineStart = from == 0 || text.charAt(from - 1) == '\n';
        boolean lineEnd = to >= text.length() || text.charAt(to) == '\n' || text.charAt(to) == '\r';
        if (lineStart && lineEnd) {
            if (to < text.length()) to += text.startsWith("\r\n", to) ? 2 : 1;
            else if (from > 0) from -= 1;
        } else if (from >= 4 && text.startsWith("<br>", from - 4) && endsCell(text, to)) {
            from -= 4;   // 표 셀 안에서 앞의 <br> 이 매달리지 않게
        }
        StringBuilder sb = new StringBuilder(text.length());
        sb.append(text, 0, from).append(text, to, text.length());
        int join = from;
        while (join >= 2 && join < sb.length() && sb.charAt(join) == '\n'
                && sb.charAt(join - 1) == '\n' && sb.charAt(join - 2) == '\n') {
            sb.deleteCharAt(join);
        }
        return sb.toString();
    }

    /**
     * 마커의 경로를 바꾼다. 그림이 달라졌으므로 <b>옛 설명은 함께 지운다</b> — 남기면 새 그림 옆에 옛 그림의 설명이 붙은 채로 임베딩된다.
     * {@code (변환불가)} 표시도 뗀다(새 파일은 변환된 그림이다).
     */
    public static String replace(String text, int index, String newPath) {
        Marker mk = pick(text, index);
        return text.substring(0, mk.start()) + marker(newPath) + text.substring(mk.blockEnd());
    }

    /**
     * 커서 위치({@code offset}, 범위를 벗어나면 끝으로 맞춘다)에 마커를 <b>자기 줄</b>로 끼워 넣는다 — 앞뒤로 빈 줄을 두어 마크다운에서
     * 앞뒤 문단에 붙지 않게 한다(지식 제안 폼의 삽입과 같은 규칙).
     */
    public static String insert(String text, int offset, String path) {
        String src = text == null ? "" : text;
        int at = Math.max(0, Math.min(offset, src.length()));
        String before = src.substring(0, at);
        String after = src.substring(at);
        String prefix = before.isEmpty() || before.endsWith("\n\n") ? "" : before.endsWith("\n") ? "\n" : "\n\n";
        String suffix = after.isEmpty() || after.startsWith("\n\n") ? "" : after.startsWith("\n") ? "\n" : "\n\n";
        return before + prefix + marker(path) + suffix + after;
    }

    /**
     * 마커의 설명을 {@code description} 으로 정한다(있으면 바꾸고 없으면 붙인다). 줄바꿈과 공백은 한 칸으로 접고, 표 셀 안의 마커는
     * {@code <br>} 로 잇는다. {@code description} 이 비어 있으면 설명을 지운다.
     */
    public static String setDescription(String text, int index, String description) {
        Marker mk = pick(text, index);
        String flat = description == null ? "" : WHITESPACE.matcher(description).replaceAll(" ").strip();
        String head = text.substring(0, mk.end());
        String tail = text.substring(mk.blockEnd());
        if (flat.isEmpty()) return head + tail;
        String separator = mk.hasDescription()
                ? (text.startsWith("<br>", mk.descStart()) ? "<br>" : "\n")
                : (inTableRow(text, mk.start()) ? "<br>" : "\n");
        return head + separator + "[이미지 설명: " + flat + "]" + tail;
    }

    /** 본문에서 마커 경로만 — {@code image_paths} 메타데이터를 다시 계산하는 데 쓴다(중복 없이, 나온 순서대로). */
    public static List<String> paths(String text) {
        List<String> out = new ArrayList<>();
        for (Marker m : markers(text)) {
            if (!out.contains(m.path())) out.add(m.path());
        }
        return out;
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private static String marker(String path) {
        return "[이미지: " + path.strip() + "]";
    }

    /** {@code pos} 다음이 셀(또는 줄·본문)의 끝인가 — 공백을 건너뛰고 {@code |}, 줄바꿈, 본문 끝, 또 다른 {@code <br>} 중 하나. */
    private static boolean endsCell(String text, int pos) {
        int i = pos;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return i >= text.length() || text.charAt(i) == '|' || text.charAt(i) == '\n' || text.charAt(i) == '\r'
                || text.startsWith("<br>", i);
    }

    private static boolean inTableRow(String text, int markerStart) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, markerStart - 1)) + 1;
        return text.substring(lineStart, markerStart).stripLeading().startsWith("|");
    }

    private static Marker pick(String text, int index) {
        List<Marker> all = markers(text);
        if (index < 0 || index >= all.size()) {
            throw new IllegalArgumentException("이미지 마커를 찾을 수 없습니다 (순번 " + index + ", 본문에는 " + all.size() + "개)");
        }
        return all.get(index);
    }
}
