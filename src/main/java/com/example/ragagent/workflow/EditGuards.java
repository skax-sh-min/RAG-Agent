package com.example.ragagent.workflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 앱 안 워크플로가 LLM 출력에 거는 가드 중 <b>지시문이 있어야</b> 하는 것들(AGENT_MCP_DESIGN §10.5) — G3(대상 관련성) · G4(새 사실은 지시문에서만) ·
 * G10(다듬은 문장의 사실 대조). 인용·보호 구간·크기·불변식(G1·G2·G5·G6·G7)은 {@link AnchoredEdit} 에 있다. 순수 클래스다.
 *
 * <p><b>공통 원칙: 표현은 막지 않고 사실만 대조한다.</b> 숫자·식별자·따옴표 안 문자열·부정 표현은 셀 수 있는 사실이라 코드가 본다. 표현만 바뀌었는데 뜻이
 * 미묘하게 달라진 것은 여기서 못 잡는다 — 사람의 검토(Diff 에 지시문 문장을 나란히 보인다)가 맡는다.
 */
public final class EditGuards {

    /** 다듬은 문장에 새로 생겨도 되는 내용어 수(G10 ④). */
    static final int MAX_NEW_CONTENT_WORDS = 2;
    /** 다듬은 문장의 길이 상한(G10 ⑤). */
    static final int MAX_SENTENCE_CHARS = 400;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    /** 식별자 — 영숫자·{@code _}·{@code .}·괄호로 된 낱말(2자 이상). */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*(?:\\(\\))?|[A-Za-z0-9_]*\\d[A-Za-z0-9_.]*");
    private static final Pattern QUOTED = Pattern.compile("[\"“「『']([^\"”」』']{1,80})[\"”」』']");
    /** 부정 표현 — 한국어 어미·영어. 수가 지시문 쪽과 같아야 한다(G10 ③). */
    private static final Pattern NEGATION = Pattern.compile("않|없|금지|말아|마세요|불가|못 |못하|아니|\\bnot\\b|\\bnever\\b|\\bno\\b|n't", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+\\S.*$");

    private EditGuards() {}

    // ── G3 ─────────────────────────────────────────────────────────────────

    /**
     * G3 — 지우기·바꾸기 작업이 대상과 관련된 구간을 가리키는가: 인용(바꾸기의 원문 · 지우기의 처음/끝 · 섹션 제목) 또는 그 인용이 속한 섹션 제목에 대상이
     * 단어 경계에서 나와야 한다. 위반 사유 또는 {@code null}. 넣기는 대상이 아니다(넣는 문장은 G10 이 본다).
     */
    public static String relevance(String chunkText, AnchoredEdit.Operation op, String subject) {
        if (op == null || op.op() == AnchoredEdit.Op.INSERT_AFTER || subject == null) return null;
        List<String> quotes = new ArrayList<>();
        if (op.anchor() != null) quotes.add(op.anchor());
        if (op.endAnchor() != null) quotes.add(op.endAnchor());
        for (String q : quotes) {
            if (ImpactAnalysisWorkflow.hasStandaloneOccurrence(q, subject)) return null;
            String heading = sectionHeadingOf(chunkText, q);
            if (heading != null && ImpactAnalysisWorkflow.hasStandaloneOccurrence(heading, subject)) return null;
        }
        return "대상(" + subject + ")과 관련된 구간이 아니다 — 인용에도 그 섹션 제목에도 대상이 없다";
    }

    /**
     * 청크 전체 삭제를 받아도 되는가 — 청크의 <b>모든 본문</b>이 대상이 제목에 나오는 섹션(또는 그 하위 섹션) 안에 있어야 한다.
     * 청크는 위치 표시로 상위 제목(`## 26. 클래스: …FileUtils`)을 앞에 달고 시작하므로 첫 제목만 보면 안 된다(그 제목엔 메서드 이름이 없다 —
     * 6c 사전 평가에서 진짜 대상 8건이 이렇게 버려졌다). 반대로 한 청크에 다른 섹션의 본문이 섞여 있거나(앞 메서드의 꼬리 + 대상 메서드)
     * 첫 제목 위에 본문이 있으면(앞 섹션에서 이어진 글) 거절한다 — 그 본문까지 지우게 되기 때문. 펜스 안의 {@code #} 줄은 제목이 아니다.
     */
    public static boolean wholeChunkBelongsTo(String chunkText, String subject) {
        if (chunkText == null || subject == null || subject.isBlank()) return false;
        record Section(int level, boolean hit) { }
        java.util.ArrayDeque<Section> stack = new java.util.ArrayDeque<>();
        boolean inFence = false, anyHit = false, anyBody = false;
        for (String raw : chunkText.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("```") || line.startsWith("~~~")) inFence = !inFence;
            else if (!inFence && HEADING.matcher(line).matches()) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                while (!stack.isEmpty() && stack.peek().level() >= level) stack.pop();
                boolean hit = ImpactAnalysisWorkflow.hasStandaloneOccurrence(line, subject) || (!stack.isEmpty() && stack.peek().hit());
                stack.push(new Section(level, hit));
                anyHit |= hit;
                continue;
            }
            if (line.isEmpty()) continue;
            anyBody = true;
            if (stack.isEmpty() || !stack.peek().hit()) return false;   // 제목 위의 이어진 글이거나 다른 섹션의 본문
        }
        return anyHit && anyBody;
    }

    /** 인용이 처음 나오는 자리 위쪽의 가장 가까운 제목 줄 — 없으면 {@code null}. */
    static String sectionHeadingOf(String text, String quote) {
        if (text == null || quote == null) return null;
        int at = text.indexOf(quote.strip());
        if (at < 0) {
            String head = quote.strip().split("\\s+")[0];
            at = head.isEmpty() ? -1 : text.indexOf(head);
        }
        if (at < 0) return null;
        String[] lines = text.substring(0, at).split("\n", -1);
        // 인용이 제목 줄 자체에서 시작하는 경우도 포함한다.
        int lineEnd = text.indexOf('\n', at);
        String current = text.substring(text.lastIndexOf('\n', Math.max(0, at - 1)) + 1, lineEnd < 0 ? text.length() : lineEnd);
        if (HEADING.matcher(current.strip()).matches()) return current.strip();
        for (int i = lines.length - 1; i >= 0; i--) {
            if (HEADING.matcher(lines[i].strip()).matches()) return lines[i].strip();
        }
        return null;
    }

    // ── G4 ─────────────────────────────────────────────────────────────────

    /**
     * G4 — 바꾸기의 새 구간에 새로 생긴 사실이 지시문에서 왔는가: 숫자는 원문 또는 지시문에 있어야 하고(0개 허용), 원문·지시문 어디에도 없는 낱말은
     * 1개까지. 위반 사유 또는 {@code null}.
     */
    public static String newFacts(String chunkText, String newText, String instruction) {
        String source = (chunkText == null ? "" : chunkText) + "\n" + (instruction == null ? "" : instruction);
        for (String n : matches(NUMBER, newText)) {
            if (!source.contains(n)) return "지시문에 없는 숫자를 넣었다: " + n;
        }
        List<String> unknown = unknownWords(newText, source);
        if (unknown.size() > 1) return "지시문에 없는 말을 넣었다: " + String.join(", ", unknown);
        return null;
    }

    // ── G10 ────────────────────────────────────────────────────────────────

    /**
     * G10 — 다듬은 문장이 기준 문장(지시문에서 뽑은 문장 · 사용 중단 템플릿)의 사실을 지켰는가. 위반 사유 또는 {@code null}(통과).
     * <ol>
     *   <li>기준 문장의 숫자·식별자·따옴표 안 문자열이 모두 그대로 있다</li>
     *   <li>그 밖의 숫자·식별자는 주변 문단에 있는 것만</li>
     *   <li>부정 표현의 수가 기준 문장과 같다</li>
     *   <li>새로 생긴 내용어(기준·주변·대상 어디에도 없는 낱말)가 {@value #MAX_NEW_CONTENT_WORDS}개 이하</li>
     *   <li>길이가 기준 문장의 0.5~2배, {@value #MAX_SENTENCE_CHARS}자 이하, 마커·펜스·표 줄·줄바꿈 없음</li>
     * </ol>
     *
     * @param polished  모델이 주변 글에 맞춰 다듬은 문장
     * @param reference 기준 문장 — 어기면 이것을 그대로 넣는다
     * @param context   넣을 자리의 앞뒤 문단(문서가 쓰는 용어·표기의 출처)
     */
    public static String sentence(String polished, String reference, String context, String subject) {
        if (polished == null || polished.isBlank()) return "다듬은 문장이 비었다";
        String p = polished.strip();
        String ref = reference == null ? "" : reference.strip();
        String ctx = context == null ? "" : context;
        if (p.contains("\n")) return "한 문단(줄바꿈 없는 문장)이 아니다";
        if (p.length() > MAX_SENTENCE_CHARS) return "너무 길다(" + MAX_SENTENCE_CHARS + "자 이하)";
        if (!ref.isEmpty()) {
            double ratio = (double) p.length() / ref.length();
            if (ratio < 0.5 || ratio > 2.0) return "길이가 기준 문장과 크게 다르다(" + Math.round(ratio * 100) + "%)";
        }
        if (p.contains("[이미지") || p.contains("[페이지") || p.contains("```") || p.startsWith("|")) return "표지·코드 펜스·표가 섞였다";

        Set<String> refFacts = new LinkedHashSet<>();
        refFacts.addAll(matches(NUMBER, ref));
        refFacts.addAll(identifiers(ref));
        for (String q : quoted(ref)) refFacts.add(q);
        for (String f : refFacts) {
            if (!p.contains(f)) return "기준 문장의 값이 빠졌다: " + f;
        }
        String allowed = ref + "\n" + ctx + "\n" + (subject == null ? "" : subject);
        for (String n : matches(NUMBER, p)) {
            if (!allowed.contains(n)) return "기준 문장·주변 글에 없는 숫자: " + n;
        }
        for (String id : identifiers(p)) {
            if (!allowed.contains(id)) return "기준 문장·주변 글에 없는 식별자: " + id;
        }
        if (count(NEGATION, p) != count(NEGATION, ref)) return "부정 표현의 수가 기준 문장과 다르다(뜻이 뒤집혔을 수 있다)";
        List<String> unknown = unknownWords(p, allowed);
        if (unknown.size() > MAX_NEW_CONTENT_WORDS) return "기준 문장·주변 글에 없는 말이 많다: " + String.join(", ", unknown);
        return null;
    }

    // ── 공통 ───────────────────────────────────────────────────────────────

    /**
     * {@code text} 의 낱말 중 {@code source} 에 없는 것 — 한국어 조사·어미를 감안해 낱말 끝을 두 글자까지 떼어 본다(2글자 이상 남을 때). 문장부호·한 글자
     * 낱말은 세지 않는다.
     */
    static List<String> unknownWords(String text, String source) {
        String src = source == null ? "" : source.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\\s+")) {
            String w = raw.replaceAll("^[\\p{Punct}“”‘’「」『』—–…·>*]+|[\\p{Punct}“”‘’「」『』—–…·*]+$", "").toLowerCase(Locale.ROOT);
            if (w.length() < 2) continue;
            if (known(w, src)) continue;
            out.add(w);
        }
        return out;
    }

    private static boolean known(String w, String src) {
        if (src.contains(w)) return true;
        for (int cut = 1; cut <= 2 && w.length() - cut >= 2; cut++) {
            if (src.contains(w.substring(0, w.length() - cut))) return true;
        }
        return false;
    }

    private static List<String> matches(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 식별자다운 낱말 — 2글자 이상이고 숫자·{@code _}·{@code .}·괄호·대문자 섞임 중 하나를 품은 것(평범한 영어 낱말은 표현으로 본다). */
    static List<String> identifiers(String text) {
        List<String> out = new ArrayList<>();
        for (String m : matches(IDENTIFIER, text)) {
            if (m.length() < 2) continue;
            boolean looksLikeCode = m.chars().anyMatch(Character::isDigit) || m.contains("_") || m.contains(".") || m.contains("(")
                    || (m.length() > 1 && !m.equals(m.toLowerCase(Locale.ROOT)) && !m.equals(capitalized(m)))
                    || m.equals(m.toUpperCase(Locale.ROOT)) && m.chars().anyMatch(Character::isLetter);
            if (looksLikeCode) out.add(m.replaceAll("\\.+$", ""));
        }
        return out;
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static List<String> quoted(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = QUOTED.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static int count(Pattern p, String text) {
        int n = 0;
        Matcher m = p.matcher(text == null ? "" : text);
        while (m.find()) n++;
        return n;
    }
}
