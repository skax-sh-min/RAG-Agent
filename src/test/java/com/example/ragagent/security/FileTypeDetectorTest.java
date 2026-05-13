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
}
