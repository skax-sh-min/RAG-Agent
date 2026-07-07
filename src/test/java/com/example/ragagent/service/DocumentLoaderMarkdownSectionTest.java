package com.example.ragagent.service;

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
}
