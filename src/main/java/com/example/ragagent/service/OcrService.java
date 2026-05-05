package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;

/**
 * OCR service using Tesseract via tess4j (kor+eng).
 * Creates a new Tesseract instance per call — Tesseract is not thread-safe.
 * If app.image-description.tessdata-path is blank, Tesseract uses TESSDATA_PREFIX
 * environment variable or system default paths.
 * Active only when app.image-description.ocr-enabled=true.
 */
@Service
@ConditionalOnProperty(name = "app.image-description.ocr-enabled", havingValue = "true")
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final String tessdataPath;

    public OcrService(AppProperties props) {
        this.tessdataPath = props.imageDescriptionSafe().tessdataPath();
    }

    public String extractText(BufferedImage image) {
        try {
            Tesseract tesseract = new Tesseract();
            if (tessdataPath != null && !tessdataPath.isBlank()) {
                tesseract.setDatapath(tessdataPath);
            }
            tesseract.setLanguage("kor+eng");
            return tesseract.doOCR(image);
        } catch (TesseractException e) {
            log.warn("OCR failed: {}", e.getMessage());
            return "";
        }
    }
}
