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
 * Active only when app.image-description.enabled=true.
 */
@Service
@ConditionalOnProperty(name = "app.image-description.enabled", havingValue = "true")
public class LazyVisionService {

    private static final Logger log = LoggerFactory.getLogger(LazyVisionService.class);

    private final VisionDescriptionService visionService;
    private final ImageDescriptionRepository descRepo;
    private final AppProperties props;

    public LazyVisionService(VisionDescriptionService visionService,
                             ImageDescriptionRepository descRepo,
                             AppProperties props) {
        this.visionService = visionService;
        this.descRepo = descRepo;
        this.props = props;
    }

    /**
     * Returns descriptions for all given image paths.
     * Cache hits are returned instantly; misses are described in parallel and persisted.
     */
    public Map<String, String> describeIfNeeded(List<String> imagePaths) {
        if (imagePaths.isEmpty()) return Map.of();

        Map<String, String> cached = descRepo.findAll(imagePaths);
        List<String> misses = imagePaths.stream()
                .filter(p -> !cached.containsKey(p))
                .toList();

        if (misses.isEmpty()) return cached;

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
                                String desc = visionService.describe(bytes, mime);
                                newDescs.put(imgPath, desc);
                                descRepo.save(imgPath, desc, null, null);
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
