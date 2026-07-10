package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.apache.poi.sl.usermodel.AutoNumberingScheme;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFChartAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFHyperlink;
import org.apache.poi.xslf.usermodel.XSLFObjectShape;
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
import java.util.List;
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

    @Test
    @DisplayName("FOOTER/SLIDE_NUMBER/DATETIME placeholder 텍스트는 매 슬라이드 본문에 유입되지 않는다")
    void footerPlaceholderTextIsExcludedFromBody() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "제목");
            XSLFTextBox body = slide.createTextBox();
            addRun(addParagraph(body, false, 0), "일반 본문", false, false);

            XSLFTextBox footer = slide.createTextBox();
            footer.setPlaceholder(Placeholder.FOOTER);
            addRun(addParagraph(footer, false, 0), "대외비", false, false);

            XSLFTextBox slideNumber = slide.createTextBox();
            slideNumber.setPlaceholder(Placeholder.SLIDE_NUMBER);
            addRun(addParagraph(slideNumber, false, 0), "SLIDENUM_MARKER", false, false);

            XSLFTextBox dateTime = slide.createTextBox();
            dateTime.setPlaceholder(Placeholder.DATETIME);
            addRun(addParagraph(dateTime, false, 0), "2024-01-01", false, false);
        });

        String md = convert();

        assertThat(md).contains("일반 본문");
        assertThat(md).doesNotContain("대외비");
        assertThat(md).doesNotContain("SLIDENUM_MARKER");
        assertThat(md).doesNotContain("2024-01-01");
    }

    @Test
    @DisplayName("자동 번호 매기기 불릿은 순서형 마커(\"1. \")로, 일반 불릿은 \"- \"로 렌더링된다")
    void autoNumberedBulletRendersAsOrderedMarker() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "절차");
            XSLFTextBox body = slide.createTextBox();

            XSLFTextParagraph p1 = addParagraph(body, true, 0);
            p1.setBulletAutoNumber(AutoNumberingScheme.arabicPeriod, 1);
            addRun(p1, "첫 단계", false, false);

            XSLFTextParagraph p2 = addParagraph(body, true, 0);
            p2.setBulletAutoNumber(AutoNumberingScheme.arabicPeriod, 2);
            addRun(p2, "둘째 단계", false, false);

            XSLFTextParagraph p3 = addParagraph(body, true, 0); // 일반 불릿(자동 번호 아님)
            addRun(p3, "일반 불릿", false, false);
        });

        String md = convert();

        assertThat(md).contains("1. 첫 단계");
        assertThat(md).contains("1. 둘째 단계"); // 마크다운 순서형 목록은 소스 번호를 그대로 쓰지 않아도 됨
        assertThat(md).contains("- 일반 불릿");
    }

    @Test
    @DisplayName("헤딩 후보 텍스트의 내부 공백 차이가 정규화되어 크로스 슬라이드 빈도 집계가 흔들리지 않는다")
    void headingCandidateWhitespaceIsNormalizedForFrequencyCalibration() throws IOException {
        writePptx(pptx -> {
            addTwoTitleSlide(pptx, "landing1", "온라인   서비스 개발"); // 내부에 공백 2칸
            addTwoTitleSlide(pptx, "landing2", "온라인 서비스 개발");
            addTwoTitleSlide(pptx, "landing3", "온라인 서비스 개발");
        });

        String md = convert();

        // 정규화 전이라면 슬라이드1의 라벨("온라인   서비스 개발")이 다른 두 슬라이드와 다른
        // 문자열로 취급되어 빈도가 1대1로 갈리고, 슬라이드1만 부제(landing1)가 상위(##) 헤딩으로
        // 잘못 승격된다. 정규화 후에는 세 슬라이드 모두 공통 라벨이 상위 헤딩이 되어야 한다.
        assertThat(md).contains("## 온라인 서비스 개발");
        assertThat(md).doesNotContain("온라인   서비스 개발"); // 공백 2칸 형태는 사라져야 함
        assertThat(md).contains("### landing1");
        assertThat(md).contains("### landing2");
        assertThat(md).contains("### landing3");
        assertThat(md).doesNotContain("\n## landing1")
                .doesNotContain("\n## landing2")
                .doesNotContain("\n## landing3");
    }

    @Test
    @DisplayName("하이퍼링크가 있는 run은 [텍스트](URL) 마크다운 링크로 렌더링된다")
    void hyperlinkRunRendersAsMarkdownLink() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "참고자료");
            XSLFTextBox body = slide.createTextBox();
            XSLFTextParagraph p = addParagraph(body, false, 0);
            XSLFTextRun run = addRun(p, "공식 문서", false, false);
            XSLFHyperlink link = run.createHyperlink();
            link.linkToUrl("https://example.com/docs");
        });

        String md = convert();

        assertThat(md).contains("[공식 문서](https://example.com/docs)");
    }

    @Test
    @DisplayName("SmartArt(다이어그램) 도형의 박스 텍스트는 본문에서 검색 가능한 텍스트로 추출된다")
    void smartArtBoxTextIsExtractedAsBodyText() throws IOException {
        PptxSmartArtFixture.write(pptxPath, List.of("기획팀", "개발팀", "운영팀"));

        String md = convert();

        assertThat(md).contains("기획팀");
        assertThat(md).contains("개발팀");
        assertThat(md).contains("운영팀");
    }

    @Test
    @DisplayName("차트 프레임의 제목 텍스트는 본문으로 추출된다")
    void chartTitleIsExtractedAsBodyText() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "실적 현황");
            XSLFChart chart = pptx.createChart();
            chart.setTitleText("연도별 매출 추이");

            XDDFCategoryDataSource catDs = XDDFDataSourcesFactory.fromArray(new String[] {"2023", "2024"});
            XDDFNumericalDataSource<Double> valDs = XDDFDataSourcesFactory.fromArray(new Double[] {10.0, 20.0});
            XDDFChartAxis catAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis valAxis = chart.createValueAxis(AxisPosition.LEFT);
            XDDFBarChartData bar = (XDDFBarChartData) chart.createData(ChartTypes.BAR, catAxis, valAxis);
            XDDFBarChartData.Series series = (XDDFBarChartData.Series) bar.addSeries(catDs, valDs);
            series.setTitle("매출", null);
            chart.plot(bar);

            slide.addChart(chart, new Rectangle2D.Double(50, 50, 300, 200));
        });

        String md = convert();

        assertThat(md).contains("연도별 매출 추이");
    }

    @Test
    @DisplayName("OLE 객체는 텍스트를 남기지 않지만, 내장 미리보기 이미지는 [이미지: ...] 마커로 추출된다")
    void oleObjectContributesOnlyItsPreviewImageNotText() throws IOException {
        writePptx(pptx -> {
            XSLFSlide slide = pptx.createSlide();
            addTitle(slide, "첨부 문서");
            byte[] fakePreviewPng = "fake-ole-preview-bytes".getBytes();
            XSLFPictureData pd = pptx.addPicture(fakePreviewPng, PictureData.PictureType.PNG);
            XSLFObjectShape ole = slide.createOleShape(pd);
            ole.setAnchor(new Rectangle2D.Double(10, 10, 100, 100));
        });

        String md = convert();

        assertThat(md).contains("[이미지: images/doc1/s1_img1.png]");
    }
}
