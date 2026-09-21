package com.example.ragagent.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 추천 후보 검색의 키워드 추출 — 입력 문장에서 의문·기능어와 조사·어미를 걷어내고 내용어만 남긴다.
 *
 * <p>추천 조회는 예전에 입력 문장 <b>통째로</b> 부분 일치({@code LIKE '%문장%'})였다. 그래서 저장된
 * 질문이 {@code sqlite 연결 설정 방법} 일 때 {@code sqlite 연결은 어떻게} 는 못 찾았다 — 어순·조사가
 * 조금만 달라도 실패한다. 이제 서비스가 여기서 키워드를 뽑아 리포지토리에 넘기고, 리포지토리는
 * 키워드 <b>전부</b>(AND)를 각각 부분 일치로 건다.
 *
 * <p><b>형태소 분석기 없이 접미사 목록으로만 한다.</b> 그래도 안전한 이유는 이 클래스가 하는 일이
 * 전부 <em>뒤를 자르는 것</em>이라 결과가 언제나 원래 토큰의 <b>접두사</b>이기 때문이다 — 부분 일치에서
 * 접두사는 원래 토큰이 맞히던 행을 전부 맞히고 더 맞힌다. 즉 과잉 절단은 재현율을 깎지 않고 정밀도만
 * 깎으며, 그 정밀도는 AND 매칭의 다른 키워드가 잡아 준다. 실제로 위험한 것은 어간이 한 글자로 줄어
 * 거의 아무 행이나 맞히는 경우뿐이고, 그것을 {@code 2음절 어간} 규칙이 막는다: 한 글자 조사(의·이·가·도·로·과)는
 * 어간이 2음절 이상 남을 때만 벗긴다 — {@code 정의·차이·추가·속도·경로·결과} 같은 2음절 내용어가 통째로
 * 조사처럼 보이기 때문이다. 두 글자 이상 조사(에서·으로·까지…)는 그런 충돌이 없어 1음절 어간까지
 * 허용한다({@code 탭에서} → {@code 탭}, {@code 값으로} → {@code 값}).
 *
 * <p>어미를 조사보다 <b>먼저</b> 벗긴다 — {@code 방법은요} 는 어미 {@code 요} 뒤에 조사 {@code 은} 이 숨어 있다.
 * 각각 한 번씩만 벗긴다. 벗긴 어간이 다시 기능어이면 토큰을 통째로 버린다({@code 이것은} → {@code 이것} → 제거,
 * {@code 가능한가요} → {@code 가능} → 제거).
 *
 * <p>FTS5 를 쓰지 않는 이유: 이 앱의 {@code trigram} 토크나이저는 3글자 미만 토큰이 0 이라 {@code 연결·설정}
 * 같은 한국어 2음절 키워드에 무력하고, {@code unicode61} + 접두 검색은 {@code conversation_turns.question}
 * 용 FTS 테이블과 동기화를 새로 들인다. 대화 턴 규모에서는 지금의 LIKE 스캔에 키워드 몇 개를 얹는 쪽이
 * 훨씬 싸다.
 *
 * <p>순수 클래스({@code ChunkDiff}/{@code HistoryPolicy} 선례). 목록은 상수이고 {@code QuestionKeywordsTest}
 * 가 고정한다 — 단어를 더하고 빼는 자리는 여기뿐이다.
 */
public final class QuestionKeywords {

    /** 리포지토리 술어 수의 상한 — 이보다 많으면 앞에서부터 쓴다. */
    static final int MAX_KEYWORDS = 6;

    /** 토큰 안에 남기는 기호 — {@code application.yml}, {@code sqlite-vec}, {@code max_tokens}, {@code /api/v1}, {@code C#}. */
    private static final String TOKEN_SYMBOLS = ".-_/:#@";

    /** 토큰 뒤에 붙은 문장부호로 보고 벗기는 기호(앞은 그대로 — {@code .env}, {@code /api}, {@code #tag}). */
    private static final String TRAILING_PUNCT = ".:/";

