package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
@Service
public class MarkdownCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownCorrectionService.class);
    private static final int MIN_SECTION_CHARS = 500;
    private static final Pattern MD_IMAGE_LINK = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
    private static final Pattern IMAGE_MARKER = Pattern.compile("\\[이미지:\\s*([^\\]]+)]");
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

    private final LlmRouter llmRouter;
    private final AppProperties props;
    private final int maxSectionChars;
    private final String defaultCodeLanguage;

    // Single source of truth for "LLM max tokens" (app.llm.max-tokens / LLM_MAX_TOKENS, default
    // 6000) — used to read the separate, dead spring.ai.openai.chat.options.max-tokens property
    // (default 8000), which config'd nothing (Spring AI's autoconfigured ChatModel bean is skipped
    // since LlmConfig.primaryChatModel() already satisfies its @ConditionalOnMissingBean).
    public MarkdownCorrectionService(LlmRouter llmRouter, AppProperties props) {
        this.llmRouter = llmRouter;
        this.props = props;
        int llmMaxTokens = props.llmSafe().maxTokens();
        this.maxSectionChars = Math.max(MIN_SECTION_CHARS, (llmMaxTokens - MIN_SECTION_CHARS) / 2);
        this.defaultCodeLanguage = props.mdCorrectionDefaultCodeLanguageSafe();
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
        log.debug("[MD_CORRECT] 설정: maxConcurrent={}, maxSectionChars={}", maxConcurrent, maxSectionChars);
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
        result = normalizeCodeBlocks(result, false);
        if (addHeadingNumbers) {
            result = secondPassHeadingAndCodePolish(result);
        }
        // FIX: deterministic final cleanup — blank lines around code blocks/tables, drop leftover
        // [DOCUMENT] markers and content-less '-' lines (all fence-aware). Runs last.
        result = postProcessMarkdown(result);
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

    private String secondPassHeadingAndCodePolish(String md) {
        String numbered = addHierarchicalHeadingNumbers(md);
        return normalizeCodeBlocks(numbered, true);
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
     */
    public String postProcess(String md) {
        return postProcessMarkdown(md);
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
        return splitByBoundary(md,
                line -> line.startsWith("## ") || line.startsWith("### ") || line.startsWith("#### ")
                        || line.startsWith("[페이지: "),
                true);
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
            if (page.length() > maxSectionChars) {
                if (bundle.length() > 0) { sections.add(bundle.toString()); bundle.setLength(0); bundlePages = 0; }
                sections.addAll(splitOversizedPage(page));
                continue;
            }
            if (bundle.length() > 0
                    && (bundlePages >= PPTX_MAX_BUNDLE_PAGES
                        || bundle.length() + page.length() > maxSectionChars)) {
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

            if (enforceSize && current.length() > maxSectionChars) {
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

        String boundaryNote = hasOverlap ? buildBoundaryNote(hasHead, hasTail) : "";

        String prompt = ("""
                당신은 문서 편집자입니다. 다음 마크다운 텍스트의 형식(포맷)만 교정하세요.
                - 내용(사실, 데이터, 수치, 의미)을 변경 절대 금지

                교정 항목:
                - 잘린 문장 연결 (줄바꿈으로 끊긴 문장을 이어붙이기)
                - 명백한 오타 수정
                - 표(table)는 변경 금지
                - 마커 형식 유지: [이미지: ...], [이미지(변환불가): ...], [헤딩페이지: N], [페이지: N]을 그대로 둘 것
                - 코드/로그/명령어(CLI)/설정 파일처럼 보이지만 코드 블록(```)으로 감싸이지 않은 부분은 반드시 코드 블록으로 감싸세요. 언어를 알 수 있으면 그 언어 태그를, 판단이 어려우면 `%s` 태그를 사용하세요.
                - 코드/로그 안에서 "#", "##", "###"으로 시작하는 줄은 마크다운 제목이 아니라 코드 내용(주석·배너·출력)입니다. 반드시 코드 블록 안에 두고 제목으로 바꾸지 마세요.
                - 이미 코드 블록(```)으로 감싸인 내용은 그대로 유지: 언어 태그가 이미 있으면 유지 (코드 블록 안의 로그·일반 텍스트 출력은 그대로 유지)
                - 코드 블록(```) 내부의 불필요한 공란을 줄이고, 빈 줄 최소화로 가독성을 높일 것 (의미는 변경 금지)
                - 연속된 빈 줄 1개로 정리
                - 응답에 [DOCUMENT], [/DOCUMENT] 같은 구분자를 절대 포함하지 말 것%s

                교정된 마크다운만 반환하세요. 설명이나 주석을 추가하지 마세요.

                [DOCUMENT]
                %s
                [/DOCUMENT]""").formatted(defaultCodeLanguage, boundaryNote, body);
        try {
            String result = llmRouter.executeWithTracking(
                    TaskType.LIGHT_TEXT, RoutingMode.COST_FIRST, BackgroundUsage.MDCORRECT_PREFIX,
                    model -> model.call(new Prompt(prompt)));
            log.debug("[MD_CORRECT] 섹션 교정 완료: {}자 → {}자", safeSection.length(), result.length());
            if (hasOverlap) {
                String cut = cutOverlap(result, hasHead, hasTail);
                if (cut != null) {
                    return cut;
                }
                // A marker the code injected is gone from the response → we can't tell where the
                // overlap ends, so re-correct with no overlap at all rather than risk keeping it.
                log.debug("[MD_CORRECT] 경계 마커 누락 — 오버랩 없이 재교정");
                return correctSection(section, "", "");
            }
            return result;
        } catch (LlmProviderExhaustedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[MD_CORRECT] 섹션 교정 실패, 원본 유지: {}", e.getMessage());
            return section;
        }
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
            for (Map.Entry<String, Path> e : toDescribe.entrySet()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    gate.acquireUninterruptibly();
                    try {
                        cache.put(e.getKey(), describeImage(e.getValue()));
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
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            String mimeType = detectMime(imagePath.toString());
            Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(bytes));
            UserMessage userMessage = UserMessage.builder()
                    .text("이 이미지를 한국어 1~2문장으로 간단히 설명하세요.")
                    .media(media).build();
            String response = llmRouter.executeWithTracking(TaskType.VISION, RoutingMode.LOCAL_ONLY,
                    BackgroundUsage.IMAGE_PREFIX, model -> model.call(new Prompt(userMessage)));
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

    /** Package-private for unit testing (log-capture assertions on the language-tag DEBUG log). */
    String normalizeCodeBlocks(String md, boolean inferLanguage) {
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
     * <p>Also heals a fence the LLM left open entirely (no closer at all, mis-tagged or not): if a
     * well-formed chapter heading ({@link #looksLikeChapterHeadingNotComment}) is reached while
     * {@code inFence} is still true, a synthetic {@code ```} is inserted right before it. Without
     * this, every real opening fence later in the document would be misread as a closer instead,
     * silently stripping its language tag one boundary at a time.
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
            if (inFence && looksLikeChapterHeadingNotComment(rawLine)) {
                log.debug("[MD_CORRECT] {}행 — 닫히지 않은 펜스(여는 펜스 '{}') 치유: 챕터 제목 '{}' 앞에 닫는 펜스 삽입",
                        i + 1, openingTag, rawLine.strip());
                out.add("```");
                inFence = false;
            }
            out.add(rawLine);
        }
        return String.join("\n", out);
    }

    /**
     * True for a well-formed 2–7 level ATX-style chapter heading ({@code "## "} through
     * {@code "####### "}) — used only by {@link #fixClosingFences} to spot a natural point to heal
     * a fence the LLM left open. Deliberately looser than {@link #chapterHeadingLevel} (allows
     * level 7, and doesn't care whether the line sits inside a fence — detecting "still inside one"
     * is the whole point here). Excludes comment-style lines whose content itself ends in a
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

    /**
     * Deterministic Markdown cleanup applied once to the fully-corrected document, fixing recurring
     * LLM formatting slips the per-section correction leaves behind. All rules are fence-aware — code
     * block <i>contents</i> are never modified:
     * <ul>
     *   <li>drops leftover {@code [DOCUMENT]}/{@code [/DOCUMENT]} prompt-framing markers;</li>
     *   <li>drops content-less bullet lines (a lone {@code -});</li>
     *   <li>guarantees a blank line before and after every fenced code block and every GFM table, so
     *       a table/code block touching adjacent text still renders;</li>
     *   <li>collapses runs of blank lines (outside fences) to a single blank line.</li>
     * </ul>
     * Package-private for unit testing.
     */
    static String postProcessMarkdown(String md) {
        if (md == null || md.isEmpty()) return md;

        // Pass A — fence-aware line removal (leftover [DOCUMENT] markers, content-less '-' lines).
        List<String> lines = new ArrayList<>();
        boolean inFence = false;
        for (String raw : md.split("\n", -1)) {
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

        // Pass B — blank lines around fences/tables + blank collapsing (fence-aware).
        List<String> out = new ArrayList<>();
        inFence = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean fence = line.stripLeading().startsWith("```");
            boolean opening = fence && !inFence;
            boolean closing = fence && inFence;
            boolean tableStart = !inFence && inTable[i] && (i == 0 || !inTable[i - 1]);
            boolean tableEnd   = !inFence && inTable[i] && (i == lines.size() - 1 || !inTable[i + 1]);

            if (!inFence && line.isBlank()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) out.add("");
                continue;
            }
            if ((opening || tableStart) && !out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
                out.add("");
            }
            out.add(line);
            if (fence) inFence = !inFence;
            if ((closing || tableEnd) && i + 1 < lines.size() && !lines.get(i + 1).isBlank()) {
                out.add("");
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return String.join("\n", out);
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
