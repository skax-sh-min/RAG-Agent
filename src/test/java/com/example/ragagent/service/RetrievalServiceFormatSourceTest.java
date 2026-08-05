package com.example.ragagent.service;

import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — RetrievalService.formatSource() citation label.
 *
 * <p>Uses chapter labels for chapter-structured sources (docx/md/txt): "ch X" when real chapter
 * metadata exists, else filename-only. Page-structured sources keep the existing "p.N" fallback.
 */
class RetrievalServiceFormatSourceTest {

    private static Document doc(Map<String, Object> meta) {
        return new Document("content", meta);
    }

    @Test
    @DisplayName("챕터 번호가 있으면(0이 아니면) '파일 | ch 챕터번호' 형식을 쓴다")
    void usesChapterNoWhenPresent() {
        Document d = doc(Map.of(
                MetaKey.FILENAME, "manual.docx",
                MetaKey.CHAPTER_NO, "1.5.3",
                MetaKey.PAGE_OR_SLIDE, 7));

        assertThat(RetrievalService.formatSource(d)).isEqualTo("manual.docx | ch 1.5.3");
    }

    @Test
    @DisplayName("PPTX처럼 페이지 기반 파일은 챕터가 0이면 페이지 번호로 폴백한다: '파일 | p.N'")
    void pageBasedFileFallsBackToPageWhenChapterNoIsZero() {
        Document d = doc(Map.of(
                MetaKey.FILENAME, "slides.pptx",
                MetaKey.CHAPTER_NO, "0",
                MetaKey.PAGE_OR_SLIDE, 3));

        assertThat(RetrievalService.formatSource(d)).isEqualTo("slides.pptx | p.3");
    }

    @Test
        @DisplayName("docx는 챕터 번호가 0이거나 없으면 'ch ?' 대신 파일명만 표시한다")
        void docxUsesUnknownChapterWhenChapterNoIsZeroOrMissing() {
        Document zeroChapter = doc(Map.of(
            MetaKey.FILENAME, "manual.docx",
            MetaKey.CHAPTER_NO, "0",
            MetaKey.PAGE_OR_SLIDE, 1));
        Document missingChapter = doc(Map.of(
            MetaKey.FILENAME, "manual.docx",
            MetaKey.PAGE_OR_SLIDE, 1));

        assertThat(RetrievalService.formatSource(zeroChapter)).isEqualTo("manual.docx");
        assertThat(RetrievalService.formatSource(missingChapter)).isEqualTo("manual.docx");
        }

        @Test
        @DisplayName("페이지 기반 파일에서 챕터 메타데이터가 없으면 페이지 번호로 폴백한다")
        void fallsBackToPageWhenChapterNoMissingOnPageBasedFile() {
        Document d = doc(Map.of(
                MetaKey.FILENAME, "old.pdf",
                MetaKey.PAGE_OR_SLIDE, 2));

        assertThat(RetrievalService.formatSource(d)).isEqualTo("old.pdf | p.2");
    }

    @Test
    @DisplayName("둘 다 없으면 '파일 | p.?' — 파일명도 없으면 'unknown'")
    void fallsBackToUnknownDefaults() {
        assertThat(RetrievalService.formatSource(doc(Map.of()))).isEqualTo("unknown | p.?");
    }

    @Test
    @DisplayName("§10.10 — 큐레이션 Q&A(doc_type=curated_qa)는 파일명/페이지 대신 고정 라벨을 쓴다")
    void curatedQaUsesFixedLabelInsteadOfPlaceholderFilename() {
        Document d = doc(Map.of(
                MetaKey.DOC_TYPE, "curated_qa",
                MetaKey.FILENAME, "curated_qa",
                MetaKey.PAGE_OR_SLIDE, 1));

        assertThat(RetrievalService.formatSource(d)).isEqualTo("💬 큐레이션 Q&A");
    }
}
