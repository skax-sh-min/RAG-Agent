package com.example.ragagent.model;

import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingWire;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * {@code /settings} 의 "생각(추론) 수준 — 호출 지점별" 카드가 그리는 전부(PLAN §6.29 ⑦). 숫자는 {@code ThinkingPreviewService} 가
 * <b>런타임이 쓰는 함수 그대로</b> 계산해 여기 채우고, 화면은 이 값을 읽어 그리기만 한다 — JS 는 계산하지 않는다(JS 테스트
 * 하네스가 없어 계산을 서버에 둔 {@code ChunkDiff}·{@code ChunkRow} 선례).
 *
 * <p>사이트마다 <b>네 수준 전부</b>({@link Row#cells()})를 미리 계산해 둔다. 드롭다운을 바꾸면 화면은 미리 그려 둔 칸을 보이기만
 * 하고, 저장된 값과의 차이도 수준마다 미리 계산돼 있다({@link Cell#diff()}).
 *
 * <p>문구는 서비스가 요청 로케일의 메시지 번들로 <b>이미 푼 문자열</b>이다. 숫자·열거형은 그대로 타입으로 남겨 테스트가 문구가 아니라
 * 값을 본다. 번들 키가 빠지면 화면이 아니라 계산 시점에 예외로 드러난다({@code MessageSource.getMessage}) — 템플릿에서 푸는
 * 방식은 빠진 키를 {@code ??key??} 로만 보여 주어 조용히 지나간다.
 */
public record ThinkingPreview(Instant computedAt, Basis basis, List<Group> groups) {

    private static final java.time.format.DateTimeFormatter CLOCK =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());

    /** 계산 시각 — 숫자는 계산 시점의 스냅샷(라우팅 상태·창·설정값)이라 기준 줄에 시각을 적는다. */
    public String computedAtText() {
        return CLOCK.format(computedAt);
    }

    /**
     * 계산 기준 줄 — 이 숫자들이 다 이 값 위에 서 있다. 가정(질문·답변 길이)과 토큰 추정 계수가 함께 있는 이유는, 모든 예산 숫자가
     * {@code TokenEstimator}(한글 1자 = 1토큰) 위에 있어 그 추정이 얼마나 맞는지가 같은 화면에 있어야 하기 때문이다.
     *
     * @param estimateRatio 서버가 센 토큰 ÷ 추정. 표본이 없으면 {@code null}
     */
    public record Basis(int maxTokens, int topK, int chunkSize, int keywordBatchSize, int readTimeoutSeconds,
                        int questionChars, int shortAnswerChars, int longAnswerChars,
                        Double estimateRatio, long estimateSamples) {}

    /** 한 표 — 채팅 답변 / 검증 / 질문 처리 / 답변 뒤 / 인덱싱 / 관리자. */
    public record Group(ThinkingSite.Group group, List<Row> rows) {
        public String id() {
            return group.id();
        }

        public String titleKey() {
            return "settings.thinking.group." + group.id();
        }
    }

    /**
     * 한 호출 지점.
     *
     * @param saved        지금 적용 중인 수준 — 오버라이드가 있으면 그것, 없으면 {@code defaultLevel}
     * @param defaultLevel 오버라이드를 지우면 돌아가는 수준 — {@code application.properties} 의 줄, 없거나 틀리면 출하값
     * @param overridden   {@code /settings} 오버라이드가 있는가
     * @param interactive  사용자가 답을 기다리는 호출인가(채팅 답변·검증·질문 처리) — "오래 기다릴 수 있음" 배지의 대상
     * @param cells        {@link ThinkingLevel#values()} 순서(끔·낮게·중간·높게)로 네 칸
     * @param modeLines    대화의 라우팅 모드에 따라 <b>다른 프로바이더</b>가 받는 경우의 추가 줄(없으면 비어 있다)
     * @param observations 받는 프로바이더에 대한 수준별 관측(네 개)
     * @param speed        그 프로바이더의 관측 생성 속도(토큰/초). 표본이 모자라면 {@code null}
     */
    public record Row(ThinkingSite site, ThinkingLevel saved, ThinkingLevel defaultLevel, boolean overridden,
                      Receiver receiver, boolean interactive, List<Cell> cells, List<ModeLine> modeLines,
                      List<ObservationLine> observations, Double speed) {

        public String id() {
            return site.id();
        }

        public String settingsKey() {
            return site.settingsKey();
        }

        public String propertyKey() {
            return site.propertyKey();
        }

        public String labelKey() {
            return "settings.thinking.site." + site.id();
        }

        public Cell cell(ThinkingLevel level) {
            return cells.get(level.ordinal());
        }

        public Cell savedCell() {
            return cell(saved);
        }
    }

    /**
     * 이 사이트를 받는 프로바이더 — 계산 시각의 라우팅이다(차단·토글 반영).
     *
     * @param name       지금 받는 프로바이더. 받을 곳이 없으면 {@code null}
     * @param nominal    서킷 브레이커의 차단이 없었다면 받았을 프로바이더 — 미리보기의 계산 기준은 <b>이쪽이 아니라 {@code name}</b>
     *                   이고, 둘이 다르면 화면이 "차단 중 → 지금은 …" 을 적는다
     * @param window     그 프로바이더의 컨텍스트 창(토큰). 0 = 모름
     * @param dialect    그 프로바이더가 쓰는 생각 제어 방식 — {@code AUTO} 가 풀린 값. 프로바이더가 없으면 {@code NONE}
     * @param rejected   서버가 거부해 보내지 않기로 기억한 필드
     * @param label      한 줄 표기 — {@code local-main · LOCAL · 우선순위 1 · 창 16,384}
     * @param note       덧붙일 말 — 차단으로 다른 프로바이더가 받을 때("… 차단 중 — 지금은 … 가 받는다"). 없으면 빈 문자열
     */
    public record Receiver(String name, String nominal, String role, int priority, int window,
                           ThinkingDialect dialect, Set<String> rejected, String label, String note) {

        public boolean none() {
            return name == null;
        }

        public boolean windowKnown() {
            return window > 0;
        }

        /** 1순위가 차단돼 다른 프로바이더가 대신 받고 있다. */
        public boolean blockedFallback() {
            return name != null && nominal != null && !nominal.equals(name);
        }
    }

    /**
     * 출력 예약이 어디에 쓰이는가 — 화면이 구분해 적는다(PLAN §6.29 ⑦-다).
     * <ul>
     *   <li>{@link #REQUEST} — 요청의 {@code max_tokens} 로 실린다(블로킹 호출).</li>
     *   <li>{@link #BUDGET_ONLY} — 예산 계산에만 쓰인다(스트리밍 답변은 {@code max_tokens} 를 싣지 않는다).</li>
     *   <li>{@link #PROVIDER_DEFAULT} — 호출부가 상한을 정하지 않아 프로바이더에 구워진 값이 예약된다(열린 항목 (d)).</li>
     * </ul>
     */
    public enum Use {
        REQUEST, BUDGET_ONLY, PROVIDER_DEFAULT;

        /** {@code settings.thinking.use.budget-only} 처럼. */
        public String messageKey() {
            return "settings.thinking.use." + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * 한 사이트의 한 수준.
     *
     * @param wire          그 프로바이더에 실제로 실리는 것 — 실제 전송({@code ThinkingControlChatModel})과 같은 함수의 결과
     * @param sentLabel     그 전송을 사람의 말로 — 켬 / 끔 / "보내지 않음 — 이 프로바이더는 생각 제어 안 함" 등. 접혔으면 그 사실도 적힌다
     * @param collapsedWith 같은 값으로 나가는 다른 수준들(접힘). 보내지 않으면 비어 있다
     * @param reservation   출력 예약의 구성(기본 + 여유). 기본이 0 이면 호출부가 상한을 싣지 않는 호출이다
     * @param reservedTokens 입력 예산에서 실제로 빼는 값 — 기본이 0 인 호출은 프로바이더 기본값
     * @param inputBudget   입력 예산. 창을 모르면 {@code null}
     * @param inputPercent  끔 대비 입력 예산 증감(%). 끔 칸이거나 알 수 없으면 {@code null}
     * @param thinkingRoom  생각에 남는 자리 = 예약 − 응답 필요분. 끔으로 나가면 {@code null}
     * @param roomUncertain 아무것도 보내지 않아 서버가 생각하는지 앱이 모른다 — 숫자에 {@code (?)} 를 붙인다
     * @param worstCaseSeconds 생각이 맴돌아 예약만큼 생성할 때 걸리는 시간. 속도 관측이 없으면 {@code null}
     * @param worstCaseText 그 시간을 사람의 말로("56초", "1분 29초") — 없으면 빈 문자열
     * @param basis         "계산 근거" 블록의 줄들(이미 푼 문장)
     * @param diff          저장된 값과의 차이 — 저장된 수준의 칸은 비어 있다
     */
    public record Cell(ThinkingLevel level, boolean saved, boolean byDefault, ThinkingWire wire, String sentLabel,
                       List<ThinkingLevel> collapsedWith, ThinkingBudget.Reservation reservation,
                       int reservedTokens, Use use, Integer inputBudget, Integer inputPercent,
                       Meaning meaning, Integer thinkingRoom, boolean roomUncertain, Long worstCaseSeconds,
                       String worstCaseText, List<Badge> badges, List<String> basis, Diff diff) {

        public boolean thinkingOn() {
            return wire.sent() == ThinkingWire.Sent.ON;
        }

        public boolean has(Badge.Kind kind) {
            return badges.stream().anyMatch(b -> b.kind() == kind);
        }

        public String levelKey() {
            return "settings.thinking.level." + level.value();
        }
    }

    /**
     * "뜻하는 것" — 그 예산이 이 호출에서 무엇인지(문서 몇 개·발췌 몇 개·조각 몇 글자…). 사이트 종류마다 다르다(PLAN §6.29 ⑦-라).
     *
     * @param metric 끔과 비교하는 값 — 클수록 좋다. {@link #known} 이 거짓이면 의미가 없다
     * @param known  비교할 수 있는 숫자가 있는가(창을 모르거나 숫자가 없는 종류면 거짓)
     * @param unit   {@link #metric} 이 세는 것의 이름 — "문서"·"발췌"·"조각 글자" 처럼 차이 줄에 쓴다
     */
    public record Meaning(Kind kind, long metric, boolean known, String text, String unit) {

        public enum Kind { DOCS, HISTORY, EXCERPTS, CANDIDATES, REWRITE, BATCH, SHORT, IMAGE }

        /** 끔보다 줄었는가 — "입력 축소" 배지의 두 번째 조건. */
        public boolean shrunkFrom(Meaning off) {
            return known && off != null && off.known && metric < off.metric;
        }
    }

    /**
     * 상태 배지 — 막지 않는다. 저장은 언제나 된다(PLAN §6.29 ⑦-마).
     *
     * @param text 배지에 적히는 짧은 문구
     * @param hint 마우스를 올리면 보이는 설명(조건·숫자) — 없으면 빈 문자열
     */
    public record Badge(Kind kind, Severity severity, String text, String hint) {

        public enum Severity {
            DANGER("bg-danger"), WARNING("bg-warning text-dark"),
            /** "오버라이드됨" 배지가 이미 {@code bg-info} 라 같은 색으로 읽히지 않게 중립색을 쓴다. */
            INFO("bg-light text-dark border");

            private final String css;

            Severity(String css) {
                this.css = css;
            }

            public String css() {
                return css;
            }
        }

        public enum Kind {
            NO_ROOM, TRUNCATION_LIKELY, TRUNCATION_POSSIBLE, HEADROOM_CLIPPED, INPUT_SHRINK, RECENT_TRUNCATION,
            SWITCH_IGNORED, TIMEOUT_EXCEEDED, LONG_WAIT, COLLAPSED, REJECTED, WINDOW_UNKNOWN, NO_CONTROL,
            NO_PROVIDER;

            /** {@code settings.thinking.badge.no-room} 처럼 — 한/영 번들이 같은 키를 갖는다(힌트는 {@code .hint}). */
            public String messageKey() {
                return "settings.thinking.badge." + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            }
        }
    }

    /** 저장된 값과 비교한 변화 — 드롭다운을 바꿨지만 아직 저장하지 않았을 때 한 줄로 보인다. 비어 있으면 "변화 없음". */
    public record Diff(List<String> parts) {

        public Diff {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        public boolean none() {
            return parts.isEmpty();
        }
    }

    /** 대화가 고른 라우팅 모드 때문에 다른 프로바이더가 받는 경우 — 저장된 수준으로만 계산한 한 칸. */
    public record ModeLine(RoutingMode mode, Receiver receiver, Cell cell) {}

    /** 수준 하나의 최근 관측(받는 프로바이더 기준). 표본이 없으면 {@code stats.any()} 가 거짓이다. */
    public record ObservationLine(ThinkingLevel level, ThinkingWire.Sent sent, ThinkingObservations.Stats stats) {}
}
