package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — RagService.isSupportedExtension (B-03)
 *
 * Verifies that upload extension whitelist correctly accepts/rejects file types
 * before any disk staging happens.
 */
class RagServiceExtensionTest {

    @Test
    @DisplayName("지원 확장자: pdf, pptx, docx, txt, md 모두 허용")
    void supportedExtensions() {
        assertThat(RagService.isSupportedExtension("doc.pdf")).isTrue();
        assertThat(RagService.isSupportedExtension("doc.PDF")).isTrue();
        assertThat(RagService.isSupportedExtension("slide.pptx")).isTrue();
        assertThat(RagService.isSupportedExtension("memo.docx")).isTrue();
        assertThat(RagService.isSupportedExtension("note.txt")).isTrue();
        assertThat(RagService.isSupportedExtension("readme.md")).isTrue();
    }

    @Test
    @DisplayName("거부 확장자: exe, zip, html, sh, js, jar, py 등")
    void rejectedExtensions() {
        assertThat(RagService.isSupportedExtension("payload.exe")).isFalse();
        assertThat(RagService.isSupportedExtension("archive.zip")).isFalse();
        assertThat(RagService.isSupportedExtension("page.html")).isFalse();
        assertThat(RagService.isSupportedExtension("script.sh")).isFalse();
        assertThat(RagService.isSupportedExtension("app.jar")).isFalse();
        assertThat(RagService.isSupportedExtension("malware.py")).isFalse();
    }

    @Test
    @DisplayName("확장자 없는 파일 / 빈 / null 거부")
    void edgeCases() {
        assertThat(RagService.isSupportedExtension("Makefile")).isFalse();
        assertThat(RagService.isSupportedExtension("")).isFalse();
        assertThat(RagService.isSupportedExtension(null)).isFalse();
    }

    @Test
    @DisplayName("이중 확장자 위조: 마지막 확장자만 검증해야 함 (.pdf.exe → 거부)")
    void doubleExtensionForgery() {
        assertThat(RagService.isSupportedExtension("invoice.pdf.exe")).isFalse();
        assertThat(RagService.isSupportedExtension("doc.exe.pdf")).isTrue();
    }
}
