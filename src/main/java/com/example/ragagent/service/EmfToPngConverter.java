package com.example.ragagent.service;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.transcoder.wmf.tosvg.WMFTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Batik-based EMF → PNG in-memory conversion.
 * Pipeline: EMF bytes → WMFTranscoder → SVG → PNGTranscoder → PNG bytes.
 * Returns Optional.empty() on any failure so the caller can fall back to original.
 * Active only when app.image-description.docx-emf-convert=true.
 */
@Component
@ConditionalOnProperty(name = "app.image-description.docx-emf-convert", havingValue = "true")
public class EmfToPngConverter {

    private static final Logger log = LoggerFactory.getLogger(EmfToPngConverter.class);

    public Optional<byte[]> convert(byte[] emfBytes) {
        try {
            ByteArrayOutputStream svgOut = new ByteArrayOutputStream();
            try (OutputStreamWriter writer = new OutputStreamWriter(svgOut, StandardCharsets.UTF_8)) {
                new WMFTranscoder().transcode(
                        new TranscoderInput(new ByteArrayInputStream(emfBytes)),
                        new TranscoderOutput(writer));
            }

            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            new PNGTranscoder().transcode(
                    new TranscoderInput(new InputStreamReader(
                            new ByteArrayInputStream(svgOut.toByteArray()), StandardCharsets.UTF_8)),
                    new TranscoderOutput(pngOut));
            return Optional.of(pngOut.toByteArray());
        } catch (Exception e) {
            log.warn("EMF to PNG conversion failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
