package com.example.ragagent.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Marker rewriting, heading numbering/TOC, and plain-text conversion for document export. */
class ExportPreprocessorTest {

    /** Mirrors the MD renderer's rewriter. */
    private static final BiFunction<String, Boolean, String> MD_IMAGES =
            (path, atLineStart) -> "![img](images/" + path.substring(path.lastIndexOf('/') + 1) + ")";

    private static String run(String md, boolean descriptions) {
        return ExportPreprocessor.preprocess(md, descriptions, false, MD_IMAGES);
    }

    @Nested
    @DisplayName("이미지 마커")
    class ImageMarkers {

        @Test
        @DisplayName("한 줄짜리 이미지 마커를 링크로 바꾼다")
        void rewritesLineImageMarker() {
            assertThat(run("본문\n[이미지: images/abc/d1_img1.png]\n끝", true))
                    .contains("![img](images/d1_img1.png)")
                    .doesNotContain("[이미지:");
        }

        @Test
        @DisplayName("표 셀 안 인라인 이미지 마커도 바꾼다 (줄바꿈 없이)")
        void rewritesInlineImageMarkerInsideTable() {
            String out = run("| 항목 | 그림 |\n| --- | --- |\n| A | [이미지: images/abc/x.png] |", true);

            assertThat(out).contains("![img](images/x.png)");
            assertThat(out).doesNotContain("[이미지:");
            // 표가 깨지지 않아야 한다 — 셀 줄이 그대로 한 줄이어야 함
            assertThat(out.lines().filter(l -> l.startsWith("| A |")).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("변환 불가 이미지 마커도 처리한다")
        void rewritesUnconvertibleMarker() {
            assertThat(run("[이미지(변환불가): images/abc/y.emf]", true))
                    .contains("![img](images/y.emf)")
                    .doesNotContain("변환불가");
        }
    }

    @Nested
    @DisplayName("이미지 설명 마커")
    class Descriptions {

        private static final String MULTILINE = """
                [이미지: images/a/b.png]
                [이미지 설명: 이 이미지는 **구조**를 보여줍니다.

                두 번째 문단입니다. 대괄호 [예시] 포함.]
                다음 본문""";

            @Test
            @DisplayName("여러 줄에 걸친 설명을 인용문으로 옮긴다")
            void convertsMultilineDescription() {
                String out = run(MULTILINE, true);

                assertThat(out).contains("> **이미지 설명**");
                assertThat(out).contains("두 번째 문단입니다.");
                assertThat(out).contains("[예시]");          // 본문 속 대괄호는 보존
                assertThat(out).doesNotContain("[이미지 설명:");
                assertThat(out).contains("다음 본문");        // 마커 뒤 본문이 잘리지 않음
            }

        @Test
        @DisplayName("설명 제외 옵션이면 통째로 사라진다")
        void dropsDescriptionWhenExcluded() {
            String out = run(MULTILINE, false);

            assertThat(out).doesNotContain("이미지 설명");
            assertThat(out).doesNotContain("두 번째 문단");
            assertThat(out).contains("다음 본문");
        }

        @Test
        @DisplayName("표 셀 안 <br> 뒤 설명은 인라인으로 남고 <br>는 제거된다")
        void inlineDescriptionKeepsTableIntact() {
            String out = run("| A | 그림<br>[이미지 설명: 설명 문장입니다.] |", true);

            assertThat(out).contains("(이미지 설명: 설명 문장입니다.)");
            assertThat(out).doesNotContain("<br>");
            assertThat(out.lines().count()).isEqualTo(1);   // 표 셀이 한 줄로 유지
        }
    }

    @Nested
    @DisplayName("구조 마커 제거")
    class StructuralMarkers {

        @Test
        @DisplayName("페이지·도형그룹·다이어그램 마커는 지우고 차트 라벨은 남긴다")
        void stripsScaffolding() {
            String out = run("[페이지: 3]\n[도형 그룹]\n내부 텍스트\n[/도형 그룹]\n[차트: 매출 추이]", true);

            assertThat(out).doesNotContain("[페이지:", "[도형 그룹]", "[/도형 그룹]", "[차트:");
            assertThat(out).contains("내부 텍스트");
            assertThat(out).contains("매출 추이");
        }
    }

    @Nested
    @DisplayName("소제목 번호 · 목차")
    class HeadingNumbers {

        @Test
        @DisplayName("H2~H6에 계층 번호를 붙이고 목차를 앞에 만든다")
        void numbersHeadingsAndBuildsToc() {
            String out = ExportPreprocessor.preprocess(
                    "# 제목\n\n## 개요\n\n본문\n\n### 상세\n\n본문2\n\n## 결론\n\n끝", true, true, MD_IMAGES);

            assertThat(out).startsWith("## 목차");
            assertThat(out).contains("## 1 개요");
            assertThat(out).contains("### 1.1 상세");
            assertThat(out).contains("## 2 결론");
            assertThat(out).doesNotContain("# 1 제목");   // H1은 번호 대상 아님
        }

        @Test
        @DisplayName("이미 번호가 있으면 중복해서 붙지 않는다 (멱등)")
        void isIdempotent() {
            String once  = ExportPreprocessor.applyHeadingNumbers("## 1 개요\n### 1.1 상세");
            String twice = ExportPreprocessor.applyHeadingNumbers(once);

            assertThat(twice).isEqualTo(once);
            assertThat(twice).contains("## 1 개요").contains("### 1.1 상세");
        }

        @Test
        @DisplayName("코드 블록 안의 # 주석은 헤딩으로 취급하지 않는다")
        void ignoresHashInsideCodeFence() {
            String out = ExportPreprocessor.applyHeadingNumbers("## 설정\n\n```bash\n## 주석입니다\n```");

            assertThat(out).contains("## 1 설정");
            assertThat(out).contains("## 주석입니다");   // 번호가 붙지 않음
        }
    }

    @Nested
    @DisplayName("TXT 평문 변환")
    class PlainText {

        @Test
        @DisplayName("인용문 안의 헤딩도 마커가 남지 않는다")
        void stripsHeadingInsideBlockquote() {
            // Vision 이미지 설명이 자체적으로 마크다운 헤딩을 포함하는 실제 사례
            String out = PlainTextRenderer.render("> ### 옵션 1: 설명\n> 본문입니다.");

            assertThat(out).doesNotContain("###");
            assertThat(out).doesNotContain(">");
            assertThat(out).contains("옵션 1: 설명");
            assertThat(out).contains("본문입니다.");
        }

        @Test
        @DisplayName("헤딩·강조·링크를 걷어내고 코드 내용은 남긴다")
        void stripsMarkdownSyntax() {
            String out = PlainTextRenderer.render(
                    "## 제목\n\n**굵게** 그리고 *기울임*, `코드`.\n\n```java\nint a = 1;\n```");

            assertThat(out).contains("제목");
            assertThat(out).contains("굵게 그리고 기울임, 코드.");
            assertThat(out).contains("int a = 1;");
            assertThat(out).doesNotContain("**", "```", "## ");
        }
    }
}
