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
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

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
        if (imagePaths.isEmpty()) return Map.of();

        Map<String, String> cached = descRepo.findAll(imagePaths);
        List<String> misses = imagePaths.stream()
                .filter(p -> !cached.containsKey(p))
                .toList();

        if (misses.isEmpty()) return cached;

        boolean classifyTypeEnabled = props.imageDescriptionSafe().classifyType();
        int maxLlm = props.indexingSafe().maxConcurrentLlmCalls();
        Semaphore semaphore = new Semaphore(maxLlm);
        Path dataDir = Path.of(props.dataDir());
        Map<String, String> newDescs = new ConcurrentHashMap<>();

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            misses.stream()
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
                        }
                    }, exec))
                    .toList()
                    .forEach(CompletableFuture::join);
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
