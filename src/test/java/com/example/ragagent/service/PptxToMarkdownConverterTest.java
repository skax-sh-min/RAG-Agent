package com.example.ragagent.service;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — PptxToMarkdownConverter: slide title → H2, [페이지: N] marker per slide, body outline
 * level → nested list only (never promoted to its own heading, per the PPTX heading-level
 * decision), bold/italic emphasis without duplicated markers, inline [이미지: ...] markers per
 * slide (like DOCX — image_paths metadata is promoted downstream by loadFromMarkdown()).
 */
class PptxToMarkdownConverterTest {

    private final PptxToMarkdownConverter converter = new PptxToMarkdownConverter(new PptxImageExtractor());
    private Path pptxPath;
    private Path imagesDir;

    @BeforeEach
    void setUp() throws IOException {
        pptxPath = Files.createTempFile("pptx-md-test-", ".pptx");
        imagesDir = Files.createTempDirectory("pptx-md-images-");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(pptxPath);
        deleteRecursively(imagesDir);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    private void writePptx(Consumer<XMLSlideShow> builder) throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            builder.accept(pptx);
            try (OutputStream out = Files.newOutputStream(pptxPath)) {
                pptx.write(out);
            }
        }
    }

    private String convert() throws IOException {
        return converter.convert(pptxPath, "doc1", imagesDir);
    }

    private static XSLFTextBox addTitle(XSLFSlide slide, String text) {
        XSLFTextBox box = slide.createTextBox();
        box.setPlaceholder(Placeholder.TITLE);
        box.setText(text);
        return box;
    }

    private static XSLFTextParagraph addParagraph(XSLFTextBox box, boolean bullet, int indentLevel) {
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setBullet(bullet);
        p.setIndentLevel(indentLevel);
        return p;
    }

    private static XSLFTextRun addRun(XSLFTextParagraph p, String text, boolean bold, boolean italic) {
        XSLFTextRun r = p.addNewTextRun();
        r.setText(text);
        r.setBold(bold);
        r.setItalic(italic);
        return r;
    }

    @Test
    @DisplayName("슬라이드 제목 placeholder는 [페이지: N] 마커와 함께 H2로 승격된다")
    void titlePlaceholderBecomesH2WithPageMarker() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "개요");
        });

        String md = convert();

        assertThat(md).contains("[페이지: 1]");
        assertThat(md).contains("## 개요");
    }

    @Test
    @DisplayName("제목 placeholder가 없는 슬라이드는 폴백 헤딩(\"N번 슬라이드\")을 받는다")
    void slideWithoutTitleGetsFallbackHeading() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFTextBox body = slide.createTextBox();
            XSLFTextParagraph p = addParagraph(body, false, 0);
            addRun(p, "제목 없는 본문", false, false);
        });

        String md = convert();

        assertThat(md).contains("## 1번 슬라이드");
    }

    @Test
    @DisplayName("불릿 문단의 들여쓰기 레벨은 중첩 목록으로만 반영되고 소제목으로 승격되지 않는다")
    void bulletIndentLevelBecomesNestedListNotHeading() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "본문 슬라이드");
            XSLFTextBox body = slide.createTextBox();
            XSLFTextRun r0 = addRun(addParagraph(body, true, 0), "항목1", false, false);
            XSLFTextRun r1 = addRun(addParagraph(body, true, 1), "하위항목1", false, false);
        });

        String md = convert();

        assertThat(md).contains("- 항목1");
        assertThat(md).contains("  - 하위항목1");
        // never promoted to a heading regardless of indent depth
        assertThat(md).doesNotContain("### 항목1").doesNotContain("### 하위항목1");
    }

    @Test
    @DisplayName("불릿이 아닌 문단은 일반 텍스트 줄로 렌더링된다")
    void nonBulletParagraphStaysPlainText() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "제목");
            XSLFTextBox body = slide.createTextBox();
            addRun(addParagraph(body, false, 0), "일반 본문 문장", false, false);
        });

        String md = convert();

        assertThat(md).contains("일반 본문 문장");
        assertThat(md).doesNotContain("- 일반 본문 문장");
    }

    @Test
    @DisplayName("인접한 동일 스타일 run은 병합되어 강조 마커가 중복되지 않는다")
    void mergesAdjacentBoldRunsWithoutDuplicatingMarkers() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "제목");
            XSLFTextBox body = slide.createTextBox();
            XSLFTextParagraph p = addParagraph(body, false, 0);
            addRun(p, "라우팅", true, false);
            addRun(p, "전략", true, false);
        });

        String md = convert();

        assertThat(md).contains("**라우팅전략**");
        assertThat(md).doesNotContain("****");
    }

    @Test
    @DisplayName("italic 강조 마커가 올바르게 적용된다")
    void appliesItalicEmphasis() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "제목");
            XSLFTextBox body = slide.createTextBox();
            addRun(addParagraph(body, false, 0), "강조문장", false, true);
        });

        String md = convert();

        assertThat(md).contains("_강조문장_");
    }

    @Test
    @DisplayName("제목도 본문도 이미지도 없는 슬라이드(공백 등)는 통째로 건너뛰어 빈 청크를 만들지 않는다")
    void slideWithNoTitleNoBodyNoImageIsSkippedEntirely() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            // no title placeholder, no text box, no picture — e.g. a blank divider slide
        });

        String md = convert();

        assertThat(md).doesNotContain("[페이지: 1]");
        assertThat(md).doesNotContain("번 슬라이드");
    }

    @Test
    @DisplayName("제목·본문은 없지만 이미지가 있는 슬라이드는 건너뛰지 않고 폴백 헤딩 + [이미지: ...] 마커를 받는다")
    void slideWithOnlyPictureIsNotSkippedAndGetsImageMarker() throws IOException {
        writePptx(pptx -> {
            byte[] fakePng = "fake-png-bytes".getBytes();
            XSLFPictureData pd = pptx.addPicture(fakePng, PictureData.PictureType.PNG);
            XSLFSlide slide = pptx.createSlide();
            slide.createPicture(pd);
            // no title, no text box — image is the slide's only content
        });

        String md = convert();

        assertThat(md).contains("[페이지: 1]");
        assertThat(md).contains("## 1번 슬라이드");
        assertThat(md).contains("[이미지: images/doc1/s1_img1.png]");
        assertThat(Files.exists(imagesDir.resolve("s1_img1.png"))).isTrue();
    }

    @Test
    @DisplayName("제목이 있는 슬라이드의 이미지도 [이미지: ...] 마커로 헤딩 바로 다음에 삽입된다")
    void titledSlideWithPictureGetsImageMarkerAfterHeading() throws IOException {
        writePptx(pptx -> {
            byte[] fakePng = "fake-png-bytes".getBytes();
            XSLFPictureData pd = pptx.addPicture(fakePng, PictureData.PictureType.PNG);
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "다이어그램");
            slide.createPicture(pd);
        });

        String md = convert();

        assertThat(md).contains("[이미지: images/doc1/s1_img1.png]");
        int headingIdx = md.indexOf("## 다이어그램");
        int imageIdx = md.indexOf("[이미지:");
        assertThat(headingIdx).isGreaterThanOrEqualTo(0);
        assertThat(imageIdx).isGreaterThan(headingIdx);
    }

    @Test
    @DisplayName("빈 슬라이드는 건너뛰지만 이후 슬라이드 번호는 실제 슬라이드 인덱스를 그대로 유지한다")
    void blankSlideSkippedWithoutShiftingSubsequentSlideNumbers() throws IOException {
        writePptx(pptx -> {
            addTitle(pptx.createSlide(), "첫 슬라이드");
            pptx.createSlide(); // slide 2: no title, no text — skipped entirely
            addTitle(pptx.createSlide(), "셋째 슬라이드");
        });

        String md = convert();

        assertThat(md).contains("[페이지: 1]").contains("[페이지: 3]");
        assertThat(md).doesNotContain("[페이지: 2]");
        assertThat(md).contains("## 셋째 슬라이드"); // real slide index, not a re-numbered "2번 슬라이드"
    }

    @Test
    @DisplayName("여러 슬라이드의 [페이지: N] 마커가 슬라이드 순서와 일치한다")
    void pageMarkersMatchSlideOrder() throws IOException {
        writePptx(pptx -> {
            addTitle(pptx.createSlide(), "첫 슬라이드");
            addTitle(pptx.createSlide(), "둘째 슬라이드");
            addTitle(pptx.createSlide(), "셋째 슬라이드");
        });

        String md = convert();

        int idx1 = md.indexOf("[페이지: 1]");
        int idx2 = md.indexOf("[페이지: 2]");
        int idx3 = md.indexOf("[페이지: 3]");
        assertThat(idx1).isGreaterThanOrEqualTo(0);
        assertThat(idx2).isGreaterThan(idx1);
        assertThat(idx3).isGreaterThan(idx2);
        assertThat(md).contains("## 첫 슬라이드").contains("## 둘째 슬라이드").contains("## 셋째 슬라이드");
    }
}
