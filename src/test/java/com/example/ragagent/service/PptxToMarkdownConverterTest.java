package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — PptxToMarkdownConverter: slide title → H2, [페이지: N] marker per slide, body outline
 * level → nested list only (never promoted to its own heading, per the PPTX heading-level
 * decision), bold/italic emphasis without duplicated markers, inline [이미지: ...] markers per
 * slide (like DOCX — image_paths metadata is promoted downstream by loadFromMarkdown()). Also
 * covers the dual-heading heuristic (untyped "title + subtitle" text boxes on slides that have
 * bullets), its cross-slide frequency calibration, the leading-bullet dedup rule, cover/
 * divider-slide regressions where the heuristic must NOT kick in, and XSLFTable → markdown
 * pipe-table conversion (including merged-cell blanking).
 */
class PptxToMarkdownConverterTest {

    private static AppProperties mockAppProperties() {
        AppProperties props = mock(AppProperties.class);
        when(props.pptxImageSafe()).thenReturn(new AppProperties.PptxShapeExtractionConfig(30.0, 15.0));
        return props;
    }

    private final PptxToMarkdownConverter converter =
            new PptxToMarkdownConverter(new PptxImageExtractor(mockAppProperties()));
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

    /**
     * 제목 placeholder가 없는 슬라이드에 굵은 비불릿 텍스트 상자 2개(순서: 슬라이드마다 달라지는
     * 부제 먼저, 여러 슬라이드에 공통인 라벨 나중) + 불릿 본문을 만든다 — 실제 문제 사례(표 최상단
     * 좌측 "장" 라벨 + 우측 부제)를 그대로 재현한다.
     */
    private static void addTwoTitleSlide(XMLSlideShow pptx, String uniqueSubtitle, String commonLabel) {
        XSLFSlide slide = pptx.createSlide();
        XSLFTextBox subtitleBox = slide.createTextBox();
        addRun(addParagraph(subtitleBox, false, 0), uniqueSubtitle, true, false);
        XSLFTextBox labelBox = slide.createTextBox();
        addRun(addParagraph(labelBox, false, 0), commonLabel, true, false);
        XSLFTextBox content = slide.createTextBox();
        addRun(addParagraph(content, true, 0), "상세 내용", false, false);
    }

    @Test
    @DisplayName("두 헤딩 후보 중 더 많은 슬라이드에 공통으로 등장하는 텍스트가 상위(##) 헤딩으로 보정된다")
    void calibratesOuterHeadingByCrossSlideFrequency() throws IOException {
        writePptx(pptx -> {
            addTwoTitleSlide(pptx, "연동거래", "온라인 서비스 개발");
            addTwoTitleSlide(pptx, "배치처리", "온라인 서비스 개발");
            addTwoTitleSlide(pptx, "장애복구", "온라인 서비스 개발");
        });

        String md = convert();

        // "온라인 서비스 개발"은 매 슬라이드에서 두 번째로 발견되지만(발견 순서만 보면 하위 헤딩),
        // 3개 슬라이드 모두에 공통으로 등장하므로 보정 로직이 상위(##) 헤딩으로 승격해야 한다.
        assertThat(md).contains("## 온라인 서비스 개발");
        assertThat(md).contains("### 연동거래");
        assertThat(md).contains("### 배치처리");
        assertThat(md).contains("### 장애복구");
        assertThat(md).doesNotContain("### 온라인 서비스 개발");
        // "## X" is a substring of "### X", so anchor on the preceding newline to avoid a false
        // match against the (correct) "### 연동거래" line.
        assertThat(md).doesNotContain("\n## 연동거래")
                .doesNotContain("\n## 배치처리")
                .doesNotContain("\n## 장애복구");
    }

