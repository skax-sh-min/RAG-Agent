package com.example.ragagent.service;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.springframework.ai.chat.client.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern MD_IMAGE_LINK = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
    private static final Pattern IMAGE_MARKER = Pattern.compile("\\[이미지:\\s*([^\\]]+)]");
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```(.*?)\\n(.*?)\\n```");
    private static final Pattern HEADING_NUMBER_PREFIX = Pattern.compile("^(?:\\d+(?:\\.\\d+)*(?:\\.)?|\\d+[\\)])\\s+");

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
        return correct(rawMd, docId, correctedOutputPath, false, false, null);
    }

    /** Same as {@link #correct(String, String, Path)} with image-description toggle. */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          boolean addImageDescriptions) {
        return correct(rawMd, docId, correctedOutputPath, addImageDescriptions, false, null);
    }

    /** Same as {@link #correct(String, String, Path, boolean)} with heading-number second pass. */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          boolean addImageDescriptions,
                          boolean addHeadingNumbers) {
        return correct(rawMd, docId, correctedOutputPath, addImageDescriptions, addHeadingNumbers, null);
    }

    /**
     * Same as {@link #correct(String, String, Path)} but calls {@code onSectionDone(done, total)}
     * after each section completes — useful for streaming progress to the UI.
     */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          BiConsumer<Integer, Integer> onSectionDone) {
        return correct(rawMd, docId, correctedOutputPath, false, false, onSectionDone);
    }

    /**
     * Same as {@link #correct(String, String, Path, boolean)} with section progress callback.
     */
    public String correct(String rawMd, String docId, Path correctedOutputPath,
                          boolean addImageDescriptions,
                          boolean addHeadingNumbers,
                          BiConsumer<Integer, Integer> onSectionDone) {
        if (rawMd == null || rawMd.isBlank()) return rawMd;
        log.info("[MD_CORRECT] 시작: docId={}, chars={}", docId, rawMd.length());
        long t0 = System.currentTimeMillis();

        String preprocessed = addImageDescriptions
                ? augmentImageDescriptionsWithLocalVision(rawMd, correctedOutputPath)
                : rawMd;

        List<String> sections = splitBySections(preprocessed);
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
        result = normalizeCodeBlocks(result, false);
        if (addHeadingNumbers) {
            result = secondPassHeadingAndCodePolish(result);
        }
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
                - 내용(사실, 데이터, 수치, 의미)을 변경 절대 금지

                교정 항목:
                - 잘린 문장 연결 (줄바꿈으로 끊긴 문장을 이어붙이기)
                - 명백한 오타 수정
                - 표(table)는 변경 금지.
                - 마커 형식 유지: [이미지: ...], [이미지(변환불가): ...], [헤딩페이지: N], [페이지: N]을 그대로 둘 것
                - 내용이 코드일 경우, 코드 블록(```)으로 감싸고 언어 태그를 유지
                - 코드 블록(```) 내부의 불필요한 공란을 줄이고, 빈 줄 최소화로 가독성을 높일 것 (의미는 변경 금지)
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

    private String augmentImageDescriptionsWithLocalVision(String md, Path correctedOutputPath) {
        if (md == null || md.isBlank()) return md;
        LlmProvider localVisionProvider;
        try {
            localVisionProvider = llmRouter.routeProvider(TaskType.VISION, RoutingMode.LOCAL_ONLY);
        } catch (Exception e) {
            return md;
        }

        Path baseDir = correctedOutputPath != null && correctedOutputPath.getParent() != null
                ? correctedOutputPath.getParent() : null;
        Path dataDir = baseDir != null ? baseDir.getParent() : null;
        Map<String, String> descCache = new HashMap<>();

        String withMarkerDesc = injectDescriptionsForPattern(md, IMAGE_MARKER, true, localVisionProvider, baseDir, dataDir, descCache);
        return injectDescriptionsForPattern(withMarkerDesc, MD_IMAGE_LINK, false, localVisionProvider, baseDir, dataDir, descCache);
    }

    private String injectDescriptionsForPattern(String input,
                                                Pattern pattern,
                                                boolean imageMarker,
                                                LlmProvider localVisionProvider,
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
            String desc = cache.computeIfAbsent(key, k -> describeImage(localVisionProvider, imagePath));
            if (desc == null || desc.isBlank()) {
                m.appendReplacement(out, Matcher.quoteReplacement(full));
                continue;
            }

            String decorated;
            if (imageMarker) {
                decorated = full + "\n[이미지 설명: " + desc + "]";
            } else {
                decorated = full + "\n> 이미지 설명: " + desc;
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

    private String describeImage(LlmProvider provider, Path imagePath) {
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            String mimeType = detectMime(imagePath.toString());
            String prompt = "이 이미지를 한국어 1~2문장으로 간단히 설명하세요.";
            String response = ChatClient.builder(provider.chatModel()).build()
                    .prompt()
                    .user(u -> u.text(prompt)
                            .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(bytes)))
                .call()
                .content();
            return response == null ? "" : response.trim();
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

    private String normalizeCodeBlocks(String md, boolean inferLanguage) {
        Matcher m = FENCED_BLOCK.matcher(md);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String lang = m.group(1) == null ? "" : m.group(1).trim();
            String code = m.group(2) == null ? "" : m.group(2);
            if (inferLanguage && lang.isBlank()) {
                lang = inferCodeLanguage(code);
            }
            String normalized = normalizeCodeContent(code);
            String replacement = "```" + lang + "\n" + normalized + "\n```";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
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

    private String inferCodeLanguage(String code) {
        if (code == null || code.isBlank()) return "";
        String trimmed = code.trim();
        String lower = trimmed.toLowerCase();

        if (looksLikeJson(trimmed)) return "json";
        if (lower.startsWith("<?xml") || (lower.contains("<") && lower.contains("</"))) return "xml";
        if (lower.contains("<html") || lower.contains("</html>")) return "html";
        if (lower.startsWith("---") || lower.matches("(?s).*^\\s*[a-zA-Z0-9_.-]+:\\s+.+$.*")) return "yaml";
        if (lower.contains("public class") || lower.contains("import java.") || lower.contains("system.out.println")) return "java";
        if (lower.matches("(?s).*\\b(select|insert|update|delete|create table|alter table)\\b.*")) return "sql";
        if (lower.startsWith("#!/bin/sh")) return "sh";
        if (lower.startsWith("#!/bin/bash") || lower.startsWith("#!/usr/bin/env bash")
                || lower.contains(" apt-get ") || lower.contains(" curl ") || lower.contains(" grep ")) return "bash";
        if (lower.matches("(?s).*\\b(def|class|import|from)\\b.*") && lower.contains(":")) return "python";
        if (lower.matches("(?s).*\\b(function|const|let|var|console\\.log|=>)\\b.*")) return "javascript";
        return "";
    }

    private boolean looksLikeJson(String code) {
        String t = code.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) return false;
        return t.contains(":") && (t.endsWith("}") || t.endsWith("]"));
    }

    private String normalizeCodeContent(String code) {
        String[] lines = code.split("\\n", -1);
        List<String> cleaned = new ArrayList<>(lines.length);
        int blankRun = 0;
        for (String line : lines) {
            String trimmedRight = line.replaceAll("[ \\t]+$", "");
            if (trimmedRight.isBlank()) {
                blankRun++;
                if (blankRun > 1) continue;
                cleaned.add("");
                continue;
            }
            blankRun = 0;
            cleaned.add(trimmedRight);
        }
        while (!cleaned.isEmpty() && cleaned.get(0).isBlank()) cleaned.remove(0);
        while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).isBlank()) cleaned.remove(cleaned.size() - 1);
        return String.join("\n", cleaned);
    }
}