    /** 독립된 토큰으로 왔을 때 통째로 버리는 의문·기능어. */
    private static final Set<String> STOPWORDS = Set.of(
            // 의문사
            "어떻게", "어떤", "어느", "어디", "어디서", "어디에", "어디에서", "언제", "왜", "무엇", "무엇을", "무엇이",
            "무엇인가요", "뭐", "뭘", "뭐야", "뭐지", "뭔지", "뭔가", "뭔가요", "누구", "누가", "몇", "얼마", "얼마나",
            "무슨", "어째서", "어떠한", "어떤가요", "어때요", "어때", "어떤데요", "어떻게요", "어떡해", "어떡하죠", "어찌",
            // 지시어
            "이거", "그거", "저거", "이것", "그것", "저것", "여기", "거기", "저기", "이런", "그런", "저런",
            "얘", "걔", "쟤", "요거", "이건", "그건", "저건", "이게", "그게", "저게", "이걸", "그걸", "저걸",
            // 접속·부사
            "그리고", "그런데", "근데", "하지만", "그래서", "그러면", "그럼", "또는", "혹은", "및", "등", "등등",
            "좀", "좀더", "더", "다시", "그냥", "혹시", "혹시나", "만약", "지금", "이제", "바로", "너무", "정말",
            "진짜", "아주", "매우", "약간", "자주", "항상", "계속", "이미", "아직", "거의", "대충", "일단", "우선",
            "먼저", "역시", "물론", "아마", "즉", "안", "못", "잘", "또", "꼭", "다",
            // 관계·형식 표현
            "대해", "대해서", "대한", "대하여", "관련", "관련해", "관련해서", "관련된", "관련하여", "관해", "관해서",
            "관한", "위해", "위해서", "위한", "통해", "통해서", "통한", "경우", "경우에", "때", "때문", "때문에",
            "것", "것은", "것이", "것을", "것도", "거", "거는", "거를", "게", "건", "걸", "정도", "같은", "같이",
            "처럼", "대로", "수", "수가", "수는", "수도", "있는", "없는", "하는", "되는", "된", "한", "할", "될",
            "하", "되", "왜냐면", "왜냐하면", "예를", "예를들어", "예로", "가능", "가능한", "가능한지", "여부",
            // 요청·서술 표현
            "알려줘", "알려줘요", "알려주세요", "알려주라", "알려", "알려줄래", "설명해줘", "설명해줘요",
            "설명해주세요", "설명해", "해줘", "해줘요", "해주세요", "해주라", "주세요", "줘", "부탁", "부탁해",
            "부탁해요", "부탁합니다", "부탁드립니다", "궁금", "궁금해", "궁금해요", "궁금합니다", "궁금한데",
            "궁금한데요", "있나요", "있나", "있어", "있어요", "있을까", "있을까요", "있는지", "있습니까", "있습니다",
            "있음", "없나요", "없나", "없어", "없어요", "없을까", "없을까요", "없는지", "없습니까", "없습니다", "없음",
            "되나요", "되나", "돼", "돼요", "될까", "될까요", "되는지", "됩니까", "됩니다", "되는데", "되는데요",
            "됐는데", "안돼", "안돼요", "안되는데", "안되나요", "하나요", "하나", "해", "해요", "할까", "할까요",
            "하는지", "합니까", "합니다", "하는데", "하는데요", "했는데", "했는데요", "인가요", "인가", "인지",
            "일까", "일까요", "입니까", "입니다", "이에요", "예요", "이야", "야", "죠", "이죠", "하죠", "되죠",
            "맞죠", "맞나요", "맞아요", "맞나", "맞지", "그런가요", "그런가", "그럴까요", "그렇죠", "그렇지",
            "그렇네요", "그러네요", "아닌가요", "아닌가", "아니야", "아닌지", "좋을까요", "좋나요", "괜찮나요",
            "괜찮은가요", "괜찮을까요", "가능한가요", "가능할까요", "알고싶어요", "알고싶다", "싶어요", "싶은데",
            "싶은데요", "싶습니다", "하고싶어요", "원해요", "가나요",
            // 영문
            "how", "to", "what", "which", "when", "where", "why", "who", "whom", "is", "are", "was", "were", "be",
            "been", "do", "does", "did", "can", "could", "should", "would", "will", "the", "a", "an", "of", "in",
            "on", "at", "for", "with", "and", "or", "but", "not", "no", "please", "me", "my", "i", "we", "our",
            "you", "your", "it", "its", "this", "that", "these", "those", "there", "here", "about", "from", "by",
            "as", "if", "then", "than", "so", "any", "some", "all", "want", "need", "know", "tell", "show",
            "explain", "help", "thanks", "thank", "ok", "okay", "hi", "hello", "something", "anything");

