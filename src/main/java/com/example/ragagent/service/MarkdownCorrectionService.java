package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.IndexingOutputCap;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based Markdown format correction.
 *
 * <p>Two split strategies feed the same parallel per-section corrector:
 * <ul>
 *   <li><b>PPTX</b> ({@code groupByPage}) — {@link #splitByPages} bundles up to
 *       {@link #PPTX_MAX_BUNDLE_PAGES} self-contained {@code [페이지: N]} slides per call (up to
 *       {@link #maxSectionChars}); an oversized single slide is split on its shape-group blocks
 *       ({@link #splitOversizedPage}). Slide boundaries are always clean, so no overlap is used.</li>
 *   <li><b>Everything else</b> — {@link #splitBySections} splits on {@code ## }/{@code ### }/
 *       {@code #### } chapter headings (fence-aware). Only boundaries flagged
 *       {@link #isUnnaturalBoundary unnatural} (a malformed/level-jumping heading, or a size-forced
 *       mid-flow cut) carry a small overlap of the neighbour's lines, corrected in place around a
 *       boundary marker and then cut back off DETERMINISTICALLY in code
 *       ({@link #cutOverlap}) — so a code block a converter split across the boundary can be fenced
 *       coherently without the overlap ever surviving into two sections (the old duplication bug).</li>
 * </ul>
 * Corrects each section in parallel (format only, never changes content), then reassembles and
 * saves the corrected file alongside the raw one.
 *
 * <p>After reassembly, {@link #postProcessMarkdown} runs a final deterministic (no-LLM) cleanup pass
 * that, for PPTX only ({@code isPptx=true}), also applies {@link #applyPptxShapeFormatting} — a set
 * of shape-group/image-anchor formatting fixes for artifacts {@code PptxToMarkdownConverter} and the
 * LLM correction pass above tend to leave behind (missing blank lines around {@code [도형 그룹]}
 * blocks and image anchors, duplicate single-token lines from SmartArt/grouped-shape extraction,
 * stray blank lines between bullets). This same pass also re-runs unconditionally on re-index
 * ({@code DocumentIndexer.reindexFromMd()} → {@link #postProcess(String, boolean)}), so hand-editing
 * a saved PPTX MD file and re-indexing gets the same formatting guarantees as the original upload.
 */
@Service
public class MarkdownCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownCorrectionService.class);
    private static final int MIN_SECTION_CHARS = 500;

    /**
     * 교정 지시 프롬프트(본문 제외)의 대략적 토큰 수 — 섹션 예산 계산의 고정비.
     * 실측 ~1,100 토큰(한글 기준)에 여유를 얹었다. 프롬프트를 크게 늘리면 이 값도 함께 올릴 것.
     */
    private static final int CORRECTION_PROMPT_TOKENS = 1_300;

    /** 이미지 설명(2~3문장)이 {@code app.llm.max-tokens} 중 쓸 몫 — {@link IndexingOutputCap} 참고. */
    private static final double IMAGE_DESCRIPTION_OUTPUT_RATIO = 0.05;
    private static final Pattern MD_IMAGE_LINK = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
    private static final Pattern IMAGE_MARKER = Pattern.compile("\\[이미지:\\s*([^\\]]+)]");
    /** Whole-line variants of the above, used by the PPTX-only shape-group formatting passes
     *  ({@link #applyPptxShapeFormatting}) — these only ever match a marker sitting alone on its
     *  own line, which is how {@code PptxToMarkdownConverter} always emits them. */
    private static final Pattern IMAGE_LINE_FULL = Pattern.compile("^\\[이미지:\\s*[^\\]]+]$");
    private static final Pattern IMAGE_DESC_LINE_FULL = Pattern.compile("^\\[이미지 설명:.*]$");
    /** {@code [도형 그룹]} / {@code [도형 그룹 N]} (numbered only when 2+ groups share a slide —
     *  see {@code PptxToMarkdownConverter.appendShapeGroup}) open/close marker lines. */
    private static final Pattern SHAPE_GROUP_OPEN  = Pattern.compile("^\\[도형 그룹(?: \\d+)?]$");
    private static final Pattern SHAPE_GROUP_CLOSE = Pattern.compile("^\\[/도형 그룹(?: \\d+)?]$");
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```(.*?)\\n(.*?)\\n```");
    private static final Pattern HEADING_NUMBER_PREFIX = Pattern.compile("^(?:\\d+(?:\\.\\d+)*(?:\\.)?|\\d+[\\)])\\s+");
    /** Any comment-ish line — used to detect "function already has a comment right above it". */
    private static final Pattern COMMENT_LINE = Pattern.compile("^(?://|#|--|/\\*|\\*|\"\"\"|''')");
    /** Block-comment / docstring openers — always treated as the start of a "multi-line comment". */
    private static final Pattern BLOCK_COMMENT_OPEN = Pattern.compile("^(?:/\\*|\"\"\"|''')");
    /** Line-comment marker, captured so the next line can be checked for the same marker. */
    private static final Pattern LINE_COMMENT_MARKER = Pattern.compile("^(//|#|--)");
    /** Heuristic function/class/method signature start across common languages. */
    private static final Pattern FUNCTION_START = Pattern.compile(
            "^(?:public|private|protected|static|final|abstract|synchronized|async|export|default|virtual|override|readonly)\\s+.*\\([^;{}]*\\)\\s*\\{?\\s*$"
          + "|^(?:async\\s+)?def\\s+\\w+\\s*\\(.*\\)\\s*:?\\s*$"
          + "|^(?:class|interface|enum|struct|trait)\\s+\\w+.*$"
          + "|^function\\s+\\w*\\s*\\(.*\\)\\s*\\{?\\s*$"
          + "|^(?:fun|func|fn)\\s+\\w+\\s*\\(.*$"
          + "|^\\w+\\s*\\(\\)\\s*\\{\\s*$");
    /**
     * Strong, Java/JVM-exclusive signals — none of these appear in a plain SQL script, so a match is
     * a reliable "this is Java, not SQL". Used both to positively identify untagged Java and to fix
     * the frequent "Java misdetected as SQL" case (the LLM or the old greedy {@code \bdelete\b}-style
     * SQL rule tagging a Java block whose {@code .delete(...)}/{@code .select(...)} method calls only
     * looked SQL-ish).
     */
    private static final Pattern JAVA_CODE_SIGNAL = Pattern.compile(
            "(?m)^\\s*package\\s+[\\w.]+;"
          + "|(?m)^\\s*import\\s+(?:java|javax|jakarta|org|com)\\."
          + "|(?m)^\\s*@[A-Z]\\w+"
          + "|\\b(?:public|private|protected)\\s+(?:static\\s+|final\\s+|abstract\\s+)*(?:class|interface|enum|record)\\b"
          + "|\\bpublic\\s+static\\s+void\\s+main\\b"
          + "|\\bSystem\\.(?:out|err)\\."
          + "|\\.print(?:ln|f)?\\s*\\("
          + "|\\b(?:void|boolean|int|long|double|float|char|byte|short)\\s+\\w+\\s*\\("
          + "|\\b(?:String|Integer|Long|Boolean|Double|Object)\\s+\\w+\\s*[=;]"
          + "|\\bnew\\s+[A-Z]\\w*\\s*[(<]"
          + "|\\b(?:implements|extends|throws)\\s+[A-Z]\\w*"
          + "|\\bcatch\\s*\\(|\\btry\\s*\\{"
          + "|\\b(?:List|Map|Set|Optional|ArrayList|HashMap)\\s*<");
    /**
     * A real SQL statement — SELECT/UPDATE anchored at line start (so a Java {@code x.select(...)} /
     * {@code x.update(...)} method call is NOT matched), and distinctive multi-word forms
     * ({@code INSERT INTO}, {@code DELETE FROM}, {@code CREATE TABLE}, …) that Java identifiers don't
     * form. Replaces the old bare {@code \b(select|insert|update|delete)\b} that matched Java method calls.
     */
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?ism)^\\s*select\\b.+?\\bfrom\\b"
          + "|\\binsert\\s+into\\b"
          + "|(?ism)^\\s*update\\s+\\S+\\s+set\\b"
          + "|\\bdelete\\s+from\\b"
          + "|\\bcreate\\s+(?:table|index|view|sequence|or\\s+replace)\\b"
          + "|\\balter\\s+table\\b"
          + "|\\bdrop\\s+(?:table|index|view)\\b"
          + "|\\bmerge\\s+into\\b"
          + "|\\btruncate\\s+table\\b");
    /**
     * Non-blank overlap lines carried across an UNNATURAL section boundary (see
     * {@link #isUnnaturalBoundary}). The previous section's tail (head overlap) and/or the next
     * section's head (tail overlap) are prepended/appended around a {@link #SECTION_START_BOUNDARY}/
     * {@link #SECTION_END_BOUNDARY} marker so the LLM sees across a boundary a converter/code
     * artifact may have put mid-flow (e.g. an unfenced code block whose "##" lines were mistaken
     * for headings). The overlap is corrected in place and then cut back off DETERMINISTICALLY by
     * code at the marker — never trusted to the model to omit — so no content is ever duplicated.
     */
    private static final int OVERLAP_LINES = 5;
    /** Max PPTX slides bundled into one correction call, subject to {@link #maxSectionChars}. */
    private static final int PPTX_MAX_BUNDLE_PAGES = 4;
    /** Marker placed right BEFORE this section's real content, after a prepended previous-section
     *  overlap. The model reproduces it verbatim; {@link #cutOverlap} drops everything up to and
     *  including it. */
    private static final String SECTION_START_BOUNDARY = "<<<SECTION_START>>>";
    /** Marker placed right AFTER this section's real content, before an appended next-section
     *  overlap. The model reproduces it verbatim; {@link #cutOverlap} drops everything from it on. */
    private static final String SECTION_END_BOUNDARY = "<<<SECTION_END>>>";
    /** Prefix/suffix of the per-table placeholder marker — see {@link #extractTables}. */
    private static final String TABLE_PLACEHOLDER_PREFIX = "[TABLE_PLACEHOLDER_";
    private static final String TABLE_PLACEHOLDER_SUFFIX = "]";

    private final LlmRouter llmRouter;
    private final AppProperties props;
    /**
     * {@code app.llm.max-tokens} 에서 나온 섹션 크기 상한 — <b>창을 모를 때의 값</b>이다.
     * 실제로 쓰는 값은 {@link #sectionCharBudget()} 이며, 창을 알면 거기서 더 줄어들 수 있다.
     */
    private final ProviderContextWindows contextWindows;
    private final int maxSectionChars;
    private final String defaultCodeLanguage;

    // Single source of truth for "LLM max tokens" (app.llm.max-tokens / LLM_MAX_TOKENS, default
    // 6000) — used to read the separate, dead spring.ai.openai.chat.options.max-tokens property
    // (default 8000), which config'd nothing (Spring AI's autoconfigured ChatModel bean is skipped
    // since LlmConfig.primaryChatModel() already satisfies its @ConditionalOnMissingBean).
    public MarkdownCorrectionService(LlmRouter llmRouter, AppProperties props,
                                     ProviderContextWindows contextWindows) {
        this.llmRouter = llmRouter;
        this.props = props;
        this.contextWindows = contextWindows;
        int llmMaxTokens = props.llmSafe().maxTokens();
        this.maxSectionChars = Math.max(MIN_SECTION_CHARS, (llmMaxTokens - MIN_SECTION_CHARS) / 2);
        this.defaultCodeLanguage = props.mdCorrectionDefaultCodeLanguageSafe();
    }

    /**
     * Indexing/background temperature (hot-editable), read fresh per call — see AppProperties.LlmConfig.
     *
     * <p><b>출력 상한을 함께 싣는다</b>({@link IndexingOutputCap}). 이걸 비워 두면 프로바이더 빈에
     * 구워진 {@code app.llm.max-tokens} 전체가 출력으로 <b>예약</b>되는데, 서버는
     * {@code 프롬프트 + max_tokens ≤ n_ctx} 를 검사하므로 그만큼 입력 자리가 사라진다. 창 20,480 ·
     * {@code max-tokens=10000} 배포에서 MD 교정이 컨텍스트 초과로 실패한 것이 정확히 이 조합이었다 —
     * 같은 프로퍼티가 {@link #maxSectionChars} 까지 정하므로 입력과 예약이 함께 커진다.
     */
    /**
     * 이번 교정 호출에 넣을 섹션의 글자 상한.
     *
     * <p>{@link #maxSectionChars}(= {@code max-tokens} 파생)와 <b>프로바이더 창에서 나온 값</b> 중
     * 작은 쪽이다. 두 값이 다른 것을 재는 탓에 어느 한쪽만으로는 부족하다 — 앞의 것은 "출력이
     * 이만큼이면 입력은 이 정도"라는 어림이고, 뒤의 것은 "이 서버에 실제로 들어가는 양"이다.
     * 창 20,480 · {@code max-tokens=10000} 배포에서 교정이 컨텍스트 초과로 실패한 것은 앞의 값만
     * 보고 있었기 때문이다.
     *
     * <p><b>줄이기만 한다.</b> 창이 넉넉하면 계산상 더 큰 섹션도 들어가지만, 섹션을 키우면 교정
     * 결과 자체가 달라진다(경계가 이동하고 한 호출이 보는 문맥이 바뀐다) — 초과를 막으러 온
     * 변경이 멀쩡한 배포의 인덱싱 결과를 바꿀 이유는 없다.
     *
     * <p>창을 모르면 {@link #maxSectionChars} 그대로다(예전 동작).
     */
    private int sectionCharBudget() {
        int window = contextWindows.tokensOrZero(
                llmRouter.findProviderName(TaskType.LIGHT_TEXT, RoutingMode.COST_FIRST));
        if (window <= 0) return maxSectionChars;
        int fromWindow = PromptBudget.rewriteInputChars(window, CORRECTION_PROMPT_TOKENS);
        if (fromWindow <= 0) return maxSectionChars;   // 창이 지시 프롬프트도 못 담는다 — 판단 불가
        return Math.min(maxSectionChars, Math.max(MIN_SECTION_CHARS, fromWindow));
    }

    private OpenAiChatOptions indexingOptions(int maxTokens) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
                .temperature(props.llmSafe().indexingTemperature());
        if (maxTokens > 0) b.maxTokens(maxTokens);   // 0 = 프로바이더 기본값 유지
        return b.build();
    }

    /**
     * Corrects the formatting of {@code rawMd} section by section using the LLM.
     * Saves the corrected result to {@code correctedOutputPath} and returns it.
     * On any LLM failure the original section text is kept (graceful fallback).
     */
    public String correct(String rawMd, String docId, Path correctedOutputPath) {
        return correct(rawMd, docId, correctedOutputPath, false, false, false, null);
    }

    /** Same as {@link #correct(String, String, Path)} with image-description toggle. */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          boolean addImageDescriptions) {
        return correct(rawMd, docId, correctedOutputPath, addImageDescriptions, false, false, null);
    }

    /** Same as {@link #correct(String, String, Path, boolean)} with heading-number second pass. */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          boolean addImageDescriptions,
                          boolean addHeadingNumbers) {
        return correct(rawMd, docId, correctedOutputPath, addImageDescriptions, addHeadingNumbers, false, null);
    }

    /**
     * Same as {@link #correct(String, String, Path)} but calls {@code onSectionDone(done, total)}
     * after each section completes — useful for streaming progress to the UI.
     */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          BiConsumer<Integer, Integer> onSectionDone) {
        return correct(rawMd, docId, correctedOutputPath, false, false, false, onSectionDone);
    }

    /**
     * Same as {@link #correct(String, String, Path, boolean, boolean)} with a section-progress
     * callback plus a section-splitting mode: {@code groupByPage=true} (PPTX) splits strictly at
     * {@code [페이지: N]} slide markers instead of every H2/H3 heading, so a slide's ##/### heading
     * pair is never torn into two correction calls (see {@link #splitByPages}).
     */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          boolean addImageDescriptions,
                          boolean addHeadingNumbers,
                          boolean groupByPage,
                          BiConsumer<Integer, Integer> onSectionDone) {
        return correct(rawMd, docId, correctedOutputPath, addImageDescriptions, addHeadingNumbers,
                groupByPage, onSectionDone, null);
    }

    /**
     * Same as the 7-arg overload but also reports Vision image-description progress via
     * {@code onImageDescribed(done, total)} — invoked once per completed image (plus an initial
     * {@code (0, total)} call as soon as the image count is known), all *before* section
     * correction/{@code onSectionDone} starts (see {@link #augmentImageDescriptionsWithLocalVision}).
     * {@code null} is a no-op, so existing callers are unaffected.
     */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          boolean addImageDescriptions,
                          boolean addHeadingNumbers,
                          boolean groupByPage,
                          BiConsumer<Integer, Integer> onSectionDone,
                          BiConsumer<Integer, Integer> onImageDescribed) {
        if (rawMd == null || rawMd.isBlank()) return rawMd;
        log.info("[MD_CORRECT] 시작: docId={}, chars={}", docId, rawMd.length());
        // Hot-editable (indexing family) — read fresh per correction run so a /settings override
        // applies on the next indexing without a restart, exactly like DocumentIndexer's keyword
        // gate and LazyVisionService. Never cache this in a field.
        int maxConcurrent = Math.max(1, props.indexingSafe().maxConcurrentLlmCalls());
        log.debug("[MD_CORRECT] 설정: maxConcurrent={}, maxSectionChars={} (설정 파생 {})",
                maxConcurrent, sectionCharBudget(), maxSectionChars);
        long t0 = System.currentTimeMillis();

        String preprocessed = addImageDescriptions
                ? augmentImageDescriptionsWithLocalVision(rawMd, correctedOutputPath, onImageDescribed)
                : rawMd;

        List<String> sections = groupByPage ? splitByPages(preprocessed) : splitBySections(preprocessed);
        log.debug("[MD_CORRECT] 섹션 {}개 분할 완료 (groupByPage={})", sections.size(), groupByPage);
        int total = sections.size();

        // Only UNNATURAL boundaries (converter/code artifacts, size-forced mid-flow cuts) carry
        // overlap context; clean chapter breaks — and every PPTX page/bundle boundary, since slides
        // are self-contained — split with no overlap, so most boundaries pay nothing and can never
        // duplicate. unnaturalAfter[i] == the boundary between section i and section i+1.
        boolean[] unnaturalAfter = new boolean[sections.size()];
        if (!groupByPage) {
            for (int i = 0; i + 1 < sections.size(); i++) {
                unnaturalAfter[i] = isUnnaturalBoundary(sections.get(i), sections.get(i + 1));
            }
        }

        Semaphore gate = new Semaphore(maxConcurrent);
        AtomicInteger doneCount = new AtomicInteger(0);
        List<String> corrected;
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<String>> futures = new ArrayList<>(sections.size());
            for (int i = 0; i < sections.size(); i++) {
                final String sec = sections.get(i);
                // Head overlap: the PREVIOUS section's last few non-blank lines, prepended only when
                // the boundary INTO this section was flagged unnatural. Corrected in place, then cut
                // back off deterministically by code (see correctSection/cutOverlap).
                final String headOverlap = (i > 0 && unnaturalAfter[i - 1])
                        ? trailingNonBlankLines(sections.get(i - 1), OVERLAP_LINES) : "";
                // Tail overlap: the NEXT section's first few non-blank lines, appended only when the
                // boundary OUT of this section is unnatural. Same deterministic cut on the other end.
                final String tailOverlap = (i + 1 < sections.size() && unnaturalAfter[i])
                        ? leadingNonBlankLines(sections.get(i + 1), OVERLAP_LINES) : "";
                futures.add(CompletableFuture.supplyAsync(() -> {
                    gate.acquireUninterruptibly();
                    try {
                        String result = correctSection(sec, tailOverlap, headOverlap);
                        int done = doneCount.incrementAndGet();
                        if (onSectionDone != null) onSectionDone.accept(done, total);
                        return result;
                    } finally {
                        gate.release();
                    }
                }, exec));
            }
            corrected = futures.stream().map(CompletableFuture::join).toList();
        } catch (CompletionException ce) {
            if (ce.getCause() instanceof LlmProviderExhaustedException) {
                log.info("[MD_CORRECT] LLM 사용 불가, 원본 유지: docId={}", docId);
                return rawMd;
            }
            throw ce;
        }

        String result = String.join("\n\n", corrected);
        // FIX: a ```lang-tagged CLOSING fence → bare ``` — must run BEFORE normalizeCodeBlocks so its
        // fence regex sees well-formed pairs.
        result = fixClosingFences(result);
        // inferLanguage=true unconditionally: a code block's language tag has nothing to do with the
        // "소제목 숫자 생성" checkbox, but used to ride along on it (inference only ran inside the
        // addHeadingNumbers-gated second pass). That left every PPTX — which always forces
        // addHeadingNumbers=false (see DocumentIndexer's .pptx branch) — and every DOCX/TXT/MD
        // uploaded with the box unchecked with untagged fences.
        result = normalizeCodeBlocks(result, true);
        if (addHeadingNumbers) {
            // Heading numbering only — code blocks are already normalized + tagged above, and this
            // pass is fence-aware (skips fence interiors), so there is nothing left for it to polish.
            result = addHierarchicalHeadingNumbers(result);
        }
        // FIX: deterministic final cleanup — blank lines around code blocks/tables, drop leftover
        // [DOCUMENT] markers and content-less '-' lines (all fence-aware). Runs last. groupByPage is
        // true only for PPTX (see class javadoc), so it doubles as the isPptx flag here.
        result = postProcessMarkdown(result, groupByPage);
        log.info("[MD_CORRECT] 완료: docId={}, {}ms", docId, System.currentTimeMillis() - t0);

        if (correctedOutputPath != null) {
            try {
                Files.createDirectories(correctedOutputPath.getParent());
                Files.writeString(correctedOutputPath, result);
                log.debug("[MD_CORRECT] 저장: {}", correctedOutputPath);
            } catch (IOException e) {
                log.warn("[MD_CORRECT] 파일 저장 실패 {}: {}", correctedOutputPath, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Re-checks and re-computes hierarchical heading numbers on already-numbered markdown — no
     * LLM call, no code-block normalization. Used by {@code DocumentIndexer.reindexFromMd()}: the
     * saved MD may have been edited since the numbers were first assigned at upload time (e.g. a
     * code block was split/merged or a section removed, shifting which H2-H6 headings exist or
     * where), leaving stale numbers behind. {@link #addHierarchicalHeadingNumbers} always strips
     * any existing numeric prefix before recomputing, so calling it again here fixes staleness
     * regardless of what the old numbers were.
     *
     * <p>A no-op when {@code md} has no numbered heading already ({@link #hasNumberedHeading}) —
     * a document that never had heading numbers (checkbox was off at upload, or is a PPTX, which
     * never gets them — see {@code DocumentIndexer}'s {@code .pptx} branch) never gains them here
     * either; this method only ever refreshes numbers that already exist.
     */
    public String reapplyHeadingNumbers(String md) {
        if (md == null || md.isBlank() || !hasNumberedHeading(md)) return md;
        return addHierarchicalHeadingNumbers(md);
    }

    /**
     * Public entry point for {@link #postProcessMarkdown} — deterministic, no-LLM cleanup (blank-line
     * collapsing, leftover prompt-marker/content-less-dash removal, blank line guarantee around
     * fences/tables). Used by {@code DocumentIndexer.reindexFromMd()} to re-apply this cleanup to a
     * saved MD file without re-running the full (LLM section-by-section) {@link #correct} pipeline.
     * Equivalent to {@code postProcess(md, false)} — no PPTX-only shape-group formatting.
     */
    public String postProcess(String md) {
        return postProcessMarkdown(md, false);
    }

    /**
     * Same as {@link #postProcess(String)} but also applies the PPTX-only shape-group/image-anchor
     * formatting fixes ({@link #applyPptxShapeFormatting}) when {@code isPptx} is true. Used by
     * {@code DocumentIndexer.postProcessIfNeeded()} on re-index, where the source filename (and
     * therefore format) is known.
     */
    public String postProcess(String md, boolean isPptx) {
        return postProcessMarkdown(md, isPptx);
    }

    /** True if any H2-H6 heading (outside a fenced code block) already starts with a numeric prefix. */
    private boolean hasNumberedHeading(String md) {
        boolean inFence = false;
        for (String line : md.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;

            int level = markdownHeadingLevel(line);
            if (level < 2 || level > 6) continue;
            String text = line.substring(level + 1).trim();
            if (HEADING_NUMBER_PREFIX.matcher(text).find()) return true;
        }
        return false;
    }

    /**
     * Splits by H2/H3/H4 headings <em>and</em> {@code [페이지: N]} markers, but never while inside a
     * fenced code block (``` / ~~~). Log dumps and batch output are often pasted verbatim into a
     * fence and commonly contain lines like {@code "### Job ID : ..."} that only look like headings —
     * treating them as real section boundaries splits the fence in half, and each half then goes to
     * the LLM with no idea it's inside (or missing) a code block, which reliably produces
     * hallucinated language tags, re-wrapped fences, or leaked prompt delimiters in the corrected
     * output.
     *
     * <p>The {@code [페이지: N]} boundary matters for non-scanned PDF: {@code PdfToMarkdownConverter}
     * no longer emits a synthetic {@code ## N페이지} heading, so the page marker is now the only
     * per-page boundary — without it a whole plain-text PDF would collapse into one oversized
     * correction section. Only PDF/PPTX ever emit {@code [페이지: N]}; DOCX/TXT/MD never do, so this
     * is a no-op for them. (PPTX itself uses {@link #splitByPages}, not this method.)
     */
    List<String> splitBySections(String md) {
        List<String> raw = splitByBoundary(md,
                line -> line.startsWith("## ") || line.startsWith("### ") || line.startsWith("#### ")
                        || line.startsWith("[페이지: "),
                true);
        return mergeSmallSections(raw);
    }

    /**
     * Bundles consecutive small sections up to {@link #maxSectionChars} into one correction call —
     * same pattern {@link #splitByPages} already uses to bundle PPTX slides. Without this, a
     * document with frequent short headings (a heading every few lines — common in DOCX/MD with
     * deep subsection structure) sent one tiny LLM call per heading instead of a handful of
     * well-filled ones, multiplying round-trips (and, since this runs during indexing, wall-clock
     * time) for no correction-quality benefit. Each input section is already guaranteed
     * fence-complete by {@link #splitByBoundary} (a heading boundary never splits mid-fence), so
     * plain concatenation is always safe — no re-parsing needed.
     *
     * <p>An already-oversized section (from {@code splitByBoundary}'s own size enforcement) is left
     * on its own: the budget check only fires once the running bundle is non-empty, so a single
     * over-budget section never grows further, and the next section starts a fresh bundle rather
     * than piling onto it.
     */
    private List<String> mergeSmallSections(List<String> raw) {
        List<String> merged = new ArrayList<>();
        StringBuilder bundle = new StringBuilder();
        for (String section : raw) {
            if (!bundle.isEmpty() && bundle.length() + section.length() > sectionCharBudget()) {
                merged.add(bundle.toString());
                bundle.setLength(0);
            }
            bundle.append(section);
        }
        if (!bundle.isEmpty()) merged.add(bundle.toString());
        return merged;
    }

    /**
     * PPTX-only split: bundle up to {@link #PPTX_MAX_BUNDLE_PAGES} consecutive {@code [페이지: N]}
     * slides into one correction call, filling each bundle up to {@link #maxSectionChars} before
     * starting the next. Slides are self-contained (each {@code [페이지: N]} + heading(s) + body),
     * so every bundle boundary lands cleanly on a page marker and needs no overlap context — and
     * batching several small slides per call cuts the LLM round-trips a per-slide split would make.
     *
     * <p>A single slide larger than {@link #maxSectionChars} can't be bundled; it's split on its
     * own by {@link #splitOversizedPage} (at shape-group/diagram/chart block boundaries) and its
     * pieces are emitted un-bundled.
     */
    List<String> splitByPages(String md) {
        // Phase 1: cut into raw per-slide blocks (fence-aware, NO size force-split — an oversized
        // slide must stay whole here so phase 2 can split it on shape-group boundaries, not chars).
        List<String> pages = splitByBoundary(md, line -> line.startsWith("[페이지: "), false);

        List<String> sections = new ArrayList<>();
        StringBuilder bundle = new StringBuilder();
        int bundlePages = 0;
        for (String page : pages) {
            if (page.length() > sectionCharBudget()) {
                if (bundle.length() > 0) { sections.add(bundle.toString()); bundle.setLength(0); bundlePages = 0; }
                sections.addAll(splitOversizedPage(page));
                continue;
            }
            if (bundle.length() > 0
                    && (bundlePages >= PPTX_MAX_BUNDLE_PAGES
                        || bundle.length() + page.length() > sectionCharBudget())) {
                sections.add(bundle.toString());
                bundle.setLength(0);
                bundlePages = 0;
            }
            bundle.append(page);
            bundlePages++;
        }
        if (bundle.length() > 0) sections.add(bundle.toString());
        return sections;
    }

    /**
     * Splits a single over-budget PPTX slide at its shape-group / diagram / chart block boundaries
     * ({@code [도형 그룹]}, {@code [다이어그램]}, {@code [차트]} — each emitted by
     * {@code PptxToMarkdownConverter} as a self-contained {@code [label] … [/label]} block) so a
     * grouped-shape block stays whole in one correction call. The {@code [페이지: N]} marker and
     * heading(s) that precede the first block stay attached to the first piece. A single block still
     * larger than {@link #maxSectionChars} falls back to the shared char-budget force-split.
     */
    private List<String> splitOversizedPage(String page) {
        return splitByBoundary(page, line -> {
            String t = line.stripLeading();
            return t.startsWith("[도형 그룹") || t.startsWith("[다이어그램") || t.startsWith("[차트");
        }, true);
    }

    /**
     * Fence-aware splitter shared by {@link #splitBySections}, {@link #splitByPages} and
     * {@link #splitOversizedPage}: never splits while inside a fenced code block (``` / ~~~),
     * regardless of what {@code isBoundaryLine} matches.
     *
     * <p>When {@code enforceSize} is true and a section grows past {@link #maxSectionChars}, it is
     * force-split so no single correction call is oversized. If the check trips while a fence is
     * still open, the fence is not cut — but it also isn't unconditionally kept in the current
     * (already-full) section. If the fence started at or after {@code MIN_SECTION_CHARS / 2} chars
     * into the current section, everything before the fence is flushed now and the fence is deferred
     * whole to the next section; if it started very early in a (so far small) section, deferring
     * would leave a tiny orphan, so it's left to keep growing until it closes. When
     * {@code enforceSize} is false the size check is skipped entirely — used by
     * {@link #splitByPages} phase 1, which must keep each slide whole (even an oversized one) so it
     * can be split on shape-group boundaries rather than an arbitrary char offset.
     */
    private List<String> splitByBoundary(String md, Predicate<String> isBoundaryLine, boolean enforceSize) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inFence = false;
        int fenceStartInSection = 0; // current.length() right before the currently-open fence began

        for (String line : md.split("\n", -1)) {
            String trimmed = line.stripLeading();
            boolean isFenceLine = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            boolean isBoundary = !inFence && isBoundaryLine.test(line);

            if (isBoundary && !current.isEmpty()) {
                sections.add(current.toString());
                current = new StringBuilder();
            }

            if (isFenceLine && !inFence) {
                fenceStartInSection = current.length(); // remember where this fence begins
            }
            current.append(line).append("\n");
            if (isFenceLine) inFence = !inFence;

            if (enforceSize && current.length() > sectionCharBudget()) {
                if (!inFence) {
                    sections.add(current.toString());
                    current = new StringBuilder();
                } else if (fenceStartInSection >= MIN_SECTION_CHARS / 2) {
                    sections.add(current.substring(0, fenceStartInSection));
                    current = new StringBuilder(current.substring(fenceStartInSection));
                    fenceStartInSection = 0;
                }
                // else: fence started very early in this section — let it keep growing.
            }
        }
        if (!current.isEmpty()) {
            if (inFence) current.append("```\n"); // malformed input (fence never closed) — close it out
            sections.add(current.toString());
        }
        return sections;
    }

    /**
     * True when the boundary between {@code before} and {@code after} looks like a converter/code
     * artifact rather than a clean chapter break, so it should carry deterministic overlap context.
     * Three signals (all confirmed with the user):
     * <ol>
     *   <li><b>Non-heading start</b> — {@code after}'s first non-blank line is not a well-formed
     *       {@code ## }/{@code ### }/{@code #### } heading. Covers a size-forced mid-flow cut, a
     *       stray {@code # } (H1) mid-document, and decorative/log lines like {@code #=====} or
     *       {@code #########} that aren't valid ATX headings.</li>
     *   <li><b>Heading level jump</b> — a well-formed heading that dives two or more levels below
     *       the last heading before it (e.g. {@code ##} then {@code ####}, skipping {@code ###}),
     *       which usually means a skipped level or a {@code ####} mistaken from inside code.</li>
     * </ol>
     * Package-private for unit testing.
     */
    boolean isUnnaturalBoundary(String before, String after) {
        int afterLevel = leadingChapterHeadingLevel(after);
        if (afterLevel == 0) return true;                       // non-heading / malformed start
        int beforeLevel = lastChapterHeadingLevel(before);
        return beforeLevel >= 2 && afterLevel - beforeLevel >= 2; // e.g. ## then ####
    }

    /** Heading level of {@code section}'s first non-blank line if it is a well-formed ATX heading,
     *  else 0. */
    private int leadingChapterHeadingLevel(String section) {
        for (String line : section.split("\n", -1)) {
            if (line.isBlank()) continue;
            return chapterHeadingLevel(line);
        }
        return 0;
    }

    /** Level of the last well-formed heading (outside a fenced code block) in {@code section},
     *  else 0. */
    private int lastChapterHeadingLevel(String section) {
        boolean inFence = false;
        int last = 0;
        for (String line : section.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) { inFence = !inFence; continue; }
            if (inFence) continue;
            int lvl = chapterHeadingLevel(line);
            if (lvl > 0) last = lvl;
        }
        return last;
    }

    /**
     * ATX heading level for a well-formed 2–6 heading ({@code n} '#' then a single space then
     * non-blank text), else 0. Rejects H1 (a stray mid-document title is itself a signal), 7+ '#',
     * and a '#' not followed by a space (e.g. {@code #=====}, a decorative/code line) — all of which
     * {@link #markdownHeadingLevel} already reports as 0 except H1, filtered here.
     */
    private int chapterHeadingLevel(String line) {
        int level = markdownHeadingLevel(line);
        if (level < 2 || level > 6) return 0;
        return line.substring(level).isBlank() ? 0 : level;
    }

    /** Package-private for unit testing. Corrects a single section with no overlap context. */
    String correctSection(String section) {
        return correctSection(section, "");
    }

    /** Same as {@link #correctSection(String, String, String)} with no head overlap. */
    String correctSection(String section, String tailOverlap) {
        return correctSection(section, tailOverlap, "");
    }

    /**
     * Corrects one section's formatting. {@code tailOverlap} (the opening lines of the NEXT section)
     * and {@code headOverlap} (the closing lines of the PREVIOUS section) are only supplied when the
     * corresponding boundary was flagged {@link #isUnnaturalBoundary unnatural}. Each is framed with
     * a {@link #SECTION_END_BOUNDARY}/{@link #SECTION_START_BOUNDARY} marker line and — unlike a
     * read-only preview — is corrected in place along with the section, so the LLM can see a code
     * block continuing across a boundary a converter/code artifact created (e.g. an unfenced block
     * whose "##" lines were mistaken for headings) and fence each side coherently. The model is told
     * to keep the marker line(s) verbatim; {@link #cutOverlap} then removes everything on the
     * overlap side of each marker DETERMINISTICALLY, so the overlap can never survive into two
     * adjacent sections (the old duplication bug). If a marker the code injected is missing from the
     * response, the section is re-corrected with no overlap at all — never trust a partial cut.
     * Package-private for unit testing.
     */
    String correctSection(String section, String tailOverlap, String headOverlap) {
        if (section == null || section.isBlank()) return section;
        String safeSection = stripReservedMarkers(section);
        boolean hasTail = tailOverlap != null && !tailOverlap.isBlank();
        boolean hasHead = headOverlap != null && !headOverlap.isBlank();
        boolean hasOverlap = hasTail || hasHead;

        StringBuilder bodyBuilder = new StringBuilder();
        if (hasHead) {
            bodyBuilder.append(stripReservedMarkers(headOverlap)).append("\n")
                    .append(SECTION_START_BOUNDARY).append("\n");
        }
        bodyBuilder.append(safeSection);
        if (hasTail) {
            bodyBuilder.append("\n").append(SECTION_END_BOUNDARY).append("\n")
                    .append(stripReservedMarkers(tailOverlap));
        }
        String body = bodyBuilder.toString();
        log.debug("[MD_CORRECT] 섹션 교정 시작: {}자 (headOverlap={}, tailOverlap={})",
                safeSection.length(), hasHead, hasTail);

        // 표는 LLM에게 아예 보여주지 않는다 — "표는 변경 금지"라는 프롬프트 지시만으로는 로컬 모델이
        // 셀 안의 ':' 를 '|' 로 바꾸는 등 표를 훼손하는 사례가 있었다. [TABLE_PLACEHOLDER_N] 자리표시자로
        // 치환해 보내고, 응답에서 그대로 복원한다(같은 종류의 [이미지: ...] 류 대괄호 마커는 이미
        // 안정적으로 보존되는 것으로 검증됨).
        TableExtraction tableExtraction = extractTables(body);

        String boundaryNote = hasOverlap ? buildBoundaryNote(hasHead, hasTail) : "";

        String prompt = ("""
                당신은 문서 편집자입니다. 다음 마크다운 텍스트의 형식(포맷)만 교정하세요.
                - 내용(사실, 데이터, 수치, 의미)을 변경 절대 금지

                교정 항목:
                - 잘린 문장 연결 (줄바꿈으로 끊긴 문장을 이어붙이기)
                - 명백한 오타 수정
                - 마커 형식 유지: [이미지: ...], [이미지(변환불가): ...], [헤딩페이지: N], [페이지: N], [TABLE_PLACEHOLDER_N](표를 대신하는 자리표시자, N은 숫자)을 그대로 둘 것 — 특히 [TABLE_PLACEHOLDER_N]은 절대 표 형식으로 채우거나 다른 형태로 바꾸지 말고 그 줄 그대로 둘 것
                - 코드/로그/명령어(CLI)/설정 파일처럼 보이지만 코드 블록(```)으로 감싸이지 않은 부분은 반드시 코드 블록으로 감싸세요. 언어를 알 수 있으면 그 언어 태그를, 판단이 어려우면 `%s` 태그를 사용하세요.
                - 코드/로그 안에서 "#", "##", "###"으로 시작하는 줄은 마크다운 제목이 아니라 코드 내용(주석·배너·출력)입니다. 반드시 코드 블록 안에 두고 제목으로 바꾸지 마세요.
                - 이미 코드 블록(```)으로 감싸인 내용은 그대로 유지: 언어 태그가 이미 있으면 유지 (코드 블록 안의 로그·일반 텍스트 출력은 그대로 유지)
                - 코드 블록(```) 내부의 불필요한 공란을 줄이고, 빈 줄 최소화로 가독성을 높일 것 (의미는 변경 금지)
                - 연속된 빈 줄 1개로 정리
                - 응답에 [DOCUMENT], [/DOCUMENT] 같은 구분자를 절대 포함하지 말 것%s

                교정된 마크다운만 반환하세요. 설명이나 주석을 추가하지 마세요.

                [DOCUMENT]
                %s
                [/DOCUMENT]""").formatted(defaultCodeLanguage, boundaryNote, tableExtraction.protectedText());
        try {
            String result = llmRouter.executeWithTracking(
                    TaskType.LIGHT_TEXT, RoutingMode.COST_FIRST, BackgroundUsage.MDCORRECT_PREFIX,
                    model -> model.call(new Prompt(prompt, indexingOptions(
                            // 재작성이라 출력은 이 섹션 크기에 묶인다 — 지시문은 입력일 뿐 출력이 아니다.
                            IndexingOutputCap.forRewrite(tableExtraction.protectedText(),
                                    props.llmSafe().maxTokens())))));
            log.debug("[MD_CORRECT] 섹션 교정 완료: {}자 → {}자", safeSection.length(), result.length());

            if (!tableExtraction.tables().isEmpty()) {
                String restored = restoreTables(result, tableExtraction.tables());
                if (restored == null) {
                    // 자리표시자가 유실됨 — 응답을 신뢰할 수 없으므로 표 위치를 추측해 끼워 넣지 않고
                    // 이 섹션은 교정 없이 원본을 그대로 유지한다.
                    log.warn("[MD_CORRECT] 표 자리표시자 유실 — 섹션 교정을 건너뛰고 원본 유지");
                    return section;
                }
                result = restored;
            }

            if (hasOverlap) {
                String cut = cutOverlap(result, hasHead, hasTail);
                if (cut != null) {
                    return acceptIfFencesBalanced(cut, section);
                }
                // A marker the code injected is gone from the response → we can't tell where the
                // overlap ends, so re-correct with no overlap at all rather than risk keeping it.
                log.debug("[MD_CORRECT] 경계 마커 누락 — 오버랩 없이 재교정");
                return correctSection(section, "", "");
            }
            return acceptIfFencesBalanced(result, section);
        } catch (LlmProviderExhaustedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[MD_CORRECT] 섹션 교정 실패, 원본 유지: {}", e.getMessage());
            return section;
        }
    }

    /**
     * Accepts the corrected section only if the span that will actually land in the document has an
     * even number of fence lines; otherwise keeps {@code original} untouched — the same "never guess"
     * discipline as the table-placeholder and boundary-marker guards above.
     *
     * <p>Two things can leave an odd count here even though every input section is balanced by
     * construction ({@code splitByBoundary} never cuts inside a fence and closes a malformed trailing
     * fence): the model dropping/adding a lone fence, and {@link #cutOverlap} slicing at the boundary
     * marker when the model wrapped code spanning that marker in ONE fence instead of one per side
     * (which {@link #buildBoundaryNote} asks for, but that is prompt compliance, not a guarantee) —
     * the cut then takes half the pair with it. A single unbalanced section desyncs fence pairing for
     * the rest of the joined document, so it is worth losing one section's formatting fixes over.
     */
    private static String acceptIfFencesBalanced(String corrected, String original) {
        if (fenceLineCount(corrected) % 2 == 0) return corrected;
        log.warn("[MD_CORRECT] 응답의 코드 펜스 개수가 홀수 — 섹션 교정을 건너뛰고 원본 유지");
        return original;
    }

    /** {@code protectedText} = {@code original} with every GFM table block ({@link #markTableRows})
     *  replaced by a {@code [TABLE_PLACEHOLDER_N]} marker line; {@code tables} holds each table's
     *  original text, indexed by N, for {@link #restoreTables}. */
    private record TableExtraction(String protectedText, List<String> tables) {}

    private static TableExtraction extractTables(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        boolean[] inTable = markTableRows(lines);
        List<String> tables = new ArrayList<>();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            if (!inTable[i]) {
                out.add(lines.get(i));
                i++;
                continue;
            }
            int start = i;
            while (i < lines.size() && inTable[i]) i++;
            tables.add(String.join("\n", lines.subList(start, i)));
            out.add(TABLE_PLACEHOLDER_PREFIX + (tables.size() - 1) + TABLE_PLACEHOLDER_SUFFIX);
        }
        return new TableExtraction(String.join("\n", out), tables);
    }

    /** Replaces each {@code [TABLE_PLACEHOLDER_N]} marker in {@code text} with the original table
     *  text at index N. Returns {@code null} if any expected marker is missing from {@code text} —
     *  the model dropped or mangled it, so the response can't be trusted (same discipline as the
     *  overlap-boundary marker check in {@link #cutOverlap}); the caller falls back to the
     *  untouched original section rather than guess where the table belongs. */
    private static String restoreTables(String text, List<String> tables) {
        String result = text;
        for (int i = 0; i < tables.size(); i++) {
            String placeholder = TABLE_PLACEHOLDER_PREFIX + i + TABLE_PLACEHOLDER_SUFFIX;
            if (!result.contains(placeholder)) return null;
            result = result.replace(placeholder, tables.get(i));
        }
        return result;
    }

    /**
     * Removes the corrected overlap from the LLM response by cutting at the boundary marker(s) the
     * code injected: everything up to and including {@link #SECTION_START_BOUNDARY} (the head
     * overlap) and everything from {@link #SECTION_END_BOUNDARY} on (the tail overlap). Returns
     * {@code null} if an expected marker is missing so the caller can fall back to an overlap-free
     * re-correction. Any stray marker the model may have duplicated is scrubbed from the kept span.
     */
    private static String cutOverlap(String text, boolean hasHead, boolean hasTail) {
        String kept = text;
        if (hasHead) {
            int idx = kept.indexOf(SECTION_START_BOUNDARY);
            if (idx < 0) return null;
            kept = kept.substring(idx + SECTION_START_BOUNDARY.length());
        }
        if (hasTail) {
            int idx = kept.indexOf(SECTION_END_BOUNDARY);
            if (idx < 0) return null;
            kept = kept.substring(0, idx);
        }
        return stripReservedMarkers(kept).strip();
    }

    /**
     * Builds the prompt's boundary-marker instructions for whichever overlap edge(s) are present.
     * The model is asked to keep each marker line verbatim and NOT merge text across it — code does
     * the actual cut afterward ({@link #cutOverlap}), so nothing here relies on the model omitting
     * the overlap on its own.
     */
    private String buildBoundaryNote(boolean hasHead, boolean hasTail) {
        StringBuilder note = new StringBuilder("\n[경계 마커 처리 — 매우 중요]\n");
        if (hasHead) {
            note.append("- 텍스트 중간에 `").append(SECTION_START_BOUNDARY)
                    .append("` 라는 줄이 있습니다. 그 줄 앞의 내용은 '이전 섹션의 끝부분'이 이어져 들어온 것입니다.\n");
        }
        if (hasTail) {
            note.append("- 텍스트 중간에 `").append(SECTION_END_BOUNDARY)
                    .append("` 라는 줄이 있습니다. 그 줄 뒤의 내용은 '다음 섹션의 시작부분'이 미리 들어온 것입니다.\n");
        }
        note.append("- 이 경계 마커 줄(들)은 절대 삭제·수정·번역·이동·복제하지 말고, 위치 그대로 각각 한 번씩 단독 줄로 그대로 출력하세요.\n");
        note.append("- 마커 바깥의 텍스트도 평소처럼 교정하세요. 단, 마커를 사이에 두고 양쪽 내용을 서로 합치거나 문장을 이어붙이지 마세요.\n");
        note.append("- 코드/로그가 마커를 넘어 이어지는 것으로 보이면, 마커는 그대로 둔 채 마커 양쪽의 코드를 각각 올바르게 코드 블록(```)으로 감싸세요.\n");
        note.append("- 그 외에는 마커를 포함한 교정 결과 전체를 반환하세요. RESULT 같은 다른 마커나 설명은 절대 추가하지 마세요.");
        return note.toString();
    }

    /** Strips any pre-existing occurrence of a prompt-framing marker from raw input text, in case a document already happens to contain one of these tokens verbatim. */
    private static String stripReservedMarkers(String text) {
        return text.replace("[/DOCUMENT]", "")
                .replace("[DOCUMENT]", "")
                .replace(SECTION_START_BOUNDARY, "")
                .replace(SECTION_END_BOUNDARY, "");
    }

    /**
     * First {@code maxLines} non-blank lines of {@code section} — used as the next section's
     * tail-overlap context. Blank/whitespace-only lines are skipped entirely (not counted, not
     * included): a section that opens with several empty lines shouldn't burn the line budget on
     * nothing. Package-private for unit testing.
     */
    static String leadingNonBlankLines(String section, int maxLines) {
        if (section == null || section.isBlank()) return "";
        String[] lines = section.split("\n", -1);
        List<String> picked = new ArrayList<>(maxLines);
        for (String line : lines) {
            if (picked.size() >= maxLines) break;
            if (!line.isBlank()) picked.add(line);
        }
        return String.join("\n", picked);
    }

    /**
     * Last {@code maxLines} non-blank lines of {@code section}, in original reading order — used as
     * the previous section's head-overlap context. Same blank-line skipping as
     * {@link #leadingNonBlankLines}. Package-private for unit testing.
     */
    static String trailingNonBlankLines(String section, int maxLines) {
        if (section == null || section.isBlank()) return "";
        String[] lines = section.split("\n", -1);
        List<String> picked = new ArrayList<>(maxLines);
        for (int i = lines.length - 1; i >= 0 && picked.size() < maxLines; i--) {
            if (!lines[i].isBlank()) picked.add(0, lines[i]);
        }
        return String.join("\n", picked);
    }

    private String augmentImageDescriptionsWithLocalVision(String md, Path correctedOutputPath,
                                                            BiConsumer<Integer, Integer> onImageDescribed) {
        if (md == null || md.isBlank()) return md;
        // Gate: skip entirely when no LOCAL vision provider is registered (don't scan/spawn tasks).
        try {
            llmRouter.routeProvider(TaskType.VISION, RoutingMode.LOCAL_ONLY);
        } catch (Exception e) {
            return md;
        }

        Path baseDir = correctedOutputPath != null && correctedOutputPath.getParent() != null
                ? correctedOutputPath.getParent() : null;
        Path dataDir = baseDir != null ? baseDir.getParent() : null;
        Map<String, String> descCache = new ConcurrentHashMap<>();

        // Describe all distinct images in parallel, bounded by INDEXING_MAX_LLM (same knob and
        // per-consumer Semaphore pattern as MD correction / keyword extraction / TXT structuring),
        // then the sequential replacement below only reads cache hits — no per-image LLM call is
        // made one-at-a-time on the regex loop anymore. Previously each image was described
        // strictly sequentially per file, so indexing image-analysis concurrency was effectively
        // bounded by INDEXING_MAX_FILES (files-in-parallel) rather than the LLM knob.
        prewarmImageDescriptions(md, baseDir, dataDir, descCache, onImageDescribed);

        String withMarkerDesc = injectDescriptionsForPattern(md, IMAGE_MARKER, true, baseDir, dataDir, descCache);
        return injectDescriptionsForPattern(withMarkerDesc, MD_IMAGE_LINK, false, baseDir, dataDir, descCache);
    }

    /**
     * Fills {@code descCache} for every distinct, resolvable, not-yet-described image referenced by
     * either pattern, running the Vision calls in parallel under a {@link Semaphore} sized to
     * {@code app.indexing.max-concurrent-llm-calls} (read fresh so a {@code /settings} override
     * applies on the next indexing). Best-effort: the replacement loop's own {@code computeIfAbsent}
     * is the correctness fallback, so a path missed here is still described (just synchronously).
     *
     * <p>{@code onImageDescribed}, if non-null, is called once with {@code (0, total)} as soon as
     * the image count is known, then once more per completed image — lets the caller surface
     * "이미지 분석 중 (N/M)" progress instead of leaving the last pre-correction message (e.g. "PPTX →
     * Markdown 변환 중...") stuck on screen for the whole Vision phase.
     */
    private void prewarmImageDescriptions(String md, Path baseDir, Path dataDir, Map<String, String> cache,
                                          BiConsumer<Integer, Integer> onImageDescribed) {
        Map<String, Path> toDescribe = new LinkedHashMap<>();
        collectImagePaths(md, IMAGE_MARKER, true, baseDir, dataDir, toDescribe);
        collectImagePaths(md, MD_IMAGE_LINK, false, baseDir, dataDir, toDescribe);
        if (toDescribe.isEmpty()) return;

        int total = toDescribe.size();
        if (onImageDescribed != null) onImageDescribed.accept(0, total);

        int maxConcurrent = Math.max(1, props.indexingSafe().maxConcurrentLlmCalls());
        log.debug("[MD_CORRECT] 이미지 설명 병렬 생성: {}장, maxConcurrent={}", total, maxConcurrent);
        Semaphore gate = new Semaphore(maxConcurrent);
        AtomicInteger doneCount = new AtomicInteger(0);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>(toDescribe.size());
            int seq = 0;
            for (Map.Entry<String, Path> e : toDescribe.entrySet()) {
                // seq = submission order (doc order), not completion order — the semaphore
                // (maxConcurrent slots, 180 virtual threads racing to acquire) makes completion
                // order effectively random, so a raw "이미지 분석 요청" log looks like some images
                // are skipped when they're really just still queued. Tagging each request with its
                // submission index lets that be told apart from an actual skip.
                String seqLabel = (++seq) + "/" + total;
                futures.add(CompletableFuture.runAsync(() -> {
                    gate.acquireUninterruptibly();
                    try {
                        cache.put(e.getKey(), describeImage(e.getValue(), seqLabel));
                        int done = doneCount.incrementAndGet();
                        if (onImageDescribed != null) onImageDescribed.accept(done, total);
                    } finally {
                        gate.release();
                    }
                }, exec));
            }
            futures.forEach(CompletableFuture::join);
        }
    }

    /** Collects distinct {@code key → imagePath} for images that would be described — same resolve,
     *  file-exists, and already-described-skip predicates as {@link #injectDescriptionsForPattern}. */
    private void collectImagePaths(String input, Pattern pattern, boolean imageMarker,
                                   Path baseDir, Path dataDir, Map<String, Path> out) {
        Matcher m = pattern.matcher(input);
        while (m.find()) {
            if (hasFollowingImageDescription(input, m.end())) continue;
            String pathRaw = imageMarker ? m.group(1) : m.group(2);
            Path imagePath = resolveLocalImagePath(pathRaw, baseDir, dataDir);
            if (imagePath == null || !Files.exists(imagePath) || !Files.isRegularFile(imagePath)) continue;
            out.putIfAbsent(imagePath.toAbsolutePath().normalize().toString(), imagePath);
        }
    }

    private String injectDescriptionsForPattern(String input,
                                                Pattern pattern,
                                                boolean imageMarker,
                                                Path baseDir,
                                                Path dataDir,
                                                Map<String, String> cache) {
        Matcher m = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String full = m.group(0);
            String pathRaw = imageMarker ? m.group(1) : m.group(2);
            Path imagePath = resolveLocalImagePath(pathRaw, baseDir, dataDir);

            if (hasFollowingImageDescription(input, m.end())) {
                m.appendReplacement(out, Matcher.quoteReplacement(full));
                continue;
            }

            if (imagePath == null || !Files.exists(imagePath) || !Files.isRegularFile(imagePath)) {
                m.appendReplacement(out, Matcher.quoteReplacement(full));
                continue;
            }

            String key = imagePath.toAbsolutePath().normalize().toString();
            // Cache is pre-warmed in parallel by prewarmImageDescriptions(); computeIfAbsent is the
            // correctness fallback for any path that pre-warm missed (then described synchronously).
            String desc = cache.computeIfAbsent(key, k -> describeImage(imagePath));
            if (desc == null || desc.isBlank()) {
                m.appendReplacement(out, Matcher.quoteReplacement(full));
                continue;
            }

            // FIX: inside a table row a raw newline splits the cell across two physical lines and
            // shatters the whole table — append the description with a <br> (valid inside a cell)
            // instead of a newline so the row stays on one line.
            boolean inTableRow = looksLikeTableRow(currentLine(input, m.start(), m.end()));
            String decorated;
            if (imageMarker) {
                decorated = inTableRow
                        ? full + "<br>[이미지 설명: " + desc + "]"
                        : full + "\n[이미지 설명: " + desc + "]";
            } else {
                decorated = inTableRow
                        ? full + "<br>이미지 설명: " + desc
                        : full + "\n> 이미지 설명: " + desc;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(decorated));
        }
        m.appendTail(out);
        return out.toString();
    }

    private Path resolveLocalImagePath(String raw, Path baseDir, Path dataDir) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.strip();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://") || cleaned.startsWith("data:")) {
            return null;
        }

        Path p = Path.of(cleaned);
        if (p.isAbsolute()) return p.normalize();

        if (baseDir != null) {
            Path candidate = baseDir.resolve(cleaned).normalize();
            if (Files.exists(candidate)) return candidate;
        }
        if (dataDir != null) {
            Path candidate = dataDir.resolve(cleaned).normalize();
            if (Files.exists(candidate)) return candidate;
        }
        return p.normalize();
    }

    private boolean hasFollowingImageDescription(String input, int markerEndIndex) {
        if (markerEndIndex >= input.length()) return false;
        int tailStart = markerEndIndex;
        int tailEnd = Math.min(input.length(), markerEndIndex + 160);
        String tail = input.substring(tailStart, tailEnd).stripLeading();
        return tail.startsWith("[이미지 설명:") || tail.startsWith("> 이미지 설명:");
    }

    /**
     * Describes one image with the LOCAL vision provider, routed through
     * {@link LlmRouter#executeWithTracking} so the call is recorded in {@code llm_usage} under
     * {@link BackgroundUsage#IMAGE_PREFIX} (indexing-time Vision cost, separate from chat). Any
     * failure (no provider, HTTP error, unsupported vision model) degrades to an empty string so
     * the marker is left untouched and indexing continues.
     */
    private String describeImage(Path imagePath) {
        return describeImage(imagePath, null);
    }

    private String describeImage(Path imagePath, String seqLabel) {
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            String mimeType = detectMime(imagePath.toString());
            // [LLM curl] logs (LoggingChatModel) only see the Prompt's text/Media bytes, never the
            // source file path, so without this line a DEBUG session can't tell which image a given
            // Vision request/curl log pair belongs to. seqLabel (submission order, set only from the
            // prewarm/parallel path) disambiguates a still-queued image from one that never ran —
            // with maxConcurrent << total, 180 virtual threads race for the gate and completion order
            // has no relation to this number.
            log.debug("[MD_CORRECT] 이미지 분석 요청{}: {} ({}, {} bytes)",
                    seqLabel != null ? " (" + seqLabel + ")" : "", imagePath, mimeType, bytes.length);
            Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(bytes));
            UserMessage userMessage = UserMessage.builder()
                    .text("이 이미지를 한국어 2~3문장으로 간단히 설명하세요. "
                            + "여러 선택지나 후보 설명을 나열하지 말고, 하나의 완성된 설명 문장만 작성하세요.")
                    .media(media).build();
            // 요청 자체가 "2~3문장"이라 출력 크기가 입력과 무관하게 정해져 있다.
            int cap = IndexingOutputCap.forFixed(IMAGE_DESCRIPTION_OUTPUT_RATIO, props.llmSafe().maxTokens());
            String response = llmRouter.executeWithTracking(TaskType.VISION, RoutingMode.LOCAL_ONLY,
                    BackgroundUsage.IMAGE_PREFIX, model -> model.call(new Prompt(userMessage, indexingOptions(cap))));
            return response == null ? "" : response.trim();
        } catch (LlmProviderExhaustedException e) {
            return ""; // no LOCAL vision provider (pre-gated; defensive)
        } catch (WebClientResponseException e) {
            log.warn("[MD_CORRECT] 이미지 설명 생성 실패 {}: HTTP {} body={}",
                    imagePath, e.getStatusCode().value(), compactBody(e.getResponseBodyAsString()));
            return "";
        } catch (Exception e) {
            log.debug("[MD_CORRECT] 이미지 설명 생성 실패 {}: {}", imagePath, e.getMessage());
            return "";
        }
    }

    private static String compactBody(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "...(truncated)" : oneLine;
    }

    private String detectMime(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    /**
     * Re-tags and re-indents every fenced code block. {@link #FENCED_BLOCK} pairs fences positionally
     * (1-2, 3-4, …) and the replacement <em>writes</em> {@code "```" + resolvedLang}, so a document
     * whose fence pairing is ambiguous would get an inferred language tag stamped onto a line that is
     * really a <em>closing</em> fence — the {@code ```java … ```java} corruption this pass is supposed
     * to be downstream of. Two cheap checks refuse the whole document in that case (same discipline as
     * {@code correctSection}'s table-placeholder guard — never guess):
     * <ul>
     *   <li><b>odd fence count</b> — some block is unclosed, so every pairing after it is shifted.
     *       {@link #fixClosingFences} runs first and now heals at headings/page markers/EOF, so this
     *       should be unreachable from {@link #correct}; it still guards direct/unit callers;</li>
     *   <li><b>mid-line fence</b> ({@link #fenceLineCount} ≠ {@link #fenceMarkCount}) — e.g. prose
     *       ending in {@code "다음처럼 감쌉니다: ```"} or a table cell holding a fence. The regex counts
     *       those as fences and the line-based passes do not, so the two disagree about which fence is
     *       an opener.</li>
     * </ul>
     * Skipping means untagged blocks stay untagged and {@link #normalizeCodeContent} does not run —
     * a visible but harmless degradation, unlike rewriting the document around a wrong pairing.
     *
     * <p>Package-private for unit testing (log-capture assertions on the language-tag DEBUG log).
     */
    String normalizeCodeBlocks(String md, boolean inferLanguage) {
        if (md == null || md.isEmpty()) return md;
        int fenceLines = fenceLineCount(md);
        int fenceMarks = fenceMarkCount(md);
        if (fenceLines % 2 != 0 || fenceLines != fenceMarks) {
            log.warn("[MD_CORRECT] 코드 펜스 짝을 확정할 수 없어 언어 태그·코드 정리를 건너뜀 "
                    + "(펜스 줄 {}개, 텍스트 내 ``` {}회 — 홀수이거나 줄 중간 펜스가 있음)",
                    fenceLines, fenceMarks);
            return md;
        }
        Matcher m = FENCED_BLOCK.matcher(md);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String lang = m.group(1) == null ? "" : m.group(1).trim();
            String code = m.group(2) == null ? "" : m.group(2);
            String resolvedLang = resolveCodeLanguage(lang, code, inferLanguage);
            if (!resolvedLang.equals(lang)) {
                String reason = lang.equalsIgnoreCase("sql")
                        ? "SQL로 태그됐지만 Java 신호가 강해 오분류로 판단, java로 교정"
                        : "언어 태그가 없어 코드 내용을 보고 추론";
                log.debug("[MD_CORRECT] {}행 — 코드 블록 언어 태그 '{}' → '{}' ({})",
                        lineNumberAt(md, m.start()), lang.isBlank() ? "(없음)" : lang, resolvedLang, reason);
            }
            String normalized = normalizeCodeContent(code);
            String replacement = "```" + resolvedLang + "\n" + normalized + "\n```";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 1-based line number of the character at {@code offset} within {@code text}. */
    private static int lineNumberAt(String text, int offset) {
        int line = 1;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * FIX: a CLOSING code fence must be a bare {@code ```} — the LLM sometimes echoes the language
     * tag on the closer too (opening {@code ```sql} … closing {@code ```sql}), which breaks fence
     * pairing and rendering. Toggles fence state line by line and strips any info string from every
     * closing fence (openers keep theirs). Fence content is otherwise untouched. Run BEFORE
     * {@link #normalizeCodeBlocks} so its regex sees well-formed pairs. Package-private for testing.
     *
     * <p>Also heals a fence the LLM left open entirely (no closer at all, mis-tagged or not) at three
     * points: a well-formed chapter heading ({@link #looksLikeChapterHeadingNotComment}), a
     * {@code [페이지: N]} marker, and end of input. Without this, every real opening fence later in
     * the document would be misread as a closer instead, silently stripping its language tag one
     * boundary at a time — and once that parity is off, {@link #normalizeCodeBlocks} pairs fences the
     * same wrong way and <em>writes</em> an inferred language tag onto a line that is really a closer
     * (the reported {@code ```java … ```java} corruption). The page marker matters because PPTX and
     * non-scanned PDF emit no {@code ##} headings at all (see {@link #splitBySections}), so a heading
     * is never reached in those formats and the desync would run to the end of the document.
     */
    static String fixClosingFences(String md) {
        if (md == null || md.isEmpty()) return md;
        String[] lines = md.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean inFence = false;
        String openingTag = "";
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String t = rawLine.stripLeading();
            if (t.startsWith("```")) {
                String line = rawLine;
                if (inFence) {
                    if (!t.equals("```")) {
                        String indent = rawLine.substring(0, rawLine.length() - t.length());
                        line = indent + "```";
                        log.debug("[MD_CORRECT] {}행 — 닫는 펜스 언어 태그 제거: '{}' → '```' (여는 펜스 '{}' 와 짝을 맞추기 위함)",
                                i + 1, t, openingTag);
                    }
                } else {
                    openingTag = t;
                }
                out.add(line);
                inFence = !inFence;
                continue;
            }
            if (inFence && (looksLikeChapterHeadingNotComment(rawLine) || isPageMarkerLine(rawLine))) {
                log.debug("[MD_CORRECT] {}행 — 닫히지 않은 펜스(여는 펜스 '{}') 치유: 경계 '{}' 앞에 닫는 펜스 삽입",
                        i + 1, openingTag, rawLine.strip());
                out.add("```");
                inFence = false;
            }
            out.add(rawLine);
        }
        if (inFence) {
            log.debug("[MD_CORRECT] 문서 끝 — 닫히지 않은 펜스(여는 펜스 '{}') 치유: 닫는 펜스 추가", openingTag);
            out.add("```");
        }
        return String.join("\n", out);
    }

    /**
     * One code-fence defect found by {@link #findFenceProblems}. {@code line} is 1-based;
     * {@code kind} is a stable machine token ({@code unclosed} / {@code tagged_closer} /
     * {@code mid_line}) and {@code message} the Korean text shown to the operator.
     */
    public record FenceProblem(int line, String kind, String message) {}

    /**
     * Reports every code-fence defect in {@code md} WITHOUT changing it — the read-only counterpart
     * to {@link #fixClosingFences}, used by the re-index pre-flight check (that path deliberately
     * never re-runs the rewriting passes, so the operator decides whether to proceed).
     *
     * <p>Detects the three defects that make fence pairing ambiguous downstream (see the fence-parity
     * invariant on {@link #normalizeCodeBlocks}):
     * <ul>
     *   <li>{@code tagged_closer} — a closing fence carrying an info string ({@code ```java} where a
     *       bare {@code ```} belongs), the originally reported corruption;</li>
     *   <li>{@code unclosed} — a fence still open at end of input (reported at the OPENING line, the
     *       one the operator has to go fix);</li>
     *   <li>{@code mid_line} — a {@code ```} that is not the start of its line, which the line-based
     *       passes cannot see but the anchorless {@link #FENCED_BLOCK} regex still pairs on.</li>
     * </ul>
     * Returns an empty list for null/blank input. Results are ordered by line number.
     */
    public static List<FenceProblem> findFenceProblems(String md) {
        if (md == null || md.isEmpty()) return List.of();
        List<FenceProblem> problems = new ArrayList<>();
        String[] lines = md.split("\n", -1);
        boolean inFence = false;
        int openedAtLine = 0;
        String openingTag = "";

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String t = raw.stripLeading();
            boolean isFenceLine = t.startsWith("```");

            if (isFenceLine) {
                if (inFence) {
                    if (!t.equals("```")) {
                        problems.add(new FenceProblem(i + 1, "tagged_closer",
                                "닫는 펜스에 언어 태그가 붙어 있습니다: '%s' — %d행의 '%s' 와 짝이므로 순수 ``` 여야 합니다"
                                        .formatted(t.strip(), openedAtLine, openingTag.strip())));
                    }
                } else {
                    openedAtLine = i + 1;
                    openingTag = t;
                }
                inFence = !inFence;
            }

            // A fence line legitimately holds exactly one ```; anything beyond that (or any ``` on a
            // non-fence line) is a mid-line occurrence the line-based passes are blind to.
            int extra = fenceMarkCount(raw) - (isFenceLine ? 1 : 0);
            if (extra > 0) {
                problems.add(new FenceProblem(i + 1, "mid_line",
                        "줄 중간에 ``` 이 있습니다: '%s' — 펜스는 줄 맨 앞에 있어야 합니다".formatted(abbreviate(raw))));
            }
        }

        if (inFence) {
            problems.add(new FenceProblem(openedAtLine, "unclosed",
                    "'%s' 로 열린 코드 블록이 문서 끝까지 닫히지 않았습니다".formatted(openingTag.strip())));
        }
        problems.sort(java.util.Comparator.comparingInt(FenceProblem::line));
        return List.copyOf(problems);
    }

    /** Single-line, length-capped rendering of a source line for an operator-facing message. */
    private static String abbreviate(String line) {
        String oneLine = line.strip().replaceAll("\\s+", " ");
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }

    /** A {@code [페이지: N]} slide/page boundary marker line — the only per-page boundary PPTX and
     *  non-scanned PDF emit (they produce no {@code ##} headings). Matches {@link #splitBySections}'
     *  own boundary test, so fence healing and section splitting agree on where a page starts. */
    private static boolean isPageMarkerLine(String line) {
        return line.startsWith("[페이지: ");
    }

    /**
     * Number of lines that open or close a fenced code block (leading whitespace ignored) — the
     * line-based view {@link #fixClosingFences} and {@link #postProcessMarkdown} both use.
     *
     * <p>Package-private, not private, so {@code AnswerService.truncate()} can count fences the
     * <b>same</b> way this class does (§6.24 Step 3-c). A second implementation over there would
     * be free to drift, and the whole point of the code-fence parity invariant is that every pass
     * touching fences agrees on where they are. Static, so no bean dependency is created — the
     * chat path must not pull in this indexing-path service.
     */
    static int fenceLineCount(String text) {
        int count = 0;
        for (String line : text.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) count++;
        }
        return count;
    }

    /** Total {@code ```} occurrences anywhere in {@code text}, including mid-line ones that
     *  {@link #fenceLineCount} does not see — {@link #FENCED_BLOCK} has no line anchors and pairs on
     *  these too, so a difference between the two counts means the line-based and regex-based views
     *  of "where the fences are" disagree. */
    private static int fenceMarkCount(String text) {
        int count = 0;
        for (int i = text.indexOf("```"); i >= 0; i = text.indexOf("```", i + 3)) count++;
        return count;
    }

    /**
     * True for a well-formed 2–7 level ATX-style chapter heading ({@code "## "} through
     * {@code "####### "}) — used by {@link #fixClosingFences} to spot a natural point to heal
     * a fence the LLM left open, and by {@link #postProcessMarkdown}'s Pass B to decide which lines
     * get a guaranteed blank line on both sides. Deliberately looser than
     * {@link #chapterHeadingLevel} (allows level 7, and doesn't care whether the line sits inside a
     * fence — detecting "still inside one" is the whole point in {@code fixClosingFences}; Pass B
     * adds its own {@code inFence} guard). Excludes comment-style lines whose content itself ends in a
     * trailing {@code #} run (e.g. {@code "### 주석 ###"}, {@code "### ###"}) — several languages
     * use that shape for banner comments inside code, and it is not a real heading. Package-private
     * for unit testing.
     */
    static boolean looksLikeChapterHeadingNotComment(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level++;
        if (level < 2 || level > 7) return false;
        if (level >= line.length() || line.charAt(level) != ' ') return false;
        String content = line.substring(level + 1);
        if (content.isBlank()) return false;
        return !content.stripTrailing().endsWith("#");
    }

    /** Same as {@link #postProcessMarkdown(String, boolean)} with {@code isPptx=false} — no
     *  PPTX-only shape-group formatting. Package-private for unit testing. */
    static String postProcessMarkdown(String md) {
        return postProcessMarkdown(md, false);
    }

    /**
     * Deterministic Markdown cleanup applied once to the fully-corrected document, fixing recurring
     * LLM formatting slips the per-section correction leaves behind. All rules are fence-aware — code
     * block <i>contents</i> are never modified:
     * <ul>
     *   <li>({@code isPptx} only) {@link #applyPptxShapeFormatting} — shape-group/image-anchor
     *       blank-line and dedup fixes, run first so the passes below normalize/trim whatever blank
     *       lines it inserts;</li>
     *   <li>drops leftover {@code [DOCUMENT]}/{@code [/DOCUMENT]} prompt-framing markers;</li>
     *   <li>drops content-less bullet lines (a lone {@code -});</li>
     *   <li>guarantees a blank line before and after every fenced code block and every GFM table, so
     *       a table/code block touching adjacent text still renders;</li>
     *   <li>guarantees a blank line before and after every H2–H7 heading
     *       ({@link #looksLikeChapterHeadingNotComment}), so a subheading never touches the previous
     *       paragraph/bullet or its own body text;</li>
     *   <li>collapses runs of blank lines (outside fences) to a single blank line.</li>
     * </ul>
     * Package-private for unit testing.
     */
    static String postProcessMarkdown(String md, boolean isPptx) {
        if (md == null || md.isEmpty()) return md;
        String preprocessed = isPptx ? applyPptxShapeFormatting(md) : md;

        // Pass A — fence-aware line removal (leftover [DOCUMENT] markers, content-less '-' lines).
        List<String> lines = new ArrayList<>();
        boolean inFence = false;
        for (String raw : preprocessed.split("\n", -1)) {
            if (raw.stripLeading().startsWith("```")) { lines.add(raw); inFence = !inFence; continue; }
            if (inFence) { lines.add(raw); continue; }

            String line = raw;
            if (line.contains("[DOCUMENT]") || line.contains("[/DOCUMENT]")) {
                line = line.replace("[/DOCUMENT]", "").replace("[DOCUMENT]", "");
                if (line.strip().isEmpty()) continue; // marker-only line
            }
            if (line.strip().equals("-")) continue;   // content-less bullet
            lines.add(line);
        }

        boolean[] inTable = markTableRows(lines);

        // Pass B — blank lines around fences/tables/headings + blank collapsing (fence-aware).
        List<String> out = new ArrayList<>();
        inFence = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean fence = line.stripLeading().startsWith("```");
            boolean opening = fence && !inFence;
            boolean closing = fence && inFence;
            boolean tableStart = !inFence && inTable[i] && (i == 0 || !inTable[i - 1]);
            boolean tableEnd   = !inFence && inTable[i] && (i == lines.size() - 1 || !inTable[i + 1]);
            // 소제목은 앞뒤 모두 빈 줄을 보장한다. 들여쓰기된 '##'은 목록 안 내용/코드일 수 있으므로
            // 원본 줄 그대로 판정하고(stripLeading 하지 않음), '### 주석 ###' 류 배너 주석은 제외된다.
            boolean heading = !inFence && looksLikeChapterHeadingNotComment(line);

            if (!inFence && line.isBlank()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) out.add("");
                continue;
            }
            if ((opening || tableStart || heading) && !out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
                out.add("");
            }
            out.add(line);
            if (fence) inFence = !inFence;
            if ((closing || tableEnd || heading) && i + 1 < lines.size() && !lines.get(i + 1).isBlank()) {
                out.add("");
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return String.join("\n", out);
    }

    /**
     * PPTX-only deterministic formatting fixes for {@code [도형 그룹]}/image-anchor artifacts left by
     * {@code PptxToMarkdownConverter} (and sometimes reshuffled by the per-section LLM correction
     * pass). Applied before {@link #postProcessMarkdown}'s generic Pass A/B so this pass's own
     * blank-line insertions/removals are normalized (never doubled, trailing blanks trimmed) by the
     * passes that follow. Fence-aware throughout — code block contents are never touched. Order
     * matters: {@link #ensureImageAnchorBoundaryBlankLines} must run before
     * {@link #ensureBlankBetweenConsecutiveImages}, otherwise the blank lines the latter inserts
     * between individual image lines would break the former's "contiguous image run" detection.
     * Package-private for unit testing.
     */
    static String applyPptxShapeFormatting(String md) {
        String result = md;
        result = normalizeBulletGaps(result);
        result = dedupSingleTokenLinesInShapeGroups(result);
        result = ensureImageAnchorBoundaryBlankLines(result);
        result = ensureBlankBetweenConsecutiveImages(result);
        result = ensureBlankAroundShapeGroupMarkers(result);
        return result;
    }

    /**
     * Blank line immediately before every {@code [도형 그룹]}/{@code [도형 그룹 N]} opening marker and
     * immediately after every matching closing marker — so a shape-group block never touches
     * adjacent slide content. Package-private for unit testing.
     */
    static String ensureBlankAroundShapeGroupMarkers(String md) {
        String[] lines = md.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean fence = line.stripLeading().startsWith("```");
            String trimmed = line.strip();
            boolean isOpen  = !inFence && SHAPE_GROUP_OPEN.matcher(trimmed).matches();
            boolean isClose = !inFence && SHAPE_GROUP_CLOSE.matcher(trimmed).matches();

            if (isOpen && !out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
                out.add("");
            }
            out.add(line);
            if (fence) inFence = !inFence;
            if (isClose && i + 1 < lines.length && !lines[i + 1].isBlank()) {
                out.add("");
            }
        }
        return String.join("\n", out);
    }

    /**
     * Within each {@code [도형 그룹]...[/도형 그룹]} block, the leading run of image-anchor lines
     * (one or more {@code [이미지: ...]}, each optionally followed by its own {@code [이미지 설명: ...]}
     * — always emitted right after the opening marker, before any inner text, by
     * {@code PptxToMarkdownConverter.appendShapeGroup}) gets a blank line before the run and a blank
     * line after it, separating the anchors from the marker line and from the group's inner text.
     * Must run before {@link #ensureBlankBetweenConsecutiveImages} — see
     * {@link #applyPptxShapeFormatting}. Package-private for unit testing.
     */
    static String ensureImageAnchorBoundaryBlankLines(String md) {
        List<String> lines = new ArrayList<>(List.of(md.split("\n", -1)));
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("```")) {
                out.add(line);
                inFence = !inFence;
                i++;
                continue;
            }
            if (inFence) {
                out.add(line);
                i++;
                continue;
            }

            out.add(line);
            boolean isOpen = SHAPE_GROUP_OPEN.matcher(line.strip()).matches();
            i++;
            if (!isOpen) continue;

            int runStart = i;
            while (i < lines.size() && isImageAnchorLine(lines.get(i))) i++;
            if (i > runStart) {
                out.add("");
                for (int k = runStart; k < i; k++) out.add(lines.get(k));
                if (i < lines.size() && !lines.get(i).isBlank()) out.add("");
            }
        }
        return String.join("\n", out);
    }

    private static boolean isImageAnchorLine(String line) {
        String t = line.strip();
        return IMAGE_LINE_FULL.matcher(t).matches() || IMAGE_DESC_LINE_FULL.matcher(t).matches();
    }

    /**
     * A blank line between every pair of consecutive image-anchor units (a {@code [이미지: ...]} line
     * plus its optional {@code [이미지 설명: ...]} line) that currently touch with no blank line
     * between them — anywhere in the document, not just inside shape groups. A lone image unit with
     * no neighbouring image unit is left untouched. Package-private for unit testing.
     */
    static String ensureBlankBetweenConsecutiveImages(String md) {
        List<String> lines = new ArrayList<>(List.of(md.split("\n", -1)));
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("```")) {
                out.add(line);
                inFence = !inFence;
                i++;
                continue;
            }
            if (inFence) {
                out.add(line);
                i++;
                continue;
            }

            if (IMAGE_LINE_FULL.matcher(line.strip()).matches()) {
                out.add(line);
                i++;
                if (i < lines.size() && IMAGE_DESC_LINE_FULL.matcher(lines.get(i).strip()).matches()) {
                    out.add(lines.get(i));
                    i++;
                }
                if (i < lines.size() && IMAGE_LINE_FULL.matcher(lines.get(i).strip()).matches()) {
                    out.add(""); // next unit follows immediately — separate with a blank line
                }
                continue;
            }
            out.add(line);
            i++;
        }
        return String.join("\n", out);
    }

    /**
     * Bullet-to-bullet blank-line gaps ({@code "- 내용"} lines, dash bullets only): a single blank
     * line between two consecutive bullets is almost always an LLM/converter artifact, not an
     * intentional break, so it is removed entirely; two or more blank lines are treated as a
     * deliberate separator and collapsed to exactly one. A blank-line run that is NOT followed by
     * another bullet (body text, a heading, a shape-group marker, end of document, …) is left exactly
     * as-is. Package-private for unit testing.
     */
    static String normalizeBulletGaps(String md) {
        List<String> lines = new ArrayList<>(List.of(md.split("\n", -1)));
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("```")) {
                out.add(line);
                inFence = !inFence;
                i++;
                continue;
            }
            if (inFence) {
                out.add(line);
                i++;
                continue;
            }

            out.add(line);
            i++;
            if (!isDashBullet(line)) continue;

            int blankStart = i;
            while (i < lines.size() && lines.get(i).isBlank()) i++;
            int blankCount = i - blankStart;
            if (blankCount == 0) continue;

            if (i < lines.size() && isDashBullet(lines.get(i))) {
                if (blankCount >= 2) out.add(""); // 1 blank -> removed entirely, 2+ -> collapsed to 1
            } else {
                for (int k = 0; k < blankCount; k++) out.add(""); // not bullet-to-bullet — leave as-is
            }
        }
        return String.join("\n", out);
    }

    private static boolean isDashBullet(String line) {
        String t = line.strip();
        return t.startsWith("- ") && t.length() > 2;
    }

    /**
     * Within each {@code [도형 그룹]...[/도형 그룹]} block, a line whose entire trimmed content is a
     * single token (a lone number or word, no internal whitespace) is dropped if the exact same line
     * already appeared earlier in that block — a recurring SmartArt/grouped-shape extraction artifact
     * (e.g. a step number or label repeated across overlapping text runs). Structural marker lines
     * (anything starting with {@code [}, e.g. {@code [이미지: ...]}) and any line containing {@code {}
     * or {@code }} are never deduped — the former to never risk dropping an image reference, the
     * latter per explicit requirement (e.g. template/placeholder tokens that legitimately repeat).
     * Deduplication is scoped per shape-group block, not document-wide. Package-private for unit
     * testing.
     */
    static String dedupSingleTokenLinesInShapeGroups(String md) {
        String[] lines = md.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean inFence = false;
        boolean inGroup = false;
        Set<String> seenInGroup = null;
        for (String line : lines) {
            if (line.stripLeading().startsWith("```")) {
                out.add(line);
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                out.add(line);
                continue;
            }

            String trimmed = line.strip();
            if (!inGroup && SHAPE_GROUP_OPEN.matcher(trimmed).matches()) {
                inGroup = true;
                seenInGroup = new HashSet<>();
                out.add(line);
                continue;
            }
            if (inGroup && SHAPE_GROUP_CLOSE.matcher(trimmed).matches()) {
                inGroup = false;
                seenInGroup = null;
                out.add(line);
                continue;
            }
            if (inGroup && isSingleTokenLine(trimmed) && !seenInGroup.add(trimmed)) {
                continue; // duplicate single-token line within this shape group — drop it
            }
            out.add(line);
        }
        return String.join("\n", out);
    }

    private static boolean isSingleTokenLine(String trimmed) {
        if (trimmed.isEmpty() || trimmed.startsWith("[")) return false;
        if (trimmed.contains("{") || trimmed.contains("}")) return false;
        return !trimmed.contains(" ") && !trimmed.contains("\t");
    }

    /** Marks the indices in {@code lines} that belong to a GFM table (a delimiter row plus the
     *  contiguous pipe rows around it), skipping fenced code. */
    private static boolean[] markTableRows(List<String> lines) {
        boolean[] inTable = new boolean[lines.size()];
        boolean inFence = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith("```")) { inFence = !inFence; continue; }
            if (inFence || !isTableDelimiterRow(lines.get(i))) continue;
            int start = i;
            if (i - 1 >= 0 && !inTable[i - 1] && isPipeRow(lines.get(i - 1))
                    && !lines.get(i - 1).stripLeading().startsWith("```")) {
                start = i - 1; // the header row directly above the delimiter
            }
            int end = i;
            for (int j = i + 1; j < lines.size(); j++) {
                if (lines.get(j).stripLeading().startsWith("```")
                        || lines.get(j).isBlank() || !isPipeRow(lines.get(j))) break;
                end = j;
            }
            for (int k = start; k <= end; k++) inTable[k] = true;
        }
        return inTable;
    }

    private static boolean isPipeRow(String line) {
        return line.indexOf('|') >= 0;
    }

    /** A GFM table delimiter row: only {@code | - :} and spaces, with at least one '-' and one '|'
     *  (so a thematic break {@code ---} is not mistaken for one). */
    private static boolean isTableDelimiterRow(String line) {
        String s = line.strip();
        if (s.indexOf('|') < 0 || s.indexOf('-') < 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') return false;
        }
        return true;
    }

    /** The full physical line of {@code text} spanning [{@code from}, {@code to}). */
    private static String currentLine(String text, int from, int to) {
        int start = text.lastIndexOf('\n', from) + 1;
        int end = text.indexOf('\n', to);
        if (end < 0) end = text.length();
        return text.substring(start, end);
    }

    /** Heuristic: {@code line} is a GFM table row (leading pipe, or 2+ pipes) — an image description
     *  appended here must use {@code <br>}, not a newline, or it splits the cell and breaks the table.
     *  Package-private for unit testing. */
    static boolean looksLikeTableRow(String line) {
        String s = line.strip();
        if (s.indexOf('|') < 0) return false;
        if (s.startsWith("|")) return true;
        int pipes = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '|') pipes++;
        return pipes >= 2;
    }

    private String addHierarchicalHeadingNumbers(String md) {
        String[] lines = md.split("\\n", -1);
        int[] counters = new int[5]; // ##..###### => 5 levels
        boolean inFence = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;

            int headingLevel = markdownHeadingLevel(line);
            if (headingLevel < 2 || headingLevel > 6) continue;

            String headingText = line.substring(headingLevel + 1).trim();
            if (headingText.isBlank()) continue;

            int idx = headingLevel - 2;
            counters[idx]++;
            for (int j = idx + 1; j < counters.length; j++) counters[j] = 0;

            String cleanHeading = HEADING_NUMBER_PREFIX.matcher(headingText).replaceFirst("").trim();
            String prefix = buildHeadingPrefix(counters, idx);
            lines[i] = "#".repeat(headingLevel) + " " + prefix + " " + cleanHeading;
        }

        return String.join("\n", lines);
    }

    private int markdownHeadingLevel(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') i++;
        if (i < 1 || i > 6) return 0;
        if (line.length() <= i || line.charAt(i) != ' ') return 0;
        return i;
    }

    private String buildHeadingPrefix(int[] counters, int idx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= idx; i++) {
            if (i > 0) sb.append('.');
            sb.append(Math.max(counters[i], 1));
        }
        if (idx == 0) sb.append('.');
        return sb.toString();
    }

    /**
     * Resolves the language tag for a fenced block: (1) deterministically fixes the frequent
     * "Java misdetected as SQL" case — a {@code sql} tag on code that carries a strong Java signal
     * ({@link #JAVA_CODE_SIGNAL}) and holds no real SQL statement ({@link #SQL_STATEMENT}) is
     * rewritten to {@code java}; (2) otherwise keeps an existing tag; (3) infers a tag for an
     * untagged block when {@code inferWhenBlank}. Runs in both normalize passes, so the sql→java fix
     * applies even when heading-number inference is off. Package-private for unit testing.
     */
    String resolveCodeLanguage(String existingLang, String code, boolean inferWhenBlank) {
        String lang = existingLang == null ? "" : existingLang.trim();
        if (lang.equalsIgnoreCase("sql") && looksLikeJava(code) && !looksLikeSql(code)) {
            return "java";
        }
        if (lang.isBlank() && inferWhenBlank) {
            return inferCodeLanguage(code);
        }
        return lang;
    }

    /** Package-private for unit testing. */
    String inferCodeLanguage(String code) {
        if (code == null || code.isBlank()) return "";
        String trimmed = code.trim();
        String lower = trimmed.toLowerCase();

        if (looksLikeJson(trimmed)) return "json";
        if (lower.startsWith("<?xml") || (lower.contains("<") && lower.contains("</"))) return "xml";
        if (lower.contains("<html") || lower.contains("</html>")) return "html";
        // Java is checked before yaml/sql: its signals are JVM-exclusive, so this can't steal a real
        // yaml/sql block, but it does stop a Java method call (.select/.delete/…) from falling through
        // to the SQL rule below (the reported "Java misdetected as SQL" bug).
        if (looksLikeJava(trimmed)) return "java";
        if (lower.startsWith("---") || lower.matches("(?s).*^\\s*[a-zA-Z0-9_.-]+:\\s+.+$.*")) return "yaml";
        if (looksLikeSql(trimmed)) return "sql";
        if (lower.startsWith("#!/bin/sh")) return "sh";
        if (lower.startsWith("#!/bin/bash") || lower.startsWith("#!/usr/bin/env bash")
                || lower.contains(" apt-get ") || lower.contains(" curl ") || lower.contains(" grep ")) return "bash";
        if (lower.matches("(?s).*\\b(def|class|import|from)\\b.*") && lower.contains(":")) return "python";
        if (lower.matches("(?s).*\\b(function|const|let|var|console\\.log|=>)\\b.*")) return "javascript";
        return "";
    }

    /** True when {@code code} carries a strong, JVM-exclusive Java signal ({@link #JAVA_CODE_SIGNAL}). */
    private static boolean looksLikeJava(String code) {
        return code != null && JAVA_CODE_SIGNAL.matcher(code).find();
    }

    /** True when {@code code} contains a real SQL statement ({@link #SQL_STATEMENT}) — not merely a
     *  word like "delete"/"select" that a Java method call would also contain. */
    private static boolean looksLikeSql(String code) {
        return code != null && SQL_STATEMENT.matcher(code).find();
    }

    private boolean looksLikeJson(String code) {
        String t = code.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) return false;
        return t.contains(":") && (t.endsWith("}") || t.endsWith("]"));
    }

    /**
     * Collapses blank lines inside a code block to zero, except a single blank line is kept
     * immediately before (a) the start of a multi-line comment (block-comment/docstring opener, or
     * the first of two-or-more consecutive line comments), or (b) a function/class/method signature
     * that isn't already preceded by a comment. No blank line is ever inserted at the very start of
     * the block (leading blanks stay trimmed).
     */
    /** Package-private for unit testing. */
    String normalizeCodeContent(String code) {
        String[] lines = code.split("\\n", -1);
        List<String> cleaned = new ArrayList<>(lines.length);
        String lastEmitted = null;
        int i = 0;
        while (i < lines.length) {
            String trimmedRight = lines[i].replaceAll("[ \\t]+$", "");
            if (!trimmedRight.isBlank()) {
                cleaned.add(trimmedRight);
                lastEmitted = trimmedRight.strip();
                i++;
                continue;
            }
            int j = i;
            while (j < lines.length && lines[j].isBlank()) j++;
            if (j < lines.length && !cleaned.isEmpty()) {
                String next = lines[j].strip();
                boolean keepBlank = startsMultiLineComment(lines, j)
                        || (looksLikeFunctionStart(next) && !isCommentLine(lastEmitted));
                if (keepBlank) cleaned.add("");
            }
            i = j;
        }
        while (!cleaned.isEmpty() && cleaned.get(0).isBlank()) cleaned.remove(0);
        while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).isBlank()) cleaned.remove(cleaned.size() - 1);
        return String.join("\n", cleaned);
    }

    private boolean startsMultiLineComment(String[] lines, int idx) {
        String line = lines[idx].strip();
        if (BLOCK_COMMENT_OPEN.matcher(line).find()) return true;
        Matcher marker = LINE_COMMENT_MARKER.matcher(line);
        if (!marker.find()) return false;
        int next = idx + 1;
        return next < lines.length && lines[next].strip().startsWith(marker.group(1));
    }

    private boolean looksLikeFunctionStart(String line) {
        return FUNCTION_START.matcher(line).find();
    }

    private boolean isCommentLine(String line) {
        return line != null && COMMENT_LINE.matcher(line).find();
    }
}
