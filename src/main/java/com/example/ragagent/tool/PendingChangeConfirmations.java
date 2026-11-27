package com.example.ragagent.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 채팅에서 유지보수를 시작하기 전의 <b>되묻기</b> 상태(§6.33 4b) — "A 를 B 로 바꾸는 수정안을 만들까요?" 에 대한 답을 기다리는 요청 하나.
 *
 * <p><b>왜 되묻는가.</b> 소형 모델이 채팅 한 줄에서 바꿀 말과 새 말을 잘못 뽑으면 엉뚱한 수정안 작업이 시작된다. 채팅이 먼저 그 둘을 되읽어 주고
 * 사람이 "네" 라고 답해야 시작한다. 시작해도 코퍼스는 승인 전에는 바뀌지 않지만(수정안 대기열), 잘못된 작업을 만들고 치우는 일을 사람에게 떠넘기지 않는다.
 *
 * <p><b>규칙은 결정적이다 — LLM 이 "네"를 해석하지 않는다.</b>
 * <ul>
 *   <li>대기는 (사용자, 대화) 당 하나이고 새 되묻기가 이전 것을 덮는다. 메모리에만 있다(재기동하면 사라지고, {@value #TTL_MINUTES}분이 지나면 무효).</li>
 *   <li>그 대화의 <b>바로 다음 메시지</b>가 해소한다: 긍정 표현({@link #isAffirmative})과 <b>정확히</b> 같을 때만 확정이고, 그 밖의 어떤 말이든 취소다
 *       (대기는 어느 쪽이든 지워진다 — "네 근데 …" 같은 섞인 말은 확정으로 읽지 않는다).</li>
 *   <li>확정된 대기는 한 번만 쓰인다(꺼내면 지워진다).</li>
 *   <li>만료된 되묻기에 긍정 표현으로 답하면 시작하지 않고 만료를 알린다({@link #resolveReply} → {@link Outcome#EXPIRED}).</li>
 * </ul>
 * 관리자 호출자만 이 저장소를 부른다(호출하는 쪽이 보장) — 일반 사용자의 "네" 는 대기를 건드리지 못한다.
 */
@Component
public class PendingChangeConfirmations {

    public static final int TTL_MINUTES = 5;
    private static final long TTL_MS = TTL_MINUTES * 60_000L;
    /** 동시에 쥐는 대기의 상한 — 관리자가 소수이므로 넉넉한 안전판일 뿐이다. */
    static final int MAX_ENTRIES = 1_000;

    /**
     * 되묻는 중인 요청 — 확정되면 <b>이 값 그대로</b> 수정안 작업이 시작된다(다시 해석하지 않는다).
     *
     * @param type 자연어 수정의 해석된 유형({@code deprecate}·{@code remove}·{@code add_note}·{@code revise}) — 글자 바꾸기면 {@code null}
     */
    public record Pending(String instruction, String from, String to, String type, String subject, String content, String replacement) {
        /** 글자 바꾸기 되묻기. */
        public Pending(String instruction, String from, String to) {
            this(instruction, from, to, null, null, null, null);
        }
    }

    private record Entry(Pending pending, long expiresAtMs) {}

    private static final Set<String> AFFIRMATIVE = Set.of(
            "네", "넵", "넹", "예", "응", "어", "그래", "그래요", "좋아", "좋아요", "맞아", "맞아요", "맞습니다", "그렇습니다",
            "진행", "진행해", "진행해줘", "진행해주세요", "진행하세요", "만들어", "만들어줘", "만들어주세요",
            "시작", "시작해", "시작해줘", "시작해주세요", "해줘", "해주세요",
            "ㅇㅇ", "ㅇㅋ", "오케이", "ok", "okay", "yes", "y", "yep", "sure", "go", "proceed");

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clockMs;

    @Autowired
    public PendingChangeConfirmations() {
        this(System::currentTimeMillis);
    }

    /** 테스트용 — 시계를 주입한다. */
    PendingChangeConfirmations(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /** 되묻는 중인 요청을 기록한다 — 같은 (사용자, 대화) 의 이전 대기는 덮어쓴다. */
    public void put(String userId, String threadId, Pending pending) {
        long now = clockMs.getAsLong();
        if (entries.size() >= MAX_ENTRIES) entries.values().removeIf(e -> e.expiresAtMs() <= now);
        if (entries.size() >= MAX_ENTRIES) return;   // 그래도 가득 차면 되묻기를 기록하지 않는다 — 확정이 없으니 시작도 없다(안전한 쪽)
        entries.put(key(userId, threadId), new Entry(pending, now + TTL_MS));
    }

    /**
     * 이 (사용자, 대화) 의 다음 메시지로 대기를 해소한다 — 대기가 있으면 <b>무엇을 말했든 지운다</b>. 그 메시지가 긍정 표현과 정확히 같고 대기가
     * 만료되지 않았을 때만 확정된 요청을 돌려준다.
     */
    public Optional<Pending> resolve(String userId, String threadId, String message) {
        Resolution r = resolveReply(userId, threadId, message);
        return r.outcome() == Outcome.CONFIRMED ? Optional.of(r.pending()) : Optional.empty();
    }

    /** 되묻기에 대한 답의 결과. */
    public enum Outcome {
        /** 기다리던 되묻기가 없거나, 긍정 표현이 아니라 취소됐다 — 평소처럼 진행한다. */
        NONE,
        /** 만료 전의 긍정 표현 — 기억한 요청 그대로 시작한다. */
        CONFIRMED,
        /**
         * 긍정 표현이지만 되묻기가 {@value #TTL_MINUTES}분을 넘겨 만료됐다 — 시작하지 않고 만료를 알린다. 이 구분이 없으면 늦은 "네" 가 평범한 질문으로
         * 흘러 모델이 "수정안을 작성하겠습니다" 같은 답을 지어내, 아무 작업도 없는데 시작된 것처럼 보였다.
         */
        EXPIRED
    }

    /** @param pending {@link Outcome#NONE} 이면 {@code null}, 그 밖에는 기억했던 요청(만료됐어도 무엇이 만료됐는지 알리려고 싣는다) */
    public record Resolution(Outcome outcome, Pending pending) {
        static final Resolution NONE = new Resolution(Outcome.NONE, null);
    }

    /**
     * {@link #resolve} 와 같은 규칙으로 대기를 해소하되 만료된 되묻기에 대한 긍정 표현을 {@link Outcome#EXPIRED} 로 가른다. 대기는 어느 경우든 지워진다.
     * 만료된 대기는 메모리에서 바로 치우지 않으므로 그 대화의 다음 메시지까지는 알아볼 수 있다(재기동하면 사라져 알아볼 수 없다).
     */
    public Resolution resolveReply(String userId, String threadId, String message) {
        Entry e = entries.remove(key(userId, threadId));
        if (e == null || !isAffirmative(message)) return Resolution.NONE;
        if (e.expiresAtMs() <= clockMs.getAsLong()) return new Resolution(Outcome.EXPIRED, e.pending());
        return new Resolution(Outcome.CONFIRMED, e.pending());
    }

    /** 이 (사용자, 대화) 가 되묻기에 답하는 중인가 — 화면 안내·테스트용. 지우지 않는다. */
    public boolean isPending(String userId, String threadId) {
        Entry e = entries.get(key(userId, threadId));
        return e != null && e.expiresAtMs() > clockMs.getAsLong();
    }

    /**
     * 메시지가 긍정 표현 <b>하나</b>인가 — 공백(안의 것도)·끝 문장부호({@code . ! ~ ?})와 대소문자를 무시하고 <b>전체가 정확히 일치</b>할 때만.
     * 일부만 포함해도 긍정으로 읽으면 "네, 그런데 B 가 아니라 C 로…" 가 확정이 된다.
     */
    public static boolean isAffirmative(String message) {
        if (message == null) return false;
        // 공백은 전부 지운 뒤 비교한다 — "진행해 주세요"·"진행해주세요" 가 같다. 끝 문장부호도 지운다.
        String s = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replaceAll("[.!~?]+$", "");
        return !s.isEmpty() && AFFIRMATIVE.contains(s);
    }

    private static String key(String userId, String threadId) {
        return (userId == null ? "" : userId) + '\u0000' + (threadId == null ? "" : threadId);
    }
}
