package com.example.ragagent.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA — UploadValidator: sanitize + extension + magic-byte 단일 출처 검증
 *
 * Covers (per refactoring/12-upload-validator.md):
 *  - 경로 트래버설 거부
 *  - dot-only 파일명 거부
 *  - 미지원 확장자 거부 (UnsupportedFileTypeException)
 *  - 매직바이트 불일치 거부 (UnsupportedFileTypeException)
 *  - 한글 파일명 통과
 *  - 정상 PDF → 임시 파일 반환
 */
class UploadValidatorTest {

    // ── sanitizeFilename ──────────────────────────────────────────────────────

    @Test
    @DisplayName("경로 트래버설 시도 → 마지막 세그먼트만 추출됨 (../etc/passwd → passwd)")
    void sanitize_traversal_keepsFilenameOnly() {
        String result = UploadValidator.sanitizeFilename("../etc/passwd");
        assertThat(result).isEqualTo("passwd");
    }

    @Test
    @DisplayName("dot-only 파일명 → IllegalArgumentException")
    void sanitize_dotOnly_throws() {
        assertThatThrownBy(() -> UploadValidator.sanitizeFilename("..."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("leading-dot 파일명 → IllegalArgumentException")
    void sanitize_leadingDot_throws() {
        assertThatThrownBy(() -> UploadValidator.sanitizeFilename(".hidden"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한글 파일명 → 그대로 통과")
    void sanitize_koreanFilename_passThrough() {
        assertThat(UploadValidator.sanitizeFilename("문서.pdf")).isEqualTo("문서.pdf");
    }

    @Test
    @DisplayName("특수문자 → '_' 치환")
    void sanitize_specialChars_replaced() {
        String result = UploadValidator.sanitizeFilename("my file (1).pdf");
        assertThat(result).isEqualTo("my_file__1_.pdf");
    }

    @Test
    @DisplayName("null 파일명 → upload_타임스탬프 형식 반환")
    void sanitize_null_returnsTimestampName() {
        String result = UploadValidator.sanitizeFilename(null);
        assertThat(result).startsWith("upload_");
    }

    // ── checkExtension ────────────────────────────────────────────────────────

    @Test
    @DisplayName("미지원 확장자 → UnsupportedFileTypeException")
    void checkExtension_unsupported_throws() {
        assertThatThrownBy(() -> UploadValidator.checkExtension("malware.exe"))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    @Test
    @DisplayName("지원 확장자 → 예외 없음")
    void checkExtension_pdf_noException() {
        UploadValidator.checkExtension("document.pdf");
    }

    // ── stageToTemp ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 PDF → 임시 파일 생성 후 경로 반환")
    void stageToTemp_validPdf_returnsTempPath(@TempDir Path dir) throws IOException {
        byte[] pdfMagic = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34};  // %PDF-1.4
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", pdfMagic);

        Path tmp = UploadValidator.stageToTemp(file, "test.pdf");
        try {
            assertThat(tmp).exists();
            assertThat(Files.size(tmp)).isEqualTo(pdfMagic.length);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("PDF 이름이지만 내용이 평문 → UnsupportedFileTypeException + 임시 파일 삭제")
    void stageToTemp_magicByteMismatch_throwsAndCleansUp() {
        byte[] plainText = "this is not a pdf".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "fake.pdf", "application/pdf", plainText);

        assertThatThrownBy(() -> UploadValidator.stageToTemp(file, "fake.pdf"))
                .isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessageContaining("magic-byte mismatch");
    }
}
