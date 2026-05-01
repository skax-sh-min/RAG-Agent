package com.example.ragagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * ProcessBuilder-based WMF → PNG conversion via LibreOffice headless.
 * Uses a temp directory per call; cleans up afterwards.
 * Returns Optional.empty() on timeout, missing output, or any error.
 * Active only when app.image-description.docx-wmf-convert=true.
 */
@Component
@ConditionalOnProperty(name = "app.image-description.docx-wmf-convert", havingValue = "true")
public class LibreOfficeConverter {

    private static final Logger log = LoggerFactory.getLogger(LibreOfficeConverter.class);
    private static final int TIMEOUT_SECONDS = 20;

    public Optional<byte[]> convert(byte[] wmfBytes, String originalExt) {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("wmf-convert-");
            Path inputFile = tmpDir.resolve("input." + originalExt);
            Files.write(inputFile, wmfBytes);

            Process process = new ProcessBuilder(
                    "soffice", "--headless", "--convert-to", "png",
                    "--outdir", tmpDir.toAbsolutePath().toString(),
                    inputFile.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("LibreOffice WMF conversion timed out after {}s", TIMEOUT_SECONDS);
                return Optional.empty();
            }

            Path pngFile = tmpDir.resolve("input.png");
            if (!Files.exists(pngFile)) {
                log.warn("LibreOffice WMF conversion produced no output (exit={})", process.exitValue());
                return Optional.empty();
            }

            return Optional.of(Files.readAllBytes(pngFile));
        } catch (Exception e) {
            log.warn("WMF to PNG conversion failed: {}", e.getMessage());
            return Optional.empty();
        } finally {
            deleteTempDir(tmpDir);
        }
    }

    private void deleteTempDir(Path dir) {
        if (dir == null) return;
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.debug("Failed to clean up temp dir {}: {}", dir, e.getMessage());
        }
    }
}
