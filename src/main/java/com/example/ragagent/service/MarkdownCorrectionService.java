package com.example.ragagent.service;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * LLM-based Markdown format correction.
 * Splits raw MD by H2/H3 headings, corrects each section in parallel (format only,
 * never changes content), then reassembles. Saves corrected file alongside the raw one.
 */
@Service
public class MarkdownCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownCorrectionService.class);
    private static final int MAX_CONCURRENT   = 3;
    private static final int MAX_SECTION_CHARS = 6_000;

    private final LlmRouter llmRouter;

    public MarkdownCorrectionService(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    /**
     * Corrects the formatting of {@code rawMd} section by section using the LLM.
     * Saves the corrected result to {@code correctedOutputPath} and returns it.
     * On any LLM failure the original section text is kept (graceful fallback).
     */
    public String correct(String rawMd, String docId, Path correctedOutputPath) {
        return correct(rawMd, docId, correctedOutputPath, null);
    }

    /**
     * Same as {@link #correct(String, String, Path)} but calls {@code onSectionDone(done, total)}
     * after each section completes — useful for streaming progress to the UI.
     */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          BiConsumer<Integer, Integer> onSectionDone) {
        if (rawMd == null || rawMd.isBlank()) return rawMd;
        log.info("[MD_CORRECT] 시작: docId={}, chars={}", docId, rawMd.length());
        long t0 = System.currentTimeMillis();

        List<String> sections = splitBySections(rawMd);
        log.debug("[MD_CORRECT] 섹션 {}개 분할 완료", sections.size());
        int total = sections.size();

        Semaphore gate = new Semaphore(MAX_CONCURRENT);
        AtomicInteger doneCount = new AtomicInteger(0);
        List<String> corrected;
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            corrected = sections.stream()
                .map(sec -> CompletableFuture.supplyAsync(() -> {
                    gate.acquireUninterruptibly();
                    try {
                        String result = correctSection(sec);
                        int done = doneCount.incrementAndGet();
                        if (onSectionDone != null) onSectionDone.accept(done, total);
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
                log.info("[MD_CORRECT] LLM 사용 불가, 원본 유지: docId={}", docId);
                return rawMd;
            }
            throw ce;
        }

        String result = String.join("\n\n", corrected);
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

    private List<String> splitBySections(String md) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : md.split("\n", -1)) {
            boolean isHeading = line.startsWith("## ") || line.startsWith("### ");
            if (isHeading && !current.isEmpty()) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
            // Flush oversized sections so they don't exceed the LLM context window
            if (current.length() > MAX_SECTION_CHARS) {
                sections.add(current.toString());
                current = new StringBuilder();
            }
        }
        if (!current.isEmpty()) sections.add(current.toString());
        return sections;
    }

    private String correctSection(String section) {
        if (section == null || section.isBlank()) return section;
        String safeSection = section.replace("[/DOCUMENT]", "");
        log.debug("[MD_CORRECT] 섹션 교정 시작: {}자", safeSection.length());
        String prompt = """
                당신은 문서 편집자입니다. 다음 마크다운 텍스트의 형식(포맷)만 교정하세요.
                절대로 내용(사실, 데이터, 수치, 의미)을 변경하지 마세요.

                교정 항목:
                - 잘린 문장 연결 (줄바꿈으로 끊긴 문장을 이어붙이기)
                - 명백한 오타 수정
                - 소제목 레벨 정규화 (H2/H3 일관성)
                - 이미지 마커 형식 유지: [이미지: 설명] 그대로 둘 것 (내용/경로 변경 금지)
                - 연속된 빈 줄 1개로 정리

                교정된 마크다운만 반환하세요. 설명이나 주석을 추가하지 마세요.

                [DOCUMENT]
                %s
                [/DOCUMENT]""".formatted(safeSection);
        try {
            String result = llmRouter.executeWithTracking(
                    TaskType.LIGHT_TEXT, RoutingMode.COST_FIRST,
                    model -> model.call(new Prompt(prompt)));
            log.debug("[MD_CORRECT] 섹션 교정 완료: {}자 → {}자", safeSection.length(), result.length());
            return result;
        } catch (LlmProviderExhaustedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[MD_CORRECT] 섹션 교정 실패, 원본 유지: {}", e.getMessage());
            return section;
        }
    }
}
