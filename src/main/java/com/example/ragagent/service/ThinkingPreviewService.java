package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.llm.IndexingOutputCap;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingBudget.Reservation;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingWire;
import com.example.ragagent.llm.TokenEstimateCalibration;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.ThinkingPreview;
import com.example.ragagent.model.ThinkingPreview.Badge;
import com.example.ragagent.model.ThinkingPreview.Basis;
import com.example.ragagent.model.ThinkingPreview.Cell;
import com.example.ragagent.model.ThinkingPreview.Diff;
import com.example.ragagent.model.ThinkingPreview.Group;
import com.example.ragagent.model.ThinkingPreview.Meaning;
import com.example.ragagent.model.ThinkingPreview.ModeLine;
import com.example.ragagent.model.ThinkingPreview.ObservationLine;
import com.example.ragagent.model.ThinkingPreview.Receiver;
import com.example.ragagent.model.ThinkingPreview.Row;
import com.example.ragagent.model.ThinkingPreview.Use;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code /settings} 의 "생각(추론) 수준 — 호출 지점별" 카드가 보여 줄 숫자를 계산한다(PLAN §6.29 ⑦, 관리자 전용).
 *
 * <p>목표는 관리자가 수준을 <b>고르기 전에</b> "이걸 고르면 이 호출에서 무엇이 얼마나 바뀌는가"를 숫자로 보는 것이다 — 출력 예약,
 * 입력 예산, 그 예산으로 들어가는 문서·발췌 수, 생각에 남는 자리, 최악의 소요 시간. 사이트마다 <b>네 수준 전부</b>를 계산해
 * 둔다(드롭다운은 미리 그려진 칸을 보이기만 한다).
 *
 * <p><b>미리보기 = 런타임.</b> 이 클래스는 숫자를 직접 만들지 않는다. 런타임 호출부가 쓰는 함수를 <b>그대로</b> 부른다:
 * 예약은 {@link ThinkingBudget#reservation(ThinkingSite, ThinkingLevel, String, int)}(기본 예약은 호출부가 노출한
 * {@code baseReservation}/{@code answerReservation}/{@code evalReservation}), 입력 예산은 {@link PromptBudget}, 문서 수는
 * {@link PromptBudget#fitByPrefix}, 발췌 수는 {@code AnswerService.excerptFits}, 이력은 {@link HistoryPolicy#budgetChars},
 * 조각 크기는 {@code sectionChars}/{@code blockChars}. 그래서 미리보기가 틀렸다면 실제 요청도 같은 식으로 틀린다 — 둘이 갈라지는
 * 일은 없다. {@code ThinkingPreviewRuntimeParityTest} 가 호출부가 실제로 만든 옵션의 {@code maxTokens} 와 비교한다.
 *
 * <p><b>관측하고 보여 줄 뿐, 아무것도 바꾸지 않는다.</b> 생각 여유를 관측으로 자동 보정하지 않는다({@link ThinkingObservations}).
 * 배지는 막지 않는다 — 저장은 언제나 된다.
 *
 * <p><b>문구는 요청 로케일</b>({@link LocaleContextHolder})의 번들로 계산 시점에 푼다. 번들 키가 빠지면 화면이 아니라 여기서 예외가
 * 난다. 반대로 <b>프롬프트 크기는 늘 한국어 번들</b>로 잰다 — 앱의 기본 로케일이고 {@code AgentState} 의 기본값이다.
 */
@Service
public class ThinkingPreviewService {

    // ── 가정 — 화면 기준 줄에 그대로 적는다(PLAN §6.29 ⑦-바) ───────────────────────────────────────────
    /** 질문의 길이. */
    public static final int QUESTION_CHARS = 200;
    /** 검증이 보는 답변의 길이 — 두 가지를 같이 보인다. */
    public static final int SHORT_ANSWER_CHARS = 3_000;
    public static final int LONG_ANSWER_CHARS = 5_000;

    /** 입력 예산이 끔 대비 이만큼(%) 이상 줄면 "입력 축소" 배지. */
    static final int INPUT_SHRINK_PERCENT = 15;
    /** 대화형 호출의 최악 소요가 이만큼(초) 이상이면 "오래 기다릴 수 있음". */
    static final int LONG_WAIT_SECONDS = 60;
    /** 관측 분위수로 잘림을 판정하려면 생각을 관측한 표본이 이만큼은 있어야 한다. */
    static final int MIN_JUDGE_SAMPLES = 3;
    /** 키워드+맥락 한 청크가 내는 응답(키워드 3~7개 + 1~2문장)의 전형 토큰 수 — 6단계에서 관측으로 조정한다. */
    static final int KEYWORD_OUTPUT_TOKENS_PER_CHUNK = 120;
    /** 대화 제목 호출의 인라인 지시(번들에 없다)의 토큰 추정. */
    static final int TITLE_INSTRUCTION_TOKENS = 60;
    /** 프롬프트 크기를 재는 번들의 로케일 — 앱의 기본 로케일이다(화면 문구의 로케일과 별개). */
    private static final Locale PROMPT_LOCALE = Locale.KOREAN;

    /** 사이트의 모양 — 예약·"뜻하는 것"의 계산식이 갈린다. exhaustive switch 라 사이트를 더하면 여기서 컴파일 오류가 난다. */
    private enum Shape { RAG_ANSWER, DIRECT_ANSWER, EVAL, CANDIDATES, REWRITE_MD, REWRITE_TXT, BATCH, SHORT, IMAGE }

    private static Shape shapeOf(ThinkingSite site) {
        return switch (site) {
            case ANSWER_RAG_S, ANSWER_RAG_N, ANSWER_RAG_C -> Shape.RAG_ANSWER;
            case ANSWER_DIRECT_S, ANSWER_DIRECT_N, ANSWER_META -> Shape.DIRECT_ANSWER;
            case EVAL, EVAL_CREATIVE -> Shape.EVAL;
            case RERANK -> Shape.CANDIDATES;
            case MD_CORRECT -> Shape.REWRITE_MD;
            case TXT_TO_MD -> Shape.REWRITE_TXT;
            case KEYWORD_CONTEXT -> Shape.BATCH;
            case IMAGE_DESCRIBE, IMAGE_TYPE, MD_CORRECT_VISION -> Shape.IMAGE;
            case CLASSIFY, CONDENSE, QUERY_EXPANSION, POST_ANSWER, TITLE, SUMMARY, CURATED_SUGGEST -> Shape.SHORT;
        };
    }

    private final AppProperties props;
    private final LlmRouter router;
    private final ThinkingBudget budget;
    private final ProviderThinkingDialects dialects;
    private final ProviderContextWindows windows;
    private final ThinkingObservations observations;
    private final TokenEstimateCalibration calibration;
    private final SettingsService settings;
    private final MessageSource messages;

    public ThinkingPreviewService(AppProperties props, LlmRouter router, ThinkingBudget budget,
                                  ProviderThinkingDialects dialects, ProviderContextWindows windows,
                                  ThinkingObservations observations, TokenEstimateCalibration calibration,
                                  SettingsService settings, MessageSource messages) {
        this.props = props;
        this.router = router;
        this.budget = budget;
        this.dialects = dialects;
        this.windows = windows;
        this.observations = observations;
        this.calibration = calibration;
        this.settings = settings;
        this.messages = messages;
    }

    // ── 진입점 ───────────────────────────────────────────────────────────────────────────────────

    /** 카드 전체 — 페이지를 열 때와 [다시 계산] 이 부른다. */
    public ThinkingPreview preview() {
        Ctx ctx = new Ctx();
        Map<ThinkingSite.Group, List<Row>> byGroup = new EnumMap<>(ThinkingSite.Group.class);
        for (ThinkingSite.Group g : ThinkingSite.Group.values()) byGroup.put(g, new ArrayList<>());
        for (ThinkingSite site : ThinkingSite.values()) byGroup.get(site.group()).add(row(ctx, site));
        List<Group> groups = new ArrayList<>();
        byGroup.forEach((g, rows) -> {
            if (!rows.isEmpty()) groups.add(new Group(g, List.copyOf(rows)));
        });
        return new ThinkingPreview(Instant.now(), ctx.basis(), List.copyOf(groups));
    }

    /** 한 사이트만 — [저장]·[기본값] 뒤에 그 행만 새로 그린다. */
    public Row row(ThinkingSite site) {
        return row(new Ctx(), site);
    }

    /** 계산 기준 줄만 — 한 행을 새로 그릴 때는 필요 없다. */
    public Basis basis() {
        return new Ctx().basis();
    }

    // ── 한 사이트 ────────────────────────────────────────────────────────────────────────────────

    private Row row(Ctx c, ThinkingSite site) {
        ThinkingLevel saved = props.llmSafe().thinkingLevel(site);
        ThinkingLevel dflt = defaultLevelOf(site);
        boolean overridden = settings.isOverridden(site.settingsKey());
        RoutingMode mode = site.routingMode(c.defaultMode);
        Receiver receiver = receiverFor(c, site, mode);

        List<Cell> cells = cellsFor(c, site, receiver, saved, dflt);

        List<ModeLine> modeLines = new ArrayList<>();
        if (site.followsConversationRouting()) {
            for (RoutingMode other : new RoutingMode[]{RoutingMode.COST_FIRST, RoutingMode.QUALITY_FIRST, RoutingMode.LOCAL_ONLY}) {
                if (other == mode) continue;
                Receiver r = receiverFor(c, site, other);
                if (r.none() || r.name().equals(receiver.name())) continue;   // 같은 프로바이더면 줄을 만들지 않는다
                if (modeLines.stream().anyMatch(l -> r.name().equals(l.receiver().name()))) continue;
                List<Cell> alt = cellsFor(c, site, r, saved, dflt);
                modeLines.add(new ModeLine(other, r, alt.get(saved.ordinal())));
            }
        }

        List<ObservationLine> obs = new ArrayList<>();
        for (Cell cell : cells) {
            obs.add(new ObservationLine(cell.level(), cell.wire().sent(), c.stats(site, receiver, cell.level())));
        }
        Double speed = c.speed(receiver.name());
        return new Row(site, saved, dflt, overridden, receiver, interactive(site), List.copyOf(cells),
                List.copyOf(modeLines), List.copyOf(obs), speed);
    }

    /** 오버라이드를 지우면 돌아가는 수준 — 파일의 줄, 없거나 틀리면 출하값({@code thinkingLevel} 이 읽는 순서의 아래 두 단). */
    private ThinkingLevel defaultLevelOf(ThinkingSite site) {
        Map<String, String> file = props.llmSafe().thinking();
        return ThinkingLevel.parse(file == null ? null : file.get(site.id())).orElse(site.shippedDefault());
    }

    private static boolean interactive(ThinkingSite site) {
        ThinkingSite.Group g = site.group();
        return g == ThinkingSite.Group.ANSWER || g == ThinkingSite.Group.VERIFY || g == ThinkingSite.Group.QUERY;
    }

    /** 네 수준을 한꺼번에 — 끔 칸과 저장된 칸에 기대는 값(입력 증감 %, 입력 축소 배지, 차이 줄)이 있어 두 단계로 만든다. */
    private List<Cell> cellsFor(Ctx c, ThinkingSite site, Receiver receiver, ThinkingLevel saved, ThinkingLevel dflt) {
        ThinkingLevel[] levels = ThinkingLevel.values();
        Draft[] drafts = new Draft[levels.length];
        for (ThinkingLevel level : levels) drafts[level.ordinal()] = draft(c, site, level, receiver);

        // 같은 값으로 나가는 다른 수준 — 접힘. 보내지 않는 프로바이더에서는 "같다"가 의미 없다.
        for (Draft d : drafts) {
            if (d.wire.sent() == ThinkingWire.Sent.NOTHING) continue;
            for (Draft other : drafts) {
                if (other != d && sameWire(d.wire, other.wire)) d.collapsedWith.add(other.level);
            }
        }

        Draft off = drafts[ThinkingLevel.OFF.ordinal()];
        Draft savedDraft = drafts[saved.ordinal()];
        List<Cell> cells = new ArrayList<>(levels.length);
        for (Draft d : drafts) {
            Integer percent = d.inputBudget == null || off.inputBudget == null || d.level == ThinkingLevel.OFF
                    || off.inputBudget <= 0 ? null
                    : (int) Math.round((d.inputBudget - off.inputBudget) * 100.0 / off.inputBudget);
            List<Badge> badges = badgesFor(c, site, receiver, d, off, percent);
            Diff diff = d.level == saved ? new Diff(List.of()) : diffOf(d, savedDraft);
            cells.add(new Cell(d.level, d.level == saved, d.level == dflt, d.wire, d.sentLabel,
                    List.copyOf(d.collapsedWith), d.reservation, d.reservedTokens, d.use, d.inputBudget, percent,
                    d.meaning, d.room, d.roomUncertain, d.worstSeconds,
                    d.worstSeconds == null ? "" : duration(d.worstSeconds), List.copyOf(badges), List.copyOf(d.basis), diff));
        }
        return cells;
    }

    private static boolean sameWire(ThinkingWire a, ThinkingWire b) {
        return a.sent() == b.sent() && a.extraBody().equals(b.extraBody())
                && java.util.Objects.equals(a.reasoningEffort(), b.reasoningEffort());
    }

    /** 수준 하나의 계산 중간 결과 — 상대적인 값(끔·저장된 칸 대비)을 빼고 전부 들어 있다. */
    private static final class Draft {
        final ThinkingLevel level;
        ThinkingWire wire = ThinkingWire.NOTHING;
        String sentLabel = "";
        final List<ThinkingLevel> collapsedWith = new ArrayList<>();
        Reservation reservation = new Reservation(0, 0, 0, 0);
        int reservedTokens;
        Use use = Use.REQUEST;
        Integer inputBudget;
        Meaning meaning;
        Integer room;
        boolean roomUncertain;
        Long worstSeconds;
        long expectedOutput;
        int rewriteChars;
        final List<String> basis = new ArrayList<>();

        Draft(ThinkingLevel level) {
            this.level = level;
        }
    }

    private Draft draft(Ctx c, ThinkingSite site, ThinkingLevel level, Receiver receiver) {
        Draft d = new Draft(level);
        String provider = receiver.name();
        Shape shape = shapeOf(site);
        int window = receiver.window();

        d.wire = dialects.wireFor(provider, level);

        // ── 출력 예약 — 기본 예약은 호출부가 노출한 함수, 여유는 ThinkingBudget (런타임과 같은 식) ──
        String baseKey;
        Object[] baseArgs;
        switch (shape) {
            case RAG_ANSWER, DIRECT_ANSWER -> {
                ResponseMode mode = answerModeOf(site);
                d.reservation = AnswerService.answerReservation(budget, site, level, provider, mode, true, c.maxTokens);
                d.use = Use.BUDGET_ONLY;
                baseKey = "settings.thinking.basis.base.answer";
                int blocking = AnswerService.answerReservation(budget, site, level, provider, mode, false, c.maxTokens).tokens();
                // 블로킹 응답 예산(생각 여유 전)과 그 값을 정한 항 — "LLM 튜닝" 그룹에서 빠진 "응답 예산" 행이 말하던 것이다
                baseArgs = new Object[]{d.reservation.base(), mode.minChars(), blocking,
                        mode.maxTokens(c.maxTokens), blockingTerm(mode, c.maxTokens)};
                d.expectedOutput = mode.minChars();
            }
            case EVAL -> {
                d.reservation = AnswerService.evalReservation(budget, site, level, provider, c.maxTokens);
                d.use = c.maxTokens > 0 ? Use.REQUEST : Use.BUDGET_ONLY;
                baseKey = "settings.thinking.basis.base.eval";
                baseArgs = new Object[]{d.reservation.base(), c.maxTokens};
                d.expectedOutput = site.expectedOutputTokens();
            }
            case BATCH -> {
                int base = KeywordExtractor.enrichmentReservation(c.batch, c.maxTokens);
                d.reservation = budget.reservation(site, level, provider, base);
                baseKey = "settings.thinking.basis.base.keyword";
                baseArgs = new Object[]{base, c.batch};
                d.expectedOutput = (long) KEYWORD_OUTPUT_TOKENS_PER_CHUNK * c.batch;
            }
            case REWRITE_MD, REWRITE_TXT -> {
                int headroom = budget.rewriteHeadroom(site, level, provider);
                d.rewriteChars = shape == Shape.REWRITE_MD
                        ? MarkdownCorrectionService.sectionChars(c.maxTokens, window, headroom)
                        : TextToMarkdownService.blockChars(window, headroom);
                int base = IndexingOutputCap.forRewriteTokens(koreanTokens(d.rewriteChars), c.maxTokens);
                d.reservation = budget.reservation(site, level, provider, base);
                baseKey = "settings.thinking.basis.base.rewrite";
                baseArgs = new Object[]{base, d.rewriteChars};
                d.expectedOutput = d.rewriteChars;
            }
            case IMAGE, SHORT, CANDIDATES -> {
                int base = switch (site) {
                    case CONDENSE -> QuestionCondenser.baseReservation(c.maxTokens);
                    case POST_ANSWER -> PostAnswerService.baseReservation(c.maxTokens);
                    case CURATED_SUGGEST -> CuratedQuestionSuggester.baseReservation(c.maxTokens);
                    case MD_CORRECT_VISION -> MarkdownCorrectionService.visionReservation(c.maxTokens);
                    default -> 0;   // 출력 상한을 싣지 않는 호출 — 프로바이더 기본값이 예약된다(열린 항목 (d))
                };
                d.reservation = budget.reservation(site, level, provider, base);
                d.expectedOutput = site.expectedOutputTokens();
                if (base > 0) {
                    baseKey = "settings.thinking.basis.base.short";
                    baseArgs = new Object[]{base, c.maxTokens};
                } else {
                    baseKey = "settings.thinking.basis.base.provider-default";
                    baseArgs = new Object[]{budget.providerMaxTokens(provider)};
                    d.use = Use.PROVIDER_DEFAULT;
                }
            }
            default -> throw new IllegalStateException(shape.name());
        }
        // 호출부가 상한을 싣지 않으면 프로바이더에 구워진 값이 실제로 예약된다 — 그 값으로 입력 예산을 잰다.
        Reservation effective = d.reservation.base() > 0 ? d.reservation
                : new Reservation(budget.providerMaxTokens(provider), d.reservation.requested(), 0, 0);
        d.reservedTokens = effective.tokens();

        // ── 입력 예산 ──
        if (window > 0) d.inputBudget = new PromptBudget(window, effective.tokens()).inputBudget();

        // ── 생각 자리·최악 소요 ──
        if (d.wire.sent() != ThinkingWire.Sent.OFF) {
            d.room = (int) (d.reservedTokens - d.expectedOutput);
            d.roomUncertain = d.wire.sent() == ThinkingWire.Sent.NOTHING;
        }
        Double speed = c.speed(provider);
        if (speed != null && speed > 0 && d.reservedTokens > 0) d.worstSeconds = (long) Math.ceil(d.reservedTokens / speed);

        d.sentLabel = sentLabel(receiver, d.wire);
        d.meaning = meaningOf(c, site, shape, d, effective, receiver);

        // ── 계산 근거 ──
        d.basis.add(receiver.none()
                ? msg("settings.thinking.basis.provider.none")
                : msg("settings.thinking.basis.provider", receiver.label(), receiver.dialect().value()));
        d.basis.add(msg(baseKey, baseArgs));
        d.basis.add(headroomLine(c, site, level, d, receiver));
        d.basis.add(window > 0
                ? msg("settings.thinking.basis.input", window, effective.tokens(), PromptBudget.marginFor(window), d.inputBudget)
                : msg("settings.thinking.basis.input.unknown"));
        d.basis.addAll(meaningBasis(c, site, shape, d, effective, receiver));
        if (d.room != null) {
            d.basis.add(msg(d.roomUncertain ? "settings.thinking.basis.room.uncertain" : "settings.thinking.basis.room",
                    d.reservedTokens, d.expectedOutput, d.room));
        }
        return d;
    }

    private String sentLabel(Receiver receiver, ThinkingWire wire) {
        return switch (wire.sent()) {
            case ON -> msg("settings.thinking.sent.on");
            case OFF -> msg("settings.thinking.sent.off");
            case NOTHING -> receiver.rejected().isEmpty()
                    ? msg("settings.thinking.sent.nothing.none")
                    : msg("settings.thinking.sent.nothing.rejected", String.join(", ", receiver.rejected()));
        };
    }

    /** "생각 여유" 한 줄 — 얼마를 요구했고, 상한이 얼마이며, 실제로 얼마를 더했는가. */
    private String headroomLine(Ctx c, ThinkingSite site, ThinkingLevel level, Draft d, Receiver receiver) {
        Reservation r = d.reservation;
        if (r.requested() > 0) {
            String source = r.ceiling() > 0 && receiver.window() > 0
                    && r.ceiling() == receiver.window() * ThinkingBudget.CEILING_PERCENT / 100
                    ? msg("settings.thinking.ceiling.window", ThinkingBudget.CEILING_PERCENT)
                    : msg("settings.thinking.ceiling.provider");
            if (r.ceiling() <= 0) {
                return msg("settings.thinking.basis.headroom.noceiling", levelName(level), r.requested());
            }
            return msg(r.clipped() ? "settings.thinking.basis.headroom.clipped" : "settings.thinking.basis.headroom",
                    levelName(level), r.requested(), r.ceiling(), source, Math.max(0, r.ceiling() - r.base()), r.granted(),
                    d.reservedTokens);
        }
        String reason;
        if (r.base() <= 0) reason = msg("settings.thinking.reason.no-cap");
        else if (d.wire.sent() == ThinkingWire.Sent.OFF) reason = msg("settings.thinking.reason.off");
        else if (d.wire.sent() == ThinkingWire.Sent.NOTHING) reason = msg("settings.thinking.reason.nothing");
        else reason = msg("settings.thinking.reason.level-off");
        return msg("settings.thinking.basis.headroom.none", reason);
    }

    // ── "뜻하는 것"(PLAN §6.29 ⑦-라) ─────────────────────────────────────────────────────────────

    private Meaning meaningOf(Ctx c, ThinkingSite site, Shape shape, Draft d, Reservation effective, Receiver receiver) {
        int window = receiver.window();
        Integer input = d.inputBudget;
        if (shape == Shape.IMAGE) {
            return new Meaning(Meaning.Kind.IMAGE, 0, false, msg("settings.thinking.meaning.image"), "");
        }
        if (shape == Shape.REWRITE_MD || shape == Shape.REWRITE_TXT) {
            // 조각 크기는 창을 몰라도 나온다(max-tokens 파생값 그대로) — 다만 끔 대비 호출 수 증가는 창이 있어야 의미가 있다.
            return new Meaning(Meaning.Kind.REWRITE, d.rewriteChars, window > 0,
                    msg("settings.thinking.meaning.rewrite", d.rewriteChars),
                    msg("settings.thinking.unit.rewrite"));
        }
        if (window <= 0 || input == null) {
            return new Meaning(kindOf(shape), 0, false, msg("settings.thinking.meaning.unknown"), unitOf(shape));
        }
        return switch (shape) {
            case RAG_ANSWER -> {
                ResponseMode mode = answerModeOf(site);
                long fixed = AnswerService.answerFixedCost(c.promptTokens(mode.answerSystemPromptKey()),
                        c.questionTokens, 0);
                long chunk = koreanTokens(c.chunkSize);
                List<Long> docs = Collections.nCopies(c.topK, chunk);
                int empty = PromptBudget.fitByPrefix(docs, Long::longValue, fixed, input).size();
                int withHistory = PromptBudget.fitByPrefix(docs, Long::longValue,
                        fixed + koreanTokens(c.historyChars), input).size();
                yield new Meaning(Meaning.Kind.DOCS, empty, true,
                        msg("settings.thinking.meaning.docs", empty, c.topK, withHistory),
                        msg("settings.thinking.unit.docs"));
            }
            case DIRECT_ANSWER -> {
                int chars = HistoryPolicy.budgetChars(window, effective.tokens(), 0, c.questionTokens, c.historyChars);
                yield new Meaning(Meaning.Kind.HISTORY, chars, true,
                        msg("settings.thinking.meaning.history", chars),
                        msg("settings.thinking.unit.history"));
            }
            case EVAL -> {
                ResponseMode mode = evalModeOf(site);
                boolean creative = mode.usesCreativeEval();
                long fixedBase = c.promptTokens(mode.evalPromptKey()) + c.questionTokens
                        + c.schemaTokens(creative);
                int shortK = excerptCount(c, window, effective, fixedBase + koreanTokens(SHORT_ANSWER_CHARS));
                int longK = excerptCount(c, window, effective, fixedBase + koreanTokens(LONG_ANSWER_CHARS));
                yield new Meaning(Meaning.Kind.EXCERPTS, shortK, true,
                        msg("settings.thinking.meaning.excerpts", shortK, c.topK, longK),
                        msg("settings.thinking.unit.excerpts"));
            }
            case CANDIDATES -> {
                int pool = c.topK * Math.max(1, c.candidateMultiplier);
                long instruction = c.promptTokens("prompt.rerank") + c.questionTokens;
                long perCandidate = Math.max(1, TokenEstimator.estimate(
                        RerankerService.formatDocList(List.of(new Document("가".repeat(c.chunkSize))))));
                long room = Math.max(0, input - instruction);
                int fit = (int) Math.min(pool, room / perCandidate);
                yield new Meaning(Meaning.Kind.CANDIDATES, fit, true,
                        msg("settings.thinking.meaning.candidates", fit, pool),
                        msg("settings.thinking.unit.candidates"));
            }
            case BATCH -> {
                long chunk = koreanTokens(c.chunkSize);
                int max = 0;
                for (int n = 1; n <= 64; n++) {
                    if (KeywordExtractor.batchPromptOverheadTokens(n) + n * chunk <= input) max = n;
                    else break;
                }
                boolean fits = KeywordExtractor.batchPromptOverheadTokens(c.batch) + c.batch * chunk <= input;
                yield new Meaning(Meaning.Kind.BATCH, max, true,
                        msg(fits ? "settings.thinking.meaning.batch" : "settings.thinking.meaning.batch.over", c.batch, max),
                        msg("settings.thinking.unit.batch"));
            }
            case SHORT -> {
                long needed = shortNeed(c, site);
                boolean ok = needed <= input;
                yield new Meaning(Meaning.Kind.SHORT, ok ? Math.max(0, input - needed) : -1, false,
                        msg(ok ? "settings.thinking.meaning.short" : "settings.thinking.meaning.short.risk", needed, input),
                        "");
            }
            default -> throw new IllegalStateException(shape.name());
        };
    }

    private static Meaning.Kind kindOf(Shape shape) {
        return switch (shape) {
            case RAG_ANSWER -> Meaning.Kind.DOCS;
            case DIRECT_ANSWER -> Meaning.Kind.HISTORY;
            case EVAL -> Meaning.Kind.EXCERPTS;
            case CANDIDATES -> Meaning.Kind.CANDIDATES;
            case REWRITE_MD, REWRITE_TXT -> Meaning.Kind.REWRITE;
            case BATCH -> Meaning.Kind.BATCH;
            case SHORT -> Meaning.Kind.SHORT;
            case IMAGE -> Meaning.Kind.IMAGE;
        };
    }

    private String unitOf(Shape shape) {
        return switch (shape) {
            case RAG_ANSWER -> msg("settings.thinking.unit.docs");
            case DIRECT_ANSWER -> msg("settings.thinking.unit.history");
            case EVAL -> msg("settings.thinking.unit.excerpts");
            case CANDIDATES -> msg("settings.thinking.unit.candidates");
            case REWRITE_MD, REWRITE_TXT -> msg("settings.thinking.unit.rewrite");
            case BATCH -> msg("settings.thinking.unit.batch");
            case SHORT, IMAGE -> "";
        };
    }

    /**
     * 검증 발췌가 몇 개 들어가는가 — {@code buildEvalExcerpts} 가 문서마다 묻는 판정({@code excerptFits})을 같은 순서로 물어서
     * 센다. 발췌 예산은 {@code evalExcerptBudget} 이다(입력 예산 − 고정 몫).
     */
    private int excerptCount(Ctx c, int window, Reservation effective, long fixed) {
        long tokenBudget = AnswerService.evalExcerptBudget(window, effective, fixed);
        long per = koreanTokens(c.chunkSize);
        int included = 0;
        int usedChars = 0;
        long usedTokens = 0;
        for (int i = 0; i < c.topK; i++) {
            if (!AnswerService.excerptFits(included, usedChars, c.chunkSize, usedTokens, per, tokenBudget)) break;
            included++;
            usedChars += c.chunkSize;
            usedTokens += per;
        }
        return included;
    }

    /** 짧은 응답 사이트가 입력으로 필요한 토큰 — 지시 프롬프트 + 전형 입력. */
    private long shortNeed(Ctx c, ThinkingSite site) {
        return switch (site) {
            case CLASSIFY -> c.promptTokens("prompt.classifier.system") + c.questionTokens;
            case CONDENSE -> c.promptTokens("prompt.retrieval.condense") + koreanTokens(4 * QUESTION_CHARS);
            case QUERY_EXPANSION -> c.promptTokens("prompt.retrieval.expansion") + c.questionTokens;
            case POST_ANSWER -> c.promptTokens("prompt.postanswer.extras") + c.questionTokens
                    + koreanTokens(SHORT_ANSWER_CHARS);
            case TITLE -> TITLE_INSTRUCTION_TOKENS + c.questionTokens;
            case SUMMARY -> c.promptTokens("prompt.summary.system") + koreanTokens(c.historyChars);
            case CURATED_SUGGEST -> c.promptTokens("prompt.curated.question") + koreanTokens(c.chunkSize);
            default -> throw new IllegalArgumentException(site.name());
        };
    }

    /** 사이트 종류별 계산 근거 줄 — 입력 예산에서 "뜻하는 것"까지 어떻게 왔는가. */
    private List<String> meaningBasis(Ctx c, ThinkingSite site, Shape shape, Draft d, Reservation effective,
                                      Receiver receiver) {
        if (receiver.window() <= 0 || d.inputBudget == null) return List.of();
        int input = d.inputBudget;
        switch (shape) {
            case RAG_ANSWER -> {
                ResponseMode mode = answerModeOf(site);
                long system = c.promptTokens(mode.answerSystemPromptKey());
                long fixed = AnswerService.answerFixedCost(system, c.questionTokens, 0);
                return List.of(msg("settings.thinking.basis.docs", input, fixed, system, c.questionTokens,
                        input - fixed, koreanTokens(c.chunkSize), c.topK, koreanTokens(c.historyChars)));
            }
            case DIRECT_ANSWER -> {
                return List.of(msg("settings.thinking.basis.history", input, c.questionTokens,
                        HistoryPolicy.DIRECT_PROMPT_OVERHEAD_TOKENS));
            }
            case EVAL -> {
                ResponseMode mode = evalModeOf(site);
                long system = c.promptTokens(mode.evalPromptKey());
                long schema = c.schemaTokens(mode.usesCreativeEval());
                long fixed = AnswerService.evalFixedCost(system, koreanTokens(SHORT_ANSWER_CHARS), c.questionTokens, schema);
                return List.of(msg("settings.thinking.basis.excerpts", input, fixed, system, schema, c.questionTokens,
                        SHORT_ANSWER_CHARS, AnswerService.evalExcerptBudget(receiver.window(), effective, fixed),
                        koreanTokens(c.chunkSize), c.topK));
            }
            case REWRITE_MD, REWRITE_TXT -> {
                int overhead = shape == Shape.REWRITE_MD
                        ? MarkdownCorrectionService.promptOverheadTokens() : TextToMarkdownService.promptOverheadTokens();
                return List.of(msg("settings.thinking.basis.rewrite", overhead, d.reservation.requested(), d.rewriteChars,
                        d.reservation.base()));
            }
            case BATCH -> {
                return List.of(msg("settings.thinking.basis.batch", input, KeywordExtractor.batchPromptOverheadTokens(c.batch),
                        koreanTokens(c.chunkSize), c.batch));
            }
            case CANDIDATES -> {
                return List.of(msg("settings.thinking.basis.candidates", input, c.promptTokens("prompt.rerank"),
                        c.topK * Math.max(1, c.candidateMultiplier)));
            }
            case SHORT -> {
                return List.of(msg("settings.thinking.basis.short", input, shortNeed(c, site)));
            }
            default -> {
                return List.of();
            }
        }
    }

    // ── 배지(PLAN §6.29 ⑦-마) — 막지 않는다 ───────────────────────────────────────────────────────

    private List<Badge> badgesFor(Ctx c, ThinkingSite site, Receiver receiver, Draft d, Draft off, Integer percent) {
        List<Badge> out = new ArrayList<>();
        Badge.Severity danger = Badge.Severity.DANGER;
        Badge.Severity warn = Badge.Severity.WARNING;
        Badge.Severity info = Badge.Severity.INFO;
        boolean streamsAnswer = site.group() == ThinkingSite.Group.ANSWER;
        ThinkingObservations.Stats stats = c.stats(site, receiver, d.level);

        if (receiver.none()) {
            out.add(badge(Badge.Kind.NO_PROVIDER, info));
            return out;
        }
        boolean on = d.wire.sent() == ThinkingWire.Sent.ON;
        if (on && d.room != null && d.room <= 0) {
            // 스트리밍 답변은 max_tokens 를 싣지 않아 오차 여유만큼 더 쓸 수 있다 — 그 너머에서는 서버가 창 끝에서 자른다.
            out.add(badge(Badge.Kind.NO_ROOM, streamsAnswer ? warn : danger, d.reservedTokens, d.expectedOutput));
        }
        if (on && d.room != null && d.room > 0 && stats.thinkingObserved() >= MIN_JUDGE_SAMPLES) {
            if (stats.thinkingP50() != null && d.room < stats.thinkingP50()) {
                out.add(badge(Badge.Kind.TRUNCATION_LIKELY, danger, d.room, stats.thinkingP50()));
            } else if (stats.thinkingP95() != null && d.room < stats.thinkingP95()) {
                out.add(badge(Badge.Kind.TRUNCATION_POSSIBLE, warn, d.room, stats.thinkingP95()));
            }
        } else if (on && d.room != null && d.room > 0 && d.room < ThinkingBudget.headroom(d.level)) {
            // 관측이 없으면 요구한 여유보다 자리가 작은가로 가늠한다.
            out.add(badge(Badge.Kind.TRUNCATION_POSSIBLE, warn, d.room, ThinkingBudget.headroom(d.level)));
        }
        if (on && d.reservation.clipped()) {
            out.add(badge(Badge.Kind.HEADROOM_CLIPPED, warn, d.reservation.requested(), d.reservation.granted(),
                    d.reservation.ceiling()));
        }
        if (d.level != ThinkingLevel.OFF && on && ((percent != null && percent <= -INPUT_SHRINK_PERCENT)
                || d.meaning.shrunkFrom(off.meaning))) {
            out.add(badge(Badge.Kind.INPUT_SHRINK, warn, percent == null ? 0 : percent, d.meaning.text()));
        }
        if (stats.truncated() > 0) {
            out.add(badge(Badge.Kind.RECENT_TRUNCATION, warn, stats.truncated(), stats.count()));
        }
        if (d.wire.sent() == ThinkingWire.Sent.OFF && d.level == ThinkingLevel.OFF && stats.thinkingObserved() > 0) {
            out.add(badge(Badge.Kind.SWITCH_IGNORED, warn, stats.thinkingObserved(), stats.count()));
        }
        Long worst = d.worstSeconds;
        if (worst != null && !streamsAnswer && c.readTimeout > 0 && worst > c.readTimeout) {
            out.add(badge(Badge.Kind.TIMEOUT_EXCEEDED, warn, duration(worst), c.readTimeout));
        }
        if (worst != null && interactive(site) && worst >= LONG_WAIT_SECONDS) {
            out.add(badge(Badge.Kind.LONG_WAIT, info, duration(worst)));
        }
        if (!d.collapsedWith.isEmpty()) {
            out.add(badge(Badge.Kind.COLLAPSED, info,
                    d.collapsedWith.stream().map(this::levelName).reduce((a, b) -> a + "·" + b).orElse("")));
        }
        if (d.wire.sent() == ThinkingWire.Sent.NOTHING) {
            if (receiver.rejected().isEmpty()) {
                out.add(badge(Badge.Kind.NO_CONTROL, info, stats.thinkingObserved(), stats.count()));
            } else {
                out.add(badge(Badge.Kind.REJECTED, info, String.join(", ", receiver.rejected())));
            }
        }
        if (!receiver.windowKnown()) out.add(badge(Badge.Kind.WINDOW_UNKNOWN, info));
        return out;
    }

    private Badge badge(Badge.Kind kind, Badge.Severity severity, Object... args) {
        return new Badge(kind, severity, msg(kind.messageKey(), args), msg(kind.messageKey() + ".hint", args));
    }

    // ── 저장된 값과의 차이 ────────────────────────────────────────────────────────────────────────

    private Diff diffOf(Draft d, Draft saved) {
        List<String> parts = new ArrayList<>();
        long reservation = (long) d.reservedTokens - saved.reservedTokens;
        parts.add(msg("settings.thinking.diff.reservation", signed(reservation)));
        if (d.inputBudget != null && saved.inputBudget != null) {
            long delta = (long) d.inputBudget - saved.inputBudget;
            String percent = saved.inputBudget > 0
                    ? signed(Math.round(delta * 100.0 / saved.inputBudget)) + "%" : "-";
            parts.add(msg("settings.thinking.diff.input", signed(delta), percent));
        }
        if (d.meaning.known() && saved.meaning.known()) {
            long delta = d.meaning.metric() - saved.meaning.metric();
            parts.add(delta == 0
                    ? msg("settings.thinking.diff.meaning.same", d.meaning.unit())
                    : msg("settings.thinking.diff.meaning", d.meaning.unit(), signed(delta)));
        }
        if (d.worstSeconds != null && saved.worstSeconds != null) {
            long delta = d.worstSeconds - saved.worstSeconds;
            parts.add(msg("settings.thinking.diff.worst", (delta >= 0 ? "+" : "-") + duration(Math.abs(delta))));
        }
        return new Diff(parts);
    }

    // ── 받는 프로바이더 ───────────────────────────────────────────────────────────────────────────

    private Receiver receiverFor(Ctx c, ThinkingSite site, RoutingMode mode) {
        String current = null;
        String nominal = null;
        for (TaskType type : site.taskTypes()) {
            if (current == null) {
                String name = router.findProviderName(type, mode);
                if (name != null && !"unknown".equals(name)) current = name;
            }
            if (nominal == null) nominal = router.findNominalProviderName(type, mode).orElse(null);
        }
        String named = current != null ? current : nominal;
        AppProperties.ProviderConfig cfg = named == null ? null : props.llmSafe().providers() == null ? null
                : props.llmSafe().providers().stream().filter(p -> named.equals(p.name())).findFirst().orElse(null);
        String role = cfg == null || cfg.role() == null ? "-" : cfg.role().toUpperCase(Locale.ROOT);
        int priority = cfg == null ? 0 : cfg.priority();
        int window = windows.tokensOrZero(current);
        ThinkingDialect dialect = dialects.dialectOf(current);
        Set<String> rejected = dialects.rejectedFields(current);
        String label = current == null ? "" : window > 0
                ? msg("settings.thinking.receiver.label", current, role, priority, window)
                : msg("settings.thinking.receiver.label.nowindow", current, role, priority);
        String note = current != null && nominal != null && !nominal.equals(current)
                ? msg("settings.thinking.receiver.blocked", nominal, current) : "";
        return new Receiver(current, nominal, role, priority, window, dialect, rejected, label, note);
    }

    private static ResponseMode answerModeOf(ThinkingSite site) {
        for (ResponseMode mode : ResponseMode.values()) {
            if (mode.ragThinkingSite() == site || mode.directThinkingSite() == site) return mode;
        }
        return ResponseMode.DEFAULT;   // answer-meta — 분류가 meta 로 판정한 답변은 기본 모드의 예약을 쓴다
    }

    private static ResponseMode evalModeOf(ThinkingSite site) {
        for (ResponseMode mode : ResponseMode.values()) {
            if (mode.evalThinkingSite() == site && mode.evalPromptKey() != null) return mode;
        }
        return ResponseMode.DEFAULT;
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────────────────────────

    /** 블로킹 응답 예산을 어느 항이 정했는가 — {@link ResponseMode#budgetTerm} 을 문구로 푼 것. 설정 상한이 없으면(0 이하) 말할 항이 없다. */
    private String blockingTerm(ResponseMode mode, int maxTokens) {
        if (maxTokens <= 0) return "-";
        return switch (mode.budgetTerm(maxTokens)) {
            case RATIO -> msg("settings.thinking.basis.blocking.ratio", Math.round(mode.tokenRatio() * 100));
            case FLOOR -> msg("settings.thinking.basis.blocking.floor");
            case CONFIGURED_CAP -> msg("settings.thinking.basis.blocking.cap");
        };
    }

    /** 한글 {@code chars} 자의 토큰 추정 — 화면 기준 줄이 말하는 "한글 1자 = 1토큰" 가정을 그대로 {@link TokenEstimator} 에 맡긴다. */
    static long koreanTokens(int chars) {
        return TokenEstimator.estimate("가".repeat(Math.max(0, chars)));
    }

    private String levelName(ThinkingLevel level) {
        return msg("settings.thinking.level." + level.value());
    }

    private String duration(long seconds) {
        if (seconds < 60) return msg("settings.thinking.duration.seconds", seconds);
        return msg("settings.thinking.duration.minutes", seconds / 60, seconds % 60);
    }

    private static String signed(long n) {
        return (n > 0 ? "+" : n < 0 ? "-" : "") + String.format(Locale.ROOT, "%,d", Math.abs(n));
    }

    /** 요청 로케일의 문구 — 키가 없으면 {@code NoSuchMessageException}(계산 시점에 드러난다). */
    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /** 한 번의 계산에서 같은 값을 다시 묻지 않게 — 설정 스냅샷과 프롬프트 크기·관측 속도 캐시. */
    private final class Ctx {
        final int maxTokens = props.llmSafe().maxTokens();
        final int topK = props.searchTopKSafe();
        final int chunkSize = props.chunkSizeSafe();
        final int batch = Math.max(1, props.indexingSafe().keywordBatchSize());
        final int candidateMultiplier = props.searchCandidateMultiplierSafe();
        final int readTimeout = props.llmSafe().readTimeoutSeconds();
        final RoutingMode defaultMode = router.getDefaultMode() == null ? RoutingMode.COST_FIRST : router.getDefaultMode();
        final long questionTokens = koreanTokens(QUESTION_CHARS);
        /** 대화 이력이 상한일 때의 길이 — 폴백 경로가 자르는 그 값({@code MemoryService.maxConversationChars}). */
        final int historyChars = MemoryService.conversationChars(maxTokens);
        private final Map<String, Long> promptTokens = new HashMap<>();
        private final Map<String, Double> speeds = new HashMap<>();
        private final Map<Boolean, Long> schemaTokens = new HashMap<>();

        long promptTokens(String key) {
            return promptTokens.computeIfAbsent(key, k -> {
                try {
                    return TokenEstimator.estimate(messages.getMessage(k, null, PROMPT_LOCALE));
                } catch (org.springframework.context.NoSuchMessageException e) {
                    return 0L;
                }
            });
        }

        /** 검증 응답 스키마의 토큰 — 스키마 생성(JSON 스키마 reflection)이 사이트·수준마다 다시 돌지 않게 한 번만 잰다. */
        long schemaTokens(boolean creative) {
            return schemaTokens.computeIfAbsent(creative, k -> TokenEstimator.estimate(AnswerService.evalSchema(k)));
        }

        Double speed(String provider) {
            if (provider == null) return null;
            return speeds.computeIfAbsent(provider, observations::speedTokensPerSecond);
        }

        ThinkingObservations.Stats stats(ThinkingSite site, Receiver receiver, ThinkingLevel level) {
            return receiver.none() ? ThinkingObservations.Stats.EMPTY
                    : ThinkingObservations.statsOf(observations.samples(site, receiver.name(), level));
        }

        Basis basis() {
            return new Basis(maxTokens, topK, chunkSize, batch, readTimeout, QUESTION_CHARS, SHORT_ANSWER_CHARS,
                    LONG_ANSWER_CHARS, calibration.ratio(), calibration.sampleCount());
        }
    }
}
