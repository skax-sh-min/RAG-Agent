package com.example.ragagent.service;

import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — DocumentLoaderService.splitMarkdownBySections() fence awareness.
 *
 * Regression: a '#' line inside a fenced code block (e.g. a shell/python comment) must NOT be
 * treated as a markdown section heading — otherwise the section is mis-split at the comment,
 * producing chunks that "start with #" but are really source-code comments.
 * loadFromMarkdown() is the public entry point that runs splitMarkdownBySections().
 */
class DocumentLoaderMarkdownSectionTest {

    // loadFromMarkdown / splitMarkdownBySections touch neither the converter nor OCR.
    private final DocumentLoaderService loader = new DocumentLoaderService(null, Optional.empty());

    @Test
    @DisplayName("코드펜스 안의 '# 주석' 줄은 섹션 헤딩으로 분리되지 않는다")
    void fencedCommentDoesNotSplitSection() {
        String md = """
                ## 설치 방법

                아래 스크립트를 실행합니다.

                ```bash
                # 의존성 설치
                npm install
                # 빌드
                npm run build
                ```

                끝.
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getMetadata().get("heading")).isEqualTo("설치 방법");
        // the fenced comments survive as body content, not as their own sections
        assertThat(sections.get(0).getText()).contains("# 의존성 설치").contains("# 빌드");
    }

    @Test
    @DisplayName("진짜 마크다운 헤딩은 여전히 섹션을 나눈다 (펜스 밖)")
    void realHeadingsStillSplit() {
        String md = """
                # 개요
                첫 섹션 본문

                ## 상세
                둘째 섹션 본문
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get("heading")).isEqualTo("개요");
        assertThat(sections.get(1).getMetadata().get("heading")).isEqualTo("상세");
    }

    @Test
    @DisplayName("펜스가 닫힌 뒤의 헤딩은 정상적으로 새 섹션을 시작한다")
    void headingAfterClosedFenceSplits() {
        String md = """
                # 예제
                ```python
                # comment inside code
                x = 1
                ```

                ## 다음 절
                본문
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getText()).contains("# comment inside code");
        assertThat(sections.get(1).getMetadata().get("heading")).isEqualTo("다음 절");
    }

    @Test
    @DisplayName("[페이지: N] 마커는 바로 다음 헤딩 섹션의 page_or_slide 메타데이터로 반영되고, 마커 자체는 본문에 남지 않는다")
    void pageMarkerBeforeHeadingSetsPageOrSlideMetadata() {
        // Mirrors what PptxToMarkdownConverter/PdfToMarkdownConverter actually emit: a [페이지: N]
        // marker immediately before each slide/page's heading.
        String md = """
                [페이지: 1]
                ## 첫 슬라이드

                내용 A

                [페이지: 2]
                ## 둘째 슬라이드

                내용 B
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get(MetaKey.HEADING)).isEqualTo("첫 슬라이드");
        assertThat(sections.get(0).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(1);
        assertThat(sections.get(1).getMetadata().get(MetaKey.HEADING)).isEqualTo("둘째 슬라이드");
        assertThat(sections.get(1).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(2);
        // metadata-only marker — must never leak into the stored/displayed text (§10.1)
        assertThat(sections.get(0).getText()).doesNotContain("[페이지:");
        assertThat(sections.get(1).getText()).doesNotContain("[페이지:");
    }

    @Test
    @DisplayName("헤딩이 전혀 없는 문서에 [페이지: N] 마커만 있으면, 그 페이지 번호가 유실되지 않도록 " +
            "전체가 헤딩 없는 단일 섹션으로 남되 첫 페이지 번호를 유지한다")
    void pageMarkerWithoutAnyHeadingKeepsFirstPageNumber() {
        // Guards the collapse risk PdfToMarkdownConverter/PptxToMarkdownConverter must avoid by
        // always emitting a heading per page/slide — without one, splitMarkdownBySections() never
        // flushes a section boundary and the whole document becomes a single section.
        String md = """
                [페이지: 1]
                본문만 있고 헤딩이 없는 문서입니다.
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(1);
    }

    @Test
    @DisplayName("'#' 뒤에 공백이 없는 줄(해시태그 등)은 가짜 헤딩으로 오인해 섹션을 쪼개지 않는다")
    void hashPrefixWithoutSpaceIsNotTreatedAsHeading() {
        // A PPTX/DOCX paragraph can legitimately start with a literal '#' that isn't a markdown
        // heading — e.g. a social-media-style hashtag ("#캠페인") in a marketing slide. CommonMark
        // ATX headings require '#' to be followed by whitespace or end-of-line; without that check
        // this line would be mis-split into its own (heading-less) section.
        String md = """
                ## 캠페인 소개

                #캠페인 #신제품 #출시기념
                이번 분기 마케팅 캠페인입니다.

                ## 다음 절
                본문
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get("heading")).isEqualTo("캠페인 소개");
        assertThat(sections.get(0).getText()).contains("#캠페인 #신제품 #출시기념");
        assertThat(sections.get(1).getMetadata().get("heading")).isEqualTo("다음 절");
    }

    @Test
    @DisplayName("'#'로만 이루어진 줄(뒤에 아무 내용 없음)은 여전히 빈 헤딩으로 인정된다 (CommonMark: '#' + 줄끝)")
    void hashOnlyLineWithNothingAfterIsStillAHeading() {
        String md = """
                본문 시작

                ##
                다음 섹션 본문
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(1).getMetadata().get("heading")).isEqualTo("");
        assertThat(sections.get(1).getText()).contains("다음 섹션 본문");
    }
}
