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

    // ---------------------------------------------------------------------------------------------
    // 챕터 번호 (chapter_no) — H2~H6 계층 카운터, 1부터. 헤딩 이전(프롤로그)은 "0"
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("챕터 번호 — H2는 1부터, H3는 1.1처럼 부모 아래에서 카운트")
    void chapterNo_h2AndH3_hierarchical() {
        String md = """
                ## 첫 장
                본문1

                ### 첫 절
                본문1-1

                ### 둘째 절
                본문1-2

                ## 둘째 장
                본문2
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(4);
        assertThat(sections.get(0).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("1");
        assertThat(sections.get(1).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("1.1");
        assertThat(sections.get(2).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("1.2");
        assertThat(sections.get(3).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("2");
    }

    @Test
    @DisplayName("챕터 번호 — 3단계 깊이(H2>H3>H4)는 1.5.3처럼 표시된다")
    void chapterNo_threeLevelsDeep() {
        String md = """
                ## 장
                a

                ## 장
                b

                ## 장
                c

                ## 장
                d

                ## 장
                e

                ### 절
                f

                #### 항
                g
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(7);
        assertThat(sections.get(4).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("5");
        assertThat(sections.get(5).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("5.1");
        assertThat(sections.get(6).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("5.1.1");
    }

    @Test
    @DisplayName("챕터 번호 — 첫 H2 헤딩 이전(프롤로그) 구간은 \"0\"")
    void chapterNo_prologueIsZero() {
        String md = """
                프롤로그 본문(헤딩 없음)

                ## 첫 장
                본문
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("0");
        assertThat(sections.get(1).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("1");
    }

    @Test
    @DisplayName("챕터 번호 — H1은 챕터로 세지 않는다(값이 바뀌지 않고 이전 상태를 유지)")
    void chapterNo_h1DoesNotAdvanceCounter() {
        String md = """
                # 문서 제목
                본문

                ## 첫 장
                본문
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("0"); // H1 — 프롤로그와 동일 취급
        assertThat(sections.get(1).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("1");
    }

    @Test
    @DisplayName("챕터 번호 — 헤딩이 전혀 없는 문서(단일 섹션)도 \"0\"을 갖는다")
    void chapterNo_noHeadingsAtAll() {
        List<Document> sections = loader.loadFromMarkdown("그냥 평문입니다. 헤딩이 전혀 없습니다.");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("0");
    }

    @Test
    @DisplayName("챕터 번호 — PPTX(skipChapterNumbers=true)는 헤딩이 있어도 항상 \"0\" (슬라이드 제목일 뿐, 목차 구조 아님)")
    void chapterNo_pptxAlwaysZero() {
        String md = """
                [페이지: 1]
                ## 첫 슬라이드
                내용 A

                [페이지: 2]
                ## 둘째 슬라이드
                내용 B
                """;

        List<Document> sections = loader.loadFromMarkdown(md, true); // skipChapterNumbers=true

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("0");
        assertThat(sections.get(1).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("0");
        // page_or_slide is still tracked normally for PPTX
        assertThat(sections.get(0).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(1);
        assertThat(sections.get(1).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(2);
    }

    @Test
    @DisplayName("챕터 번호 — 비스캔 PDF의 합성 페이지 헤딩(\"## N페이지\")도 skipChapterNumbers=true면 항상 \"0\" "
            + "(진짜 챕터가 아니라 페이지당 1개씩 붙는 합성 헤딩이므로, 세면 사실상 페이지 번호를 다른 이름으로 중복시킬 뿐)")
    void chapterNo_nonScannedPdfSyntheticPageHeadingAlwaysZero() {
        // Mirrors PdfToMarkdownConverter's exact output shape: "[페이지: N]" + "## N페이지" per page.
        String md = """
                [페이지: 1]
                ## 1페이지
                내용 A

                [페이지: 2]
                ## 2페이지
                내용 B
                """;

        List<Document> sections = loader.loadFromMarkdown(md, true); // skipChapterNumbers=true

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("0");
        assertThat(sections.get(1).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("0");
        assertThat(sections.get(0).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(1);
        assertThat(sections.get(1).getMetadata().get(MetaKey.PAGE_OR_SLIDE)).isEqualTo(2);
    }

    @Test
    @DisplayName("챕터 번호 — 코드펜스 안의 '### ...' 로그 줄은 챕터 카운터를 증가시키지 않는다")
    void chapterNo_fencedContentDoesNotAdvanceCounter() {
        String md = """
                ## 첫 장

                ```
                ### 이건 로그일 뿐
                ```

                ## 둘째 장
                본문
                """;

        List<Document> sections = loader.loadFromMarkdown(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("1");
        assertThat(sections.get(1).getMetadata().get(MetaKey.CHAPTER_NO)).isEqualTo("2"); // not "1.1" or similar
    }
}
