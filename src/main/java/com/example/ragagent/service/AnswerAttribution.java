package com.example.ragagent.service;

import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.model.SourceRef;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estimates how much of a finished answer came from each retrieved chunk (2단계 응답 참여도).
 *
 * <p><b>This is an estimate, not a measurement.</b> The only causal way to know a chunk's
 * contribution is to regenerate the answer without it (leave-one-out), which costs one LLM call
 * per chunk and belongs in the offline evaluation harness. What this class does instead is assign
 * each answer sentence to the chunk it most resembles, then report the share of answer text that
 * landed on each chunk. Two consequences follow and both are deliberate:
 *
 * <ul>
 *   <li>Shares always sum to 1.0 across the chunks that received anything — the number answers
 *       "of the answer text, how much looks like it came from here", not "how important was this".</li>
 *   <li>A sentence the model wrote from its own phrasing (a transition, a summary line) matches
 *       nothing and is simply excluded from the denominator rather than being forced onto the
 *       nearest chunk.</li>
 * </ul>
 *
 * <p>Matching is character n-gram based because the corpus is Korean: whitespace tokenization
 * splits 조사/어미 wrongly ("포트를" vs "포트는" share no token), while a 4-gram window slides over
 * the stem and matches regardless of the particle attached to it.
 *
 * <p>Every n-gram is weighted by <b>rarity across the retrieved set</b> ({@code 1/documentFrequency}).
 * Without this, chunks from the same document dominate purely by sharing boilerplate — headings,
 * product names, the context breadcrumb — and the shares collapse toward "whichever chunk is
 * longest". A 4-gram present in every chunk carries no attribution information and contributes
 * almost nothing; one present in a single chunk is what actually identifies a source.
 *
 * <p>No Spring dependencies (mirrors {@code ChunkReassembler}/{@code MarkdownNoiseNormalizer}) so
 * the whole contract is unit-testable without a context.
 */
public final class AnswerAttribution {

    private AnswerAttribution() {}

    /** n-gram width. 4 chars ≈ a Korean stem plus one syllable of inflection. */
    static final int NGRAM = 4;

    /**
     * A sentence must reach this share of its own n-grams as rare matches before it is credited to
     * a chunk. Guards against a single incidental 4-gram ("있습니다" 계열) deciding attribution for
     * an otherwise unrelated sentence.
     */
    static final double MIN_SENTENCE_MATCH = 0.10;

    /** Sentences shorter than this are structural noise (headings, list bullets, "예시:"). */
    static final int MIN_SENTENCE_CHARS = 10;

    /** How the shares were derived — surfaced to the UI so a reader can weigh them. */
    public enum Method {
        /** Lexical assignment restricted to the documents the evaluator said it used. */
        CITATION_LEXICAL,
        /** Lexical assignment over every retrieved document (no usable citation list). */
        LEXICAL,
        /** Nothing matched — no share is reported at all. */
        NONE;

        public String wireValue() {
            return switch (this) {
                case CITATION_LEXICAL -> "citation+lexical";
                case LEXICAL -> "lexical";
                case NONE -> "none";
            };
        }
    }

    /** Per-chunk share keyed by {@link Document#getId()}, plus how it was derived. */
    public record Result(Map<String, Double> sharesByChunkId, Method method) {
        public static Result none() { return new Result(Map.of(), Method.NONE); }
    }

    /**
     * @param answer      the final answer text as shown to the user
     * @param docs        the chunks the answer prompt carried, in prompt order
     * @param usedDocIndices 1-based indices the evaluator reported as actually used; null/empty
     *                    means no citation signal is available and every doc stays a candidate.
     *                    Out-of-range values are ignored rather than throwing — it is model output.
     */
    public static Result compute(String answer, List<Document> docs, List<Integer> usedDocIndices) {
        if (answer == null || answer.isBlank() || docs == null || docs.isEmpty()) {
            return Result.none();
        }
        Set<Integer> cited = normalizeCitations(usedDocIndices, docs.size());
        // Candidate restriction is the ONLY thing the citation list does. It never invents a share:
        // if the model cites a document whose text the answer does not resemble, that document
        // still gets nothing.
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            if (cited.isEmpty() || cited.contains(i + 1)) candidates.add(i);
        }
        if (candidates.isEmpty()) return Result.none();

