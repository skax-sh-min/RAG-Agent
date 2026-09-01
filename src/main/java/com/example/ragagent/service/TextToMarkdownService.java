package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import com.example.ragagent.llm.IndexingOutputCap;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * LLM-based plain-text → structured Markdown conversion for {@code .txt} uploads.
 *
 * <p>Plain text carries no structure, so — unlike DOCX (which the converter turns into MD
 * mechanically) — a {@code .txt} file is restructured by the LLM: logical headings/lists are
 * imposed and grammar/typos are corrected, while facts/data/meaning are never added, removed,
 * or altered. Large files are split into size-capped blocks processed in parallel, then joined.
 *
 * <p>The result feeds the same pipeline DOCX uses ({@code correct()} → {@code loadFromMarkdown()}),
 * so TXT gains section-based chunking and MD re-indexing. Degrades gracefully: on any LLM
 * unavailability the original text is kept so indexing never fails on the structuring step.
 */
@Service
public class TextToMarkdownService {

    private static final Logger log = LoggerFactory.getLogger(TextToMarkdownService.class);
    private static final int MAX_BLOCK_CHARS = 6_000;

    private final LlmRouter llmRouter;
    private final AppProperties props;

    public TextToMarkdownService(LlmRouter llmRouter, AppProperties props) {
        this.llmRouter = llmRouter;
        this.props = props;
    }

    /** Indexing/background temperature (hot-editable), read fresh per call — see AppProperties.LlmConfig. */
    /** 온도 + 출력 상한 — 상한을 비우면 {@code max-tokens} 전체가 예약된다({@link IndexingOutputCap}). */
    private OpenAiChatOptions indexingOptions(int maxTokens) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
                .temperature(props.llmSafe().indexingTemperature());
        if (maxTokens > 0) b.maxTokens(maxTokens);   // 0 = 프로바이더 기본값 유지
        return b.build();
    }

    /** {@link #convert(String, String, BiConsumer)} without progress callback. */
    public String convert(String plainText, String docId) {
        return convert(plainText, docId, null);
    }

    /**
     * Converts {@code plainText} into structured Markdown block by block, invoking
     * {@code onBlockDone(done, total)} after each block. On LLM exhaustion the whole original
     * text is returned; on a per-block error that block's original text is kept.
     */
    public String convert(String plainText, String docId, BiConsumer<Integer, Integer> onBlockDone) {
        if (plainText == null || plainText.isBlank()) return plainText;
        log.info("[TXT2MD] 시작: docId={}, chars={}", docId, plainText.length());
        long t0 = System.currentTimeMillis();

        List<String> blocks = splitIntoBlocks(plainText);
        int total = blocks.size();
        log.debug("[TXT2MD] 블록 {}개 분할 완료", total);

        // Hot-editable (indexing family) — read fresh per conversion so a /settings override applies
        // on the next indexing without a restart, same as DocumentIndexer's keyword gate,
        // MarkdownCorrectionService.correct() and LazyVisionService. Never cache this in a field.
        int maxConcurrent = Math.max(1, props.indexingSafe().maxConcurrentLlmCalls());
        log.debug("[TXT2MD] 설정: maxConcurrent={}", maxConcurrent);

        Semaphore gate = new Semaphore(maxConcurrent);
        AtomicInteger doneCount = new AtomicInteger(0);
        List<String> structured;
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            structured = blocks.stream()
                    .map(block -> CompletableFuture.supplyAsync(() -> {
                        gate.acquireUninterruptibly();
                        try {
                            String result = structureBlock(block);
                            int done = doneCount.incrementAndGet();
                            if (onBlockDone != null) onBlockDone.accept(done, total);
                            return result;
                        } finally {
                            gate.release();
                        }
                    }, exec))
                    .toList()
                    .stream()
                    .map(CompletableFuture::join)
                    .toList();
        } catch (CompletionException ce) {
            if (ce.getCause() instanceof LlmProviderExhaustedException) {
                log.info("[TXT2MD] LLM 사용 불가, 원본 텍스트 유지: docId={}", docId);
                return plainText;
            }
            throw ce;
        }

        String result = String.join("\n\n", structured);
        log.info("[TXT2MD] 완료: docId={}, {}자 → {}자, {}ms",
                docId, plainText.length(), result.length(), System.currentTimeMillis() - t0);
        return result;
    }

    /**
     * Splits plain text into blocks no larger than {@link #MAX_BLOCK_CHARS}, preferring to break
     * at blank lines so paragraphs stay intact for coherent structuring.
     */
    private List<String> splitIntoBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            // Flush at a paragraph boundary once the block is already sizeable.
            if (line.isBlank() && current.length() > MAX_BLOCK_CHARS / 2) {
                blocks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
            // Hard cap so a single huge paragraph cannot overflow the LLM context window.
            if (current.length() > MAX_BLOCK_CHARS) {
                blocks.add(current.toString());
                current = new StringBuilder();
            }
        }
        if (!current.isEmpty()) blocks.add(current.toString());
        return blocks.isEmpty() ? List.of(text) : blocks;
    }

    private String structureBlock(String block) {
        if (block == null || block.isBlank()) return block;
        String safeBlock = block.replace("[/DOCUMENT]", "");
        String prompt = """
                당신은 문서 편집자입니다. 다음 일반 텍스트를 구조화된 마크다운으로 변환하세요.
                절대로 내용(사실, 데이터, 수치, 의미)을 추가·삭제·변경하지 마세요.

                변환 규칙:
                - 논리적 흐름에 따라 적절한 소제목(##, ###)을 부여
                - 나열 항목은 목록(-, 1.)으로 정리
                - 표 형태의 데이터는 마크다운 표로 변환
                - 맞춤법·오타·띄어쓰기·줄바꿈으로 끊긴 문장을 자연스럽게 교정
                - 이미지 마커([이미지: ...])나 링크를 새로 만들지 말 것
                - 원문에 없는 제목/문장/설명을 지어내지 말 것

                변환된 마크다운만 반환하세요. 설명이나 주석을 추가하지 마세요.

                [DOCUMENT]
                %s
                [/DOCUMENT]""".formatted(safeBlock);
        try {
            String result = llmRouter.executeWithTracking(
                    TaskType.LIGHT_TEXT, RoutingMode.COST_FIRST, BackgroundUsage.TXT2MD_PREFIX,
                    model -> model.call(new Prompt(prompt, indexingOptions(
                            // 구조화도 재작성이라 출력이 이 블록 크기에 묶인다.
                            IndexingOutputCap.forRewrite(safeBlock, props.llmSafe().maxTokens())))));
            log.debug("[TXT2MD] 블록 구조화 완료: {}자 → {}자", safeBlock.length(), result.length());
            return result;
        } catch (LlmProviderExhaustedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[TXT2MD] 블록 구조화 실패, 원본 유지: {}", e.getMessage());
            return block;
        }
    }
}