    @Test
    @DisplayName("본문의 첫 불릿이 헤딩 텍스트와 정확히 같으면 그 불릿 한 줄만 제거된다")
    void dropsLeadingBulletThatDuplicatesHeadingText() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFTextBox labelBox = slide.createTextBox();
            addRun(addParagraph(labelBox, false, 0), "온라인 서비스 개발", true, false);
            XSLFTextBox subtitleBox = slide.createTextBox();
            addRun(addParagraph(subtitleBox, false, 0), "연동거래", true, false);
            XSLFTextBox content = slide.createTextBox();
            addRun(addParagraph(content, true, 0), "연동거래", true, false); // ### 헤딩과 중복
            addRun(addParagraph(content, true, 0), "기동 거래에서 수동 거래를 호출하는 것", false, false);
        });

        String md = convert();

        assertThat(md).contains("## 온라인 서비스 개발");
        assertThat(md).contains("### 연동거래");
        assertThat(md).doesNotContain("- **연동거래**").doesNotContain("- 연동거래");
        assertThat(md).contains("- 기동 거래에서 수동 거래를 호출하는 것");
    }

    @Test
    @DisplayName("표지 슬라이드(제목 + 불릿 없는 부제)는 새 헤딩 승격 로직의 영향을 받지 않는다 — 회귀")
    void coverSlideWithBoldSubtitleIsUnaffectedByHeadingPromotion() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "2024년 3분기 실적 보고");
            XSLFTextBox subtitleBox = slide.createTextBox();
            addRun(addParagraph(subtitleBox, false, 0), "발표자: 홍길동", true, false);
            // 슬라이드 전체에 불릿이 하나도 없음 — 전형적인 표지 슬라이드
        });

        String md = convert();

        assertThat(md).contains("## 2024년 3분기 실적 보고");
        assertThat(md).doesNotContain("### 발표자");
        assertThat(md).contains("**발표자: 홍길동**"); // 승격되지 않고 굵은 본문 텍스트로 남는다
    }

    @Test
    @DisplayName("중간 표지 슬라이드(제목 없음, 불릿 없음, 굵은 텍스트 2개)는 폴백 헤딩 하나만 받는다 — 회귀")
    void sectionDividerSlideWithTwoBoldTextsGetsOnlyFallbackHeading() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            XSLFTextBox partBox = slide.createTextBox();
            addRun(addParagraph(partBox, false, 0), "PART 2", true, false);
            XSLFTextBox chapterBox = slide.createTextBox();
            addRun(addParagraph(chapterBox, false, 0), "결제 시스템", true, false);
            // 제목 placeholder도, 불릿도 없음 — 챕터 사이 구분 슬라이드
        });

        String md = convert();

        assertThat(md).contains("## 1번 슬라이드");
        assertThat(md).doesNotContain("### PART 2").doesNotContain("### 결제 시스템");
        assertThat(md).contains("**PART 2**").contains("**결제 시스템**");
    }

    @Test
    @DisplayName("슬라이드의 표(XSLFTable)는 마크다운 파이프 표로 변환된다")
    void tableConvertsToMarkdownPipeTable() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "표 슬라이드");
            XSLFTable table = slide.createTable(2, 3);
            table.getCell(0, 0).setText("이름");
            table.getCell(0, 1).setText("부서");
            table.getCell(0, 2).setText("직급");
            table.getCell(1, 0).setText("홍길동");
            table.getCell(1, 1).setText("개발팀");
            table.getCell(1, 2).setText("과장");
        });

        String md = convert();

        assertThat(md).contains("| 이름 | 부서 | 직급 |");
        assertThat(md).contains("| --- | --- | --- |");
        assertThat(md).contains("| 홍길동 | 개발팀 | 과장 |");
    }

    @Test
    @DisplayName("가로로 병합된 표 셀은 연속 셀이 빈 칸으로 렌더링된다")
    void mergedTableCellRendersBlank() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "병합 표 슬라이드");
            XSLFTable table = slide.createTable(2, 2);
            table.getCell(0, 0).setText("헤더");
            table.getCell(0, 1).setText("헤더");
            table.mergeCells(0, 0, 0, 1); // 첫 행의 두 열을 가로로 병합
            table.getCell(1, 0).setText("A");
            table.getCell(1, 1).setText("B");
        });

        String md = convert();

        assertThat(md).contains("| 헤더 |  |");
        assertThat(md).contains("| A | B |");
    }

    @Test
    @DisplayName("그룹 도형은 하나의 이미지로 래스터라이즈되지만, 내부 텍스트는 Vision 없이도 검색되도록 본문에도 별도로 추출된다")
    void groupInternalTextIsExtractedSeparatelyFromGroupImage() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "다이어그램 슬라이드");
            XSLFGroupShape group = slide.createGroup();
            Rectangle2D bounds = new Rectangle2D.Double(0, 0, 200, 100);
            group.setAnchor(bounds);
            group.setInteriorAnchor(bounds);
            XSLFTextBox label = group.createTextBox();
            label.setAnchor(new Rectangle2D.Double(10, 10, 80, 40));
            label.setText("승인 처리");
        });

        String md = convert();

        assertThat(md).contains("승인 처리"); // 그룹 내부 텍스트가 검색 가능한 본문 텍스트로 남는다
        assertThat(md).contains("[이미지:"); // 그룹 자체도 여전히 이미지로 래스터라이즈된다
    }
}