    /** 토큰 끝에 붙은 의문형·요청형·서술형 어미 — 조사보다 먼저 벗긴다. 긴 것부터 대조한다. */
    private static final List<String> ENDINGS = byLengthDesc(List.of(
            // 하-
            "하나요", "하나", "하는지", "하는데요", "하는데", "하는", "하려면", "하려고", "하면", "하고", "해서",
            "해줘요", "해줘", "해주세요", "해주라", "해줄래", "해봐", "하기", "할까요", "할까", "할지", "할래",
            "합니까", "합니다", "해요", "했어요", "했는데요", "했는데", "했나요", "했는지", "하죠", "하지",
            "하네요", "하네", "하고싶어요", "하고싶은데", "하고싶다", "하고싶어",
            // 되-
            "되나요", "되나", "되는지", "되는데요", "되는데", "되는", "되려면", "되면", "되고", "돼서", "되어서",
            "될까요", "될까", "될지", "됩니까", "됩니다", "돼요", "됐어요", "됐는데", "됐나요", "됐는지", "되죠",
            "되지", "되네요",
            // 이-/인-
            "인가요", "인가", "인지", "인데요", "인데", "일까요", "일까", "일지", "입니까", "입니다", "이에요",
            "예요", "이야", "이죠", "이지", "이라면", "이면", "이라서", "이라고", "라고", "이란", "이라는", "라는",
            "이네요",
            // 한-
            "한가요", "한지", "한데요", "한데", "한지요",
            // 있-/없-
            "있나요", "있는지", "있어요", "있을까요", "있을까", "있습니까", "없나요", "없는지", "없어요",
            "없을까요", "없을까", "없습니까",
            // 일반
            "습니까", "습니다", "나요", "까요", "는지", "은지", "니까", "니다", "어요", "아요", "네요", "데요",
            "요", "죠", "야"));

    /** 토큰 끝에 붙은 조사. 긴 것부터 대조한다. */
    private static final List<String> PARTICLES = byLengthDesc(List.of(
            "에서는", "에서도", "에서의", "에서만", "으로는", "으로도", "으로의", "으로만", "으로서", "으로써",
            "에게는", "에게도", "한테는", "한테도", "까지는", "까지도", "까지만", "부터는", "부터도", "부터만",
            "이라도", "라도", "이라는", "라는", "이란", "로서", "로써", "에서", "에게", "에는", "에도", "에만",
            "한테", "께서", "으로", "까지", "부터", "처럼", "보다", "마다", "조차", "마저", "밖에", "이나", "이랑",
            "하고", "이든", "든지", "이며", "대로", "만큼", "랑", "과", "와", "로",
            "은", "는", "이", "가", "을", "를", "의", "에", "도", "만", "께", "나"));

    private QuestionKeywords() {}

