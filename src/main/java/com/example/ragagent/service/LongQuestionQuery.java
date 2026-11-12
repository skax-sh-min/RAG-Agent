package com.example.ragagent.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 긴 질문의 <b>검색용 질의</b>를 만드는 순수 규칙 — 질문 전문이 아니라 이것을 검색 축에 넣는다.
 *
 * <p><b>왜 필요한가.</b> 긴 질문은 대개 "지시 한 줄 + 붙여넣은 본문(로그·코드)"이다. 전문을 그대로 검색 축에 넣으면 ① 벡터는
 * 하나의 벡터에 전문을 뭉개 검색 의도가 흐려지고 ② 키워드(BM25) 축의 MATCH 식은 수천 개 항이 되며 ③ 3글자 미만 낱말은
 * 인덱스를 못 타는 {@code LIKE} 스캔으로 가고 ④ 질의 확장은 전문을 변형 3개로 다시 쓰느라 출력이 질문만큼 커진다.
 * 답변 프롬프트의 {@code [현재 질문]} 은 이 클래스와 무관하게 늘 전문이다 — 검색만 좁힌다
 * ({@code AgentState.effectiveSearchQuestion()} 와 같은 격리).
 *
 * <p>두 단계다.
 * <ul>
 *   <li><b>1단계({@value #SHORT_LIMIT}자 초과)</b> — LLM 없이 즉시. 벡터 검색문은 <b>앞부분 + 뒷부분</b>(지시는 앞이나 뒤에 온다),
 *       키워드 검색어는 질문 전체에서 뽑은 내용어 상위 {@value #MAX_DETERMINISTIC_TERMS}개(정확한 단어·식별자를 그대로 지킨다).</li>
 *   <li><b>2단계({@value #LLM_LIMIT}자 초과)</b> — 1단계 위에 LLM 이 만든 검색문·키워드를 얹는다
 *       ({@code QuestionCondenser.distill}). 키워드는 <b>합집합</b>이다 — 작은 모델은 식별자를 바꿔 말하기 쉬워서 정확한
 *       단어는 결정적 추출이 지키고 LLM 은 보탠다. 실패하면 1단계 결과가 그대로 쓰인다.</li>
 * </ul>
 */
public final class LongQuestionQuery {

    /** 이 길이 이하의 질문은 손대지 않는다(오늘과 같은 동작). 공백을 뗀 길이로 잰다. */
    public static final int SHORT_LIMIT = 500;

    /** 이 길이를 넘으면 LLM 으로 검색문·키워드를 뽑는다(2단계). */
    public static final int LLM_LIMIT = 1000;

    /** 벡터 검색문(앞 + 뒤)의 앞부분·뒷부분 글자 수 — 합쳐 {@value #SHORT_LIMIT}자 안팎. */
    static final int HEAD_CHARS = 300;
    static final int TAIL_CHARS = 200;

    /** 결정적 키워드 검색어의 개수 상한과 합집합의 상한. 항 하나가 어간까지 둘이 되므로 MATCH 식은 이 두 배 안쪽이다. */
    static final int MAX_DETERMINISTIC_TERMS = 20;
    static final int MAX_MERGED_TERMS = 30;

    /** 낱말 길이 범위. 3 미만은 trigram 이 안 만들어져 LIKE 스캔으로 가고, 긴 것은 해시·base64 같은 잡음이다. */
    static final int MIN_TERM_LEN = 3;
    static final int MAX_TERM_LEN = 40;

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s\\p{Punct}]+");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private LongQuestionQuery() {}

    /** 1단계 대상인가 — 공백을 뗀 길이가 {@value #SHORT_LIMIT}자를 넘는다. */
    public static boolean isLong(String question) {
        return question != null && question.strip().length() > SHORT_LIMIT;
    }

    /** 2단계(LLM) 대상인가 — 공백을 뗀 길이가 {@value #LLM_LIMIT}자를 넘는다. */
    public static boolean needsLlm(String question) {
        return question != null && question.strip().length() > LLM_LIMIT;
    }

    /**
     * 이후 턴의 이력이 원문 대신 싣을 <b>LLM 이 만든</b> 검색문 — 없으면 {@code null}. 2단계(1,000자 초과) 질문이고 검색문이 1단계의
     * 결정적 앞뒤 발췌({@link #headTail})와 다를 때만이다: LLM 호출이 꺼졌거나 실패하면 검색문은 그 발췌 그대로인데, 그것은 요약이 아니라
     * 잘린 원문이라(앞 300 + 뒤 200자 — 중간 내용이 사라진다) 이력의 대체물로 쓰지 않는다.
     */
    public static String distilledOrNull(String question, String searchQuestion) {
        if (!needsLlm(question) || searchQuestion == null || searchQuestion.isBlank()) return null;
        return searchQuestion.strip().equals(headTail(question).strip()) ? null : searchQuestion.strip();
    }

    /**
     * 벡터 검색문(1단계) — 공백을 한 칸으로 접은 질문의 앞 {@value #HEAD_CHARS}자 + 뒤 {@value #TAIL_CHARS}자.
     * 중간을 자른 자리는 {@code " … "} 로 표시한다. 낱말 중간에서 자르지 않도록 경계를 가까운 공백으로 당긴다.
     * {@link #SHORT_LIMIT} 이하면 질문 그대로(공백만 접는다).
     */
    public static String headTail(String question) {
        if (question == null) return "";
        String text = WHITESPACE_RUN.matcher(question.strip()).replaceAll(" ");
        if (text.length() <= SHORT_LIMIT) return text;
        int headEnd = boundaryBefore(text, HEAD_CHARS);
        int tailStart = boundaryAfter(text, text.length() - TAIL_CHARS);
        if (tailStart <= headEnd) return text.substring(0, SHORT_LIMIT);
        return text.substring(0, headEnd).strip() + " … " + text.substring(tailStart).strip();
    }

    /**
     * 키워드 검색어(1단계) — 질문 전체에서 뽑은 내용어 상위 {@code max}개, 질문에 나온 <b>그대로의 형태</b>.
     *
     * <p><b>구간 우선, 그 안에서 점수.</b> 순서는 ① 앞·뒤 구간(= 지시가 있을 법한 곳)의 식별자형 낱말 ② 앞·뒤 구간의 나머지 ③ 본문의
     * 식별자형 ④ 본문의 나머지이고, 같은 구간 안에서는 {@code (1 + ln 빈도) × min(길이, 12)} 점수순(동점은 먼저 나온 순)이다. 점수만
     * 쓰면 붙여넣은 본문이 길수록 흔한 낱말(로그의 반복 문구)이 빈도로 앞서 정작 지시에 있는 식별자가 밀려난다 — 구간 우선이 그것을
     * 막는다. 식별자형 = 낱말 중간에 대문자가 있거나(CamelCase · 전부 대문자) 글자와 숫자가 섞인 것({@code FooService}, {@code ERR4021}).
     * 의문·기능어와 조사·어미만 있는 토큰은 {@link QuestionKeywords} 와 같은 규칙으로 뺀다. 3글자 미만과 40자 초과는 뺀다.
     */
    public static List<String> keywordTerms(String question, int max) {
        if (question == null || question.isBlank() || max <= 0) return List.of();
        String text = WHITESPACE_RUN.matcher(question.strip()).replaceAll(" ");
        int headEnd = Math.min(text.length(), HEAD_CHARS);
        int tailStart = Math.max(0, text.length() - TAIL_CHARS);

        Map<String, Stat> stats = new LinkedHashMap<>();
        int pos = 0;
        for (String raw : WORD_SPLIT.split(text)) {
            int at = text.indexOf(raw, pos);
            if (raw.isEmpty() || at < 0) continue;
            pos = at + raw.length();
            if (raw.length() < MIN_TERM_LEN || raw.length() > MAX_TERM_LEN) continue;
            if (QuestionKeywords.keyword(raw) == null) continue;     // 의문·기능어·조사만
            Stat st = stats.computeIfAbsent(raw.toLowerCase(Locale.ROOT), k -> new Stat(raw, stats.size()));
            st.count++;
            if (at < headEnd || at >= tailStart) st.inInstruction = true;
        }
        List<Stat> ranked = new ArrayList<>(stats.values());
        ranked.sort((x, y) -> {
            int t = Integer.compare(x.tier(), y.tier());
            if (t != 0) return t;
            int c = Double.compare(y.score(), x.score());
            return c != 0 ? c : Integer.compare(x.first, y.first);
        });
        List<String> out = new ArrayList<>(Math.min(max, ranked.size()));
        for (Stat st : ranked) {
            if (out.size() >= max) break;
            out.add(st.term);
        }
        return out;
    }

    /** 결정적 키워드 검색어 기본 개수({@value #MAX_DETERMINISTIC_TERMS}). */
    public static List<String> keywordTerms(String question) {
        return keywordTerms(question, MAX_DETERMINISTIC_TERMS);
    }

    /**
     * 합집합 — {@code primary}(LLM 이 뽑은 것)를 먼저, 이어서 {@code secondary}(결정적 추출). 대소문자를 무시해 중복을 걸러내고
     * {@code max} 개에서 멈춘다. 항 하나라도 3글자 미만이거나 40자 초과면 뺀다(LLM 이 짧은 약어를 내놓아도 LIKE 스캔이 되지 않게).
     */
    public static List<String> mergeTerms(List<String> primary, List<String> secondary, int max) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (List<String> list : List.of(primary == null ? List.<String>of() : primary,
                                         secondary == null ? List.<String>of() : secondary)) {
            for (String t : list) {
                if (out.size() >= max) return out;
                if (t == null) continue;
                String term = t.strip();
                if (term.length() < MIN_TERM_LEN || term.length() > MAX_TERM_LEN) continue;
                if (seen.add(term.toLowerCase(Locale.ROOT))) out.add(term);
            }
        }
        return out;
    }

    /** 키워드 축에 넘길 문자열 — 낱말을 공백으로 잇는다(축이 다시 토큰화해 MATCH 식을 만든다). */
    public static String joinTerms(List<String> terms) {
        return terms == null || terms.isEmpty() ? null : String.join(" ", terms);
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    /** 낱말 중간에 대문자가 있거나 글자와 숫자가 섞여 있다 — 클래스·메서드·오류 코드처럼 질문이 바꿔 말할 수 없는 이름. */
    private static boolean identifierLike(String term) {
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) return true;
            if (Character.isDigit(c)) digit = true;
            else if (Character.isLetter(c)) letter = true;
        }
        return letter && digit;
    }

    /** 낱말 하나의 집계 — 처음 나온 순서, 빈도, 앞·뒤(지시) 구간에 나왔는가. */
    private static final class Stat {
        final String term;
        final int first;
        int count;
        boolean inInstruction;

        Stat(String term, int first) {
            this.term = term;
            this.first = first;
        }

        double score() {
            return (1 + Math.log(count)) * Math.min(term.length(), 12);
        }

        /** 0 = 지시 구간의 식별자형, 1 = 지시 구간의 나머지, 2 = 본문의 식별자형, 3 = 본문의 나머지. */
        int tier() {
            return (inInstruction ? 0 : 2) + (identifierLike(term) ? 0 : 1);
        }
    }

    /** {@code limit} 이하에서 가장 가까운(뒤쪽) 공백 위치 — 없으면 {@code limit}. */
    private static int boundaryBefore(String text, int limit) {
        int i = text.lastIndexOf(' ', limit);
        return i > limit / 2 ? i : limit;
    }

    /** {@code from} 이상에서 가장 가까운(앞쪽) 공백 다음 위치 — 없으면 {@code from}. */
    private static int boundaryAfter(String text, int from) {
        int i = text.indexOf(' ', from);
        return (i >= 0 && i - from < TAIL_CHARS / 2) ? i + 1 : from;
    }
}
