package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — PdfToMarkdownConverter: one [페이지: N] marker + synthetic "## N페이지" heading per
 * non-blank page, page numbers matching the true (1-based) page index even when earlier pages
 * were blank and skipped.
 */
class PdfToMarkdownConverterTest {

    private final PdfToMarkdownConverter converter = new PdfToMarkdownConverter();

    @Test
    @DisplayName("페이지마다 [페이지: N] 마커와 페이지 헤딩이 순서대로 생성된다")
    void multiplePagesGetOrderedPageMarkers() {
        List<Document> pages = List.of(
                new Document("첫 페이지 내용", Map.of()),
                new Document("둘째 페이지 내용", Map.of()),
                new Document("셋째 페이지 내용", Map.of()));

        String md = converter.convert(pages, Path.of("report.pdf"));

        int idx1 = md.indexOf("[페이지: 1]");
        int idx2 = md.indexOf("[페이지: 2]");
        int idx3 = md.indexOf("[페이지: 3]");
        assertThat(idx1).isGreaterThanOrEqualTo(0);
        assertThat(idx2).isGreaterThan(idx1);
        assertThat(idx3).isGreaterThan(idx2);
        assertThat(md).contains("## 1페이지").contains("## 2페이지").contains("## 3페이지");
        assertThat(md).contains("첫 페이지 내용").contains("둘째 페이지 내용").contains("셋째 페이지 내용");
    }

    @Test
    @DisplayName("빈 페이지는 건너뛰지만 이후 페이지 번호는 실제 페이지 인덱스를 그대로 유지한다")
    void blankPageSkippedWithoutShiftingSubsequentPageNumbers() {
        List<Document> pages = List.of(
                new Document("첫 페이지 내용", Map.of()),
                new Document("   ", Map.of()),   // blank page (e.g. divider slide-equivalent)
                new Document("셋째 페이지 내용", Map.of()));

        String md = converter.convert(pages, Path.of("report.pdf"));

        assertThat(md).doesNotContain("[페이지: 2]");
        assertThat(md).contains("[페이지: 1]");
        assertThat(md).contains("[페이지: 3]");
        assertThat(md).contains("## 3페이지"); // real page index, not a re-numbered "2페이지"
    }

    @Test
    @DisplayName("문서 제목은 파일명에서 유도되어 H1으로 추가된다")
    void documentTitleDerivedFromFilename() {
        List<Document> pages = List.of(new Document("본문", Map.of()));

        String md = converter.convert(pages, Path.of("2024_사용자_가이드.pdf"));

        assertThat(md).startsWith("# ");
        assertThat(md).contains("사용자 가이드");
    }
}
