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
 * <p>Prefers the chapter number ({@link MetaKey#CHAPTER_NO}) when the chunk has a real one
 * (H2-H6 heading structure), else falls back to the page/slide number — "0" means "no chapter
 * applies here" (prologue or PPTX), not a real chapter, so it must not be shown as one.
 */
class RetrievalServiceFormatSourceTest {

    private static Document doc(Map<String, Object> meta) {
        return new Document("content", meta);
    }

    @Test
    @DisplayName("챕터 번호가 있으면(0이 아니면) '파일 | 챕터번호' 형식을 쓴다")
    void usesChapterNoWhenPresent() {
        Document d = doc(Map.of(
                MetaKey.FILENAME, "manual.docx",
                MetaKey.CHAPTER_NO, "1.5.3",
                MetaKey.PAGE_OR_SLIDE, 7));

        assertThat(RetrievalService.formatSource(d)).isEqualTo("manual.docx | 1.5.3");
    }

    @Test
    @DisplayName("챕터 번호가 \"0\"(프롤로그/PPTX)이면 페이지 번호로 폴백한다: '파일 | p.N'")
    void fallsBackToPageWhenChapterNoIsZero() {
        Document d = doc(Map.of(
                MetaKey.FILENAME, "slides.pptx",
                MetaKey.CHAPTER_NO, "0",
                MetaKey.PAGE_OR_SLIDE, 3));

        assertThat(RetrievalService.formatSource(d)).isEqualTo("slides.pptx | p.3");
    }

    @Test
    @DisplayName("챕터 번호 메타데이터 자체가 없으면(구버전 인덱스 등) 페이지 번호로 폴백한다")
    void fallsBackToPageWhenChapterNoMissing() {
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
}
