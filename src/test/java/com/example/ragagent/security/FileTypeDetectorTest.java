package com.example.ragagent.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeDetectorTest {

    @TempDir
    Path tmp;

    @Test
    void pdf_magic_bytes_accepted() throws Exception {
        Path f = tmp.resolve("doc.pdf");
        Files.write(f, "%PDF-1.4 dummy content".getBytes());
        assertThat(FileTypeDetector.matches(f, ".pdf")).isTrue();
    }

    @Test
    void txt_disguised_as_pdf_rejected() throws Exception {
        Path f = tmp.resolve("fake.pdf");
        Files.write(f, "This is plain text content, not a PDF".getBytes());
        assertThat(FileTypeDetector.matches(f, ".pdf")).isFalse();
    }

    @Test
    void docx_magic_bytes_accepted() throws Exception {
        Path f = tmp.resolve("doc.docx");
        // ZIP/PK signature: 0x50 0x4B 0x03 0x04
        Files.write(f, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00});
        assertThat(FileTypeDetector.matches(f, ".docx")).isTrue();
    }

    @Test
    void pptx_magic_bytes_accepted() throws Exception {
        Path f = tmp.resolve("deck.pptx");
        Files.write(f, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00});
        assertThat(FileTypeDetector.matches(f, ".pptx")).isTrue();
    }

    @Test
    void txt_binary_content_rejected() throws Exception {
        Path f = tmp.resolve("binary.txt");
        Files.write(f, new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07});
        assertThat(FileTypeDetector.matches(f, ".txt")).isFalse();
    }

    @Test
    void txt_ascii_content_accepted() throws Exception {
        Path f = tmp.resolve("readme.txt");
        Files.write(f, "Hello World".getBytes());
        assertThat(FileTypeDetector.matches(f, ".txt")).isTrue();
    }

    @Test
    void md_ascii_content_accepted() throws Exception {
        Path f = tmp.resolve("notes.md");
        Files.write(f, "# Heading\nSome text".getBytes());
        assertThat(FileTypeDetector.matches(f, ".md")).isTrue();
    }

    @Test
    void too_short_file_rejected() throws Exception {
        Path f = tmp.resolve("tiny.pdf");
        Files.write(f, "%PD".getBytes());
        assertThat(FileTypeDetector.matches(f, ".pdf")).isFalse();
    }

    @Test
    void unknown_extension_rejected() throws Exception {
        Path f = tmp.resolve("file.xyz");
        Files.write(f, "some content here".getBytes());
        assertThat(FileTypeDetector.matches(f, ".xyz")).isFalse();
    }

    // ── 지식 제안 본문 이미지 (CuratedImageStore) ────────────────────────────────

    @Test
    void png_magic_bytes_accepted() throws Exception {
        Path f = tmp.resolve("shot.png");
        Files.write(f, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        assertThat(FileTypeDetector.matches(f, ".png")).isTrue();
    }

    @Test
    void jpeg_magic_bytes_accepted() throws Exception {
        Path f = tmp.resolve("photo.jpg");
        Files.write(f, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10});
        assertThat(FileTypeDetector.matches(f, ".jpg")).isTrue();
        assertThat(FileTypeDetector.matches(f, ".jpeg")).isTrue();
    }

    @Test
    void gif_magic_bytes_accepted() throws Exception {
        Path f = tmp.resolve("anim.gif");
        Files.write(f, "GIF89a...".getBytes());
        assertThat(FileTypeDetector.matches(f, ".gif")).isTrue();
    }

    @Test
    void webp_needs_both_riff_and_webp_halves() throws Exception {
        Path ok = tmp.resolve("pic.webp");
        Files.write(ok, "RIFF????WEBPVP8 ".getBytes());
        assertThat(FileTypeDetector.matches(ok, ".webp")).isTrue();

        // RIFF container, but a WAV payload — the 8-11 half is what tells them apart, which is
        // the whole reason the header read is 12 bytes rather than 8.
        Path wav = tmp.resolve("sound.webp");
        Files.write(wav, "RIFF????WAVEfmt ".getBytes());
        assertThat(FileTypeDetector.matches(wav, ".webp")).isFalse();
    }

    @Test
    void executable_renamed_to_png_rejected() throws Exception {
        Path f = tmp.resolve("payload.png");
        Files.write(f, new byte[]{'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00});
        assertThat(FileTypeDetector.matches(f, ".png")).isFalse();
    }

    @Test
    void short_text_file_accepted() throws Exception {
        // 12바이트 미만이라 버퍼 뒤쪽이 0으로 남는다 — 그 패딩까지 NUL 로 세면 짧은 텍스트 파일이
        // 전부 거부된다(헤더를 12바이트로 늘리면서 실제로 문제가 될 수 있었던 경계).
        Path f = tmp.resolve("tiny.txt");
        Files.write(f, "hi".getBytes());
        assertThat(FileTypeDetector.matches(f, ".txt")).isFalse();   // 4바이트 미만은 여전히 거부

        Path five = tmp.resolve("five.md");
        Files.write(five, "hello".getBytes());
        assertThat(FileTypeDetector.matches(five, ".md")).isTrue();
    }
}
