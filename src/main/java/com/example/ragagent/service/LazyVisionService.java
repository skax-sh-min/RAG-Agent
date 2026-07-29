package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.ImageDescriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Checks the image_descriptions cache; calls VisionDescriptionService only for misses.
 * When app.image-description.classify-type=true, first classifies each image via
 * ImageTypeClassifier, then uses the type-specific prompt for Vision description.
 * Active only when app.image-description.enabled=true.
 */
@Service
@ConditionalOnProperty(name = "app.image-description.enabled", havingValue = "true")
public class LazyVisionService {

    private static final Logger log = LoggerFactory.getLogger(LazyVisionService.class);

    private final VisionDescriptionService visionService;
    private final ImageDescriptionRepository descRepo;
    private final ImageTypeClassifier imageTypeClassifier;
    private final AppProperties props;

    public LazyVisionService(VisionDescriptionService visionService,
                             ImageDescriptionRepository descRepo,
                             ImageTypeClassifier imageTypeClassifier,
                             AppProperties props) {
        this.visionService = visionService;
        this.descRepo = descRepo;
        this.imageTypeClassifier = imageTypeClassifier;
        this.props = props;
    }

    /**
     * Returns descriptions for all given image paths.
     * Cache hits are returned instantly; misses are described in parallel and persisted.
     * When classifyType is enabled, each image is classified first and a type-specific
     * prompt is used for Vision description; the type is stored in image_descriptions.
     */
    public Map<String, String> describeIfNeeded(List<String> imagePaths) {
        return describeIfNeeded(imagePaths, null);
    }

    /**
     * Same as {@link #describeIfNeeded(List)}, but reports progress through {@code onProgress}
     * (called once with {@code (0, total)} as soon as the miss count is known, then once more per
     * completed image — see {@link GraphListener#onImageAnalysisProgress}). {@code null} is a valid
     * no-op, same as the single-arg overload.
     */
    public Map<String, String> describeIfNeeded(List<String> imagePaths, BiConsumer<Integer, Integer> onProgress) {
        return describeIfNeeded(imagePaths, onProgress, null);
    }

    /**
     * Same as {@link #describeIfNeeded(List, BiConsumer)}, but polls {@code skipRequested} instead
     * of blocking on every in-flight Vision call — once it returns {@code true}, this returns
     * immediately with whatever descriptions are done so far. Images still in flight at that point
     * are **not** cancelled: their executor keeps running them in the background so the result
     * still lands in {@code image_descriptions} for the next turn (only {@code newDescs}/the
     * returned map misses out on them for *this* turn). {@code null} is a valid no-op, same as the
     * other overloads — used by the blocking chat path, which has no skip button to wire up.
     */
    public Map<String, String> describeIfNeeded(List<String> imagePaths, BiConsumer<Integer, Integer> onProgress,
                                                 BooleanSupplier skipRequested) {
        if (imagePaths.isEmpty()) return Map.of();

        Map<String, String> cached = descRepo.findAll(imagePaths);
        List<String> misses = imagePaths.stream()
                .filter(p -> !cached.containsKey(p))
                .toList();

        if (misses.isEmpty()) return cached;

        int total = misses.size();
        if (onProgress != null) onProgress.accept(0, total);

        boolean classifyTypeEnabled = props.imageDescriptionSafe().classifyType();
        int maxLlm = props.indexingSafe().maxConcurrentLlmCalls();
        Semaphore semaphore = new Semaphore(maxLlm);
        Path dataDir = Path.of(props.dataDir());
        Map<String, String> newDescs = new ConcurrentHashMap<>();
        AtomicInteger doneCount = new AtomicInteger(0);

        // Deliberately NOT try-with-resources: ExecutorService.close() awaits termination of every
        // submitted task, which would defeat the whole point of "skip" below (stop waiting, but let
        // already-running Vision calls keep going in the background). shutdown() near the end just
        // stops new submissions — this executor already has everything it will ever get.
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<Void>> futures = misses.stream()
                .map(imgPath -> CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            Path fullPath = dataDir.resolve(imgPath);
                            if (!Files.exists(fullPath)) return;
                            byte[] bytes = Files.readAllBytes(fullPath);
                            String mime = detectMime(imgPath);
                            // [LLM curl] logs (LoggingChatModel) only see the Prompt's text/Media
                            // bytes, never the source file path, so without this line a DEBUG
                            // session can't tell which image a given Vision request belongs to.
                            log.debug("[VISION] 이미지 분석 요청: {} (mime={}, {} bytes)", imgPath, mime, bytes.length);

                            String imageType = null;
                            String desc;
                            if (classifyTypeEnabled) {
                                imageType = imageTypeClassifier.classify(bytes, mime);
                                String prompt = VisionDescriptionService.PROMPTS.getOrDefault(
                                        imageType, VisionDescriptionService.PROMPTS.get("other"));
                                desc = visionService.describe(bytes, mime, prompt);
                            } else {
                                desc = visionService.describe(bytes, mime);
                            }

                            newDescs.put(imgPath, desc);
                            descRepo.save(imgPath, desc, imageType, null);
                        } finally {
                            semaphore.release();
                        }
                    } catch (Exception e) {
                        log.warn("Vision description failed for {}: {}", imgPath, e.getMessage());
                    } finally {
                        // Reported on completion regardless of success/failure — a failed
                        // description still "finishes" that image's slot, and the counter must
                        // reach total or the client's progress indicator would stall short.
                        if (onProgress != null) onProgress.accept(doneCount.incrementAndGet(), total);
                    }
                }, exec))
                .toList();

        CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        boolean skipped = false;
        pollLoop:
        while (!allDone.isDone()) {
            if (skipRequested != null && skipRequested.getAsBoolean()) {
                skipped = true;
                break;
            }
            try {
                allDone.get(150, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // still working — loop back and check skipRequested again
            } catch (InterruptedException e) {
                // The turn's own worker thread was interrupted (e.g. the user hit the full-turn
                // "중지" button, or the idle watchdog fired) — stop waiting the same way a skip
                // would, but restore the flag so the caller's own interrupt handling still sees it.
                Thread.currentThread().interrupt();
                break pollLoop;
            } catch (ExecutionException e) {
                // Never actually thrown in practice — every per-image task catches its own
                // exceptions above, so runAsync() never completes exceptionally. Defensive only.
                break pollLoop;
            }
        }
        exec.shutdown();
        if (skipped) {
            log.info("[VISION] 사용자가 이미지 분석을 건너뜀 — {}/{}장 완료, 나머지는 백그라운드에서 계속 진행",
                    doneCount.get(), total);
        }

        Map<String, String> all = new HashMap<>(cached);
        all.putAll(newDescs);
        return all;
    }

    private String detectMime(String imagePath) {
        String lower = imagePath.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }
}