        List<Set<String>> docGrams = new ArrayList<>(candidates.size());
        for (int idx : candidates) {
            docGrams.add(ngrams(normalize(docs.get(idx).getText())));
        }
        Map<String, Integer> docFreq = new HashMap<>();
        for (Set<String> grams : docGrams) {
            for (String g : grams) docFreq.merge(g, 1, Integer::sum);
        }

        Map<Integer, Double> charsByCandidate = new LinkedHashMap<>();
        double assignedChars = 0.0;
        for (String sentence : splitSentences(answer)) {
            String normalized = normalize(sentence);
            if (normalized.length() < MIN_SENTENCE_CHARS) continue;
            Set<String> sentenceGrams = ngrams(normalized);
            if (sentenceGrams.isEmpty()) continue;

            int best = -1;
            double bestScore = 0.0;
            double matchedGrams = 0.0;
            for (int c = 0; c < candidates.size(); c++) {
                Set<String> grams = docGrams.get(c);
                double score = 0.0;
                double hits = 0.0;
                for (String g : sentenceGrams) {
                    if (grams.contains(g)) {
                        score += 1.0 / docFreq.getOrDefault(g, 1);   // rarity weighting
                        hits++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                    matchedGrams = hits;
                }
            }
            if (best < 0 || matchedGrams / sentenceGrams.size() < MIN_SENTENCE_MATCH) continue;

            double weight = normalized.length();   // longer sentences carry more of the answer
            charsByCandidate.merge(candidates.get(best), weight, Double::sum);
            assignedChars += weight;
        }
        if (assignedChars <= 0.0) return Result.none();

        Map<String, Double> shares = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> e : charsByCandidate.entrySet()) {
            String id = docs.get(e.getKey()).getId();
            if (id == null) continue;
            shares.merge(id, e.getValue() / assignedChars, Double::sum);
        }
        if (shares.isEmpty()) return Result.none();
        return new Result(shares, cited.isEmpty() ? Method.LEXICAL : Method.CITATION_LEXICAL);
    }

    /** Applies computed shares onto the turn's sources, matched by {@code chunkId}. */
    public static List<SourceRef> applyTo(List<SourceRef> sources, Result result) {
        if (sources == null || sources.isEmpty() || result.method() == Method.NONE) return sources;
        List<SourceRef> out = new ArrayList<>(sources.size());
        for (SourceRef s : sources) {
            Double share = s.chunkId() == null ? null : result.sharesByChunkId().get(s.chunkId());
            out.add(new SourceRef(s.label(), s.preview(), s.chunkId(), s.docId(), s.pageOrSlide(),
                    s.similarity(), s.retrievalShare(), s.axisRanks(), share));
        }
        return List.copyOf(out);
    }

    private static Set<Integer> normalizeCitations(List<Integer> raw, int docCount) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<Integer> out = new HashSet<>();
        for (Integer i : raw) {
            if (i != null && i >= 1 && i <= docCount) out.add(i);
        }
        return out;
    }

    /**
     * Same normalization the answer prompt applies, so the comparison is not thrown off by
     * decorative markdown that exists on one side only (the prompt carries stripped text, the
     * stored chunk keeps the raw form).
     */
    private static String normalize(String text) {
        return MarkdownNoiseNormalizer.normalize(text == null ? "" : text)
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Sentence split on terminators and newlines. Markdown heading lines are dropped: they are
     * the answer's fixed skeleton (`## 요약` etc.), identical on every turn, and matching them
     * against chunk text credits whichever chunk happens to contain the same word.
     */
    static List<String> splitSentences(String answer) {
        List<String> out = new ArrayList<>();
        for (String line : answer.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            for (String piece : trimmed.split("(?<=[.!?。])\\s+")) {
                if (!piece.isBlank()) out.add(piece);
            }
        }
        return out;
    }

    static Set<String> ngrams(String text) {
        Set<String> out = new HashSet<>();
        String compact = text.replace(" ", "");
        for (int i = 0; i + NGRAM <= compact.length(); i++) {
            out.add(compact.substring(i, i + NGRAM));
        }
        return out;
    }
}
