package com.example.ragagent.workflow;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 유지보수의 <b>치환 규칙 하나</b>(§6.33 4b) — 수정안을 만드는 쪽(청크 본문·맥락·키워드), 적용하는 쪽(섹션 제목·남은 말 검사), 변환 MD 되쓰기가
 * <b>같은 규칙</b>을 써야 한다. 한 곳만 대소문자 변형을 바꾸면 청크와 MD 가 다시 갈라져 재인덱싱이 수정을 되돌린다.
 *
 * <p>{@code caseVariants=false}(기본)이면 {@link String#replace} 와 같다 — 글자 그대로. {@code true} 이면 {@code from} 과 <b>대소문자만 다른</b>
 * 모든 표기도 찾아 <b>그 표기의 대소문자 모양에 맞춰</b> 바꾼다: 모두 대문자({@code ORACLE})면 {@code to} 를 대문자로, 모두 소문자({@code oracle})면 소문자로,
 * {@code from} 과 정확히 같으면 {@code to} 그대로, 그 밖의 섞인 표기({@code oRacle})는 {@code to} 그대로. 새 말이 한글처럼 대소문자가 없으면 모양이
 * 달라지지 않는다.
 *
 * <p>변형 치환은 <b>식별자 의미를 바꿀 수 있다</b> — {@code "ORACLE".equals(dbType)} 는 코드 속 문자열 상수라 런타임 값과 맞춰야 한다. 그래서 켜는 것은
 * 요청 때의 명시적 선택이고, 결과는 사람이 Diff 로 검토한다.
 */
public record TermReplacement(String from, String to, boolean caseVariants) {

    /** 글자 그대로의 치환 규칙 — 대소문자 변형은 건드리지 않는다. */
    public static TermReplacement exact(String from, String to) {
        return new TermReplacement(from, to, false);
    }

    private Pattern pattern() {
        return Pattern.compile(Pattern.quote(from), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** 이 규칙이 찾는 표기의 발생 수 — 규칙이 대소문자 변형을 포함하면 그것까지, 아니면 글자 그대로만. 겹치지 않게 센다. */
    public int count(String text) {
        if (text == null || from.isEmpty()) return 0;
        if (!caseVariants) return MarkdownWriteBack.occurrences(text, from);
        int n = 0;
        Matcher m = pattern().matcher(text);
        while (m.find()) n++;
        return n;
    }

    /** 이 규칙이 바꿀 것이 있는가. */
    public boolean matches(String text) {
        return count(text) > 0;
    }

    /** {@code from} 과 <b>대소문자만 다른</b> 표기의 발생 수(글자 그대로인 것은 세지 않는다) — 규칙의 옵션과 무관하게 센다(리포트의 투명성). */
    public int variantCount(String text) {
        if (text == null || from.isEmpty()) return 0;
        int n = 0;
        Matcher m = pattern().matcher(text);
        while (m.find()) if (!m.group().equals(from)) n++;
        return n;
    }

    /** 규칙대로 바꾼다. 바꿀 것이 없으면 입력을 그대로 돌려준다. */
    public String apply(String text) {
        if (text == null) return null;
        if (!caseVariants) return text.replace(from, to);
        return pattern().matcher(text).replaceAll(r -> Matcher.quoteReplacement(shaped(r.group())));
    }

    /** 찾은 표기의 대소문자 모양에 맞춘 새 말. */
    String shaped(String matched) {
        if (matched.equals(from)) return to;
        if (matched.equals(from.toUpperCase(Locale.ROOT))) return to.toUpperCase(Locale.ROOT);
        if (matched.equals(from.toLowerCase(Locale.ROOT))) return to.toLowerCase(Locale.ROOT);
        return to;
    }
}