    /**
     * 입력 문장의 검색 키워드. 아무것도 남지 않으면 빈 목록 — 호출자는 그때 입력 전체 부분 일치로
     * 폴백해 어떤 입력도 예전보다 나빠지지 않게 한다.
     */
    public static List<String> extract(String q) {
        if (q == null || q.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : tokenize(q)) {
            String kw = keyword(raw);
            if (kw == null) continue;
            out.add(kw);
            if (out.size() >= MAX_KEYWORDS) break;
        }
        return List.copyOf(out);
    }

    /** 공백·문장부호로 자르되 {@link #TOKEN_SYMBOLS} 는 토큰 안에 남긴다. */
    static List<String> tokenize(String q) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < q.length(); ) {
            int cp = q.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || TOKEN_SYMBOLS.indexOf(cp) >= 0) {
                cur.appendCodePoint(cp);
            } else if (cur.length() > 0) {
                tokens.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
    }

    /** 토큰 하나 → 키워드, 또는 버릴 때 {@code null}. */
    static String keyword(String raw) {
        String token = trimTrailingPunct(raw).toLowerCase(Locale.ROOT);
        if (token.isEmpty()) return null;
        if (isFunctionWord(token)) return null;

        boolean stripped = false;
        String afterEnding = stripSuffix(token, ENDINGS, true);
        if (afterEnding == null) return null;             // 어간이 기능어이거나 한 음절 용언(두나요·쓰나요)
        if (!afterEnding.equals(token)) { token = afterEnding; stripped = true; }

        String afterParticle = stripSuffix(token, PARTICLES, false);
        if (afterParticle == null) return null;
        if (!afterParticle.equals(token)) { token = afterParticle; stripped = true; }

        if (isFunctionWord(token)) return null;
        // 벗겨서 한 글자만 남은 한글 어간(값·키·탭)은 내용어라 남기고, 원래부터 한 글자인 토큰(a·안)은 버린다.
        if (token.length() < 2 && !(stripped && endsWithHangul(token))) return null;
        return token;
    }

    /**
     * 목록의 접미사 하나를 벗긴다. 어간이 기능어이면 {@code null}(토큰 전체를 버린다), 벗길 수 없으면
     * 토큰 그대로. 한 글자 접미사는 어간이 2음절 이상 남을 때만 벗긴다 — 클래스 설명의 {@code 2음절 어간}
     * 규칙. 두 글자 이상 접미사 뒤에 한 음절만 남으면 조사({@code 값으로} → {@code 값})는 어간을 남기고,
     * 어미({@code dropShortStem})는 토큰을 버린다 — {@code 두나요·쓰나요·가나요} 의 한 음절 용언 어간은
     * 내용어가 아닌데 AND 매칭에서는 키워드 하나가 멀쩡한 후보를 떨어뜨린다.
     */
    private static String stripSuffix(String token, List<String> suffixes, boolean dropShortStem) {
        if (!endsWithHangul(token)) return token;
        for (String suffix : suffixes) {
            if (token.length() <= suffix.length() || !token.endsWith(suffix)) continue;
            String stem = token.substring(0, token.length() - suffix.length());   // 'sqlite로' → 'sqlite', '8080으로' → '8080' 도 여기서
            if (isFunctionWord(stem)) return null;
            if (stem.length() >= 2) return stem;
            if (suffix.length() >= 2) return dropShortStem ? null : stem;
            return token;                                 // 한 글자 접미사 + 한 음절 어간(정의·경로) — 가장 긴 접미사가 걸리면 더 짧은 것은 보지 않는다
        }
        return token;
    }

    private static boolean isFunctionWord(String token) {
        return STOPWORDS.contains(token) || ENDINGS.contains(token) || PARTICLES.contains(token);
    }

    private static String trimTrailingPunct(String s) {
        int end = s.length();
        while (end > 0 && TRAILING_PUNCT.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end);
    }

    private static boolean endsWithHangul(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    private static List<String> byLengthDesc(List<String> items) {
        List<String> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(sorted);
    }
}
