package com.example.ragagent.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Extracts embedded raster images from PDF pages using PDFBox 3.x.
 * Saves each image as p{page}_img{n}.png.
 * L0 filter: skips images smaller than MIN_IMAGE_BYTES (estimated) to exclude icons/backgrounds,
 * and larger than MAX_IMAGE_PIXELS to bound the single allocation getImage() makes.
 */
@Component
public class PdfImageExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfImageExtractor.class);

    private static final int MIN_IMAGE_BYTES = 1_000;

    /**
     * 디코딩을 시도할 최대 픽셀 수. {@link #MIN_IMAGE_BYTES} 와 짝이 되는 상한이며, 성격은 다르다 —
     * 하한은 "쓸모없는 아이콘을 거르는" 품질 기준이고, 이쪽은 <b>메모리 안전장치</b>다.
     *
     * <p>{@code PDImageXObject.getImage()} 는 픽셀당 최소 4바이트짜리 {@link BufferedImage} 를
     * <b>한 번에</b> 할당한다. 여기 상한이 없으면 PDF 가 부르는 크기가 그대로 힙 요구가 된다 —
     * 20000×20000 이미지 한 장이 1.6GB 다. 대형 도면·고해상도 스캔에서 악의 없이도 나오며,
     * 인덱싱은 백그라운드에서 도는 작업이라 OOM 이 사용자에게는 "업로드가 실패했다"로만 보인다.
     *
     * <p>5천만 픽셀은 A4 를 600DPI 로 스캔한 것(약 3,500만)보다 넉넉하다 — 문서에서 실제로 읽을
     * 만한 이미지는 다 통과하고, 통과한 최악의 경우가 약 200MB 다. 넘는 이미지는 그 한 장만
     * 건너뛰고 나머지 추출은 계속한다(문서 전체를 실패시키지 않는다).
     *
     * <p>크기는 {@code getWidth()}/{@code getHeight()} 로 <b>디코딩 전에</b> 알 수 있다 — PDF
     * 딕셔너리의 /Width, /Height 라 픽셀을 만지지 않는다. 그래서 이 검사는 공짜다.
     */
    private static final long MAX_IMAGE_PIXELS = 50_000_000L;

    /** @return {pageNum(1-based) → relative image paths from dataDir} */
    public Map<Integer, List<String>> extract(Path pdfPath, String imageId, Path imagesDir)
            throws IOException {
        return extract(pdfPath, imageId, imagesDir, null);
    }

    /**
     * Same as {@link #extract(Path, String, Path)} but calls {@code onProgress(done, total)}
     * after each page is processed.
     */
    public Map<Integer, List<String>> extract(Path pdfPath, String imageId, Path imagesDir,
                                              BiConsumer<Integer, Integer> onProgress)
            throws IOException {
        Files.createDirectories(imagesDir);
        Map<Integer, List<String>> result = new LinkedHashMap<>();

        try (PDDocument pdf = Loader.loadPDF(pdfPath.toFile())) {
            int totalPages = pdf.getNumberOfPages();
            int pageNum = 0;
            for (PDPage page : pdf.getPages()) {
                pageNum++;
                PDResources resources = page.getResources();
                if (resources != null) {
                    List<String> paths = new ArrayList<>();
                    int imgIdx = 0;
                    for (COSName name : resources.getXObjectNames()) {
                        PDXObject xObj;
                        try {
                            xObj = resources.getXObject(name);
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (!(xObj instanceof PDImageXObject img)) continue;

                        // 할당 '전에' 크기를 본다 — getImage() 는 픽셀당 4바이트를 한 번에 잡으므로
                        // 이 검사가 늦으면 막으려던 그 할당이 이미 일어난 뒤다.
                        if (!withinDecodeLimit(img.getWidth(), img.getHeight())) {
                            log.warn("[PDF-IMG] 이미지가 너무 커서 건너뜁니다: p{} {}x{} ({} MPixel, 상한 {} MPixel)",
                                    pageNum, img.getWidth(), img.getHeight(),
                                    (long) img.getWidth() * img.getHeight() / 1_000_000,
                                    MAX_IMAGE_PIXELS / 1_000_000);
                            continue;
                        }

                        BufferedImage bi;
                        try {
                            bi = img.getImage();
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (bi == null) continue;
                        // L0 filter: skip tiny images (icons, watermarks)
                        if ((long) bi.getWidth() * bi.getHeight() * 3 < MIN_IMAGE_BYTES) continue;

                        imgIdx++;
                        String fileName = "p" + pageNum + "_img" + imgIdx + ".png";
                        Path imgFile = imagesDir.resolve(fileName);
                        ImageIO.write(bi, "png", imgFile.toFile());
                        paths.add("images/" + imageId + "/" + fileName);
                    }
                    if (!paths.isEmpty()) result.put(pageNum, paths);
                }
                if (onProgress != null) onProgress.accept(pageNum, totalPages);
            }
        }
        return result;
    }

    /**
     * 디코딩을 시도해도 되는 크기인가 — {@link #MAX_IMAGE_PIXELS} 만 보는 순수 판정.
     *
     * <p><b>따로 뽑아 둔 이유</b>: 이 검사가 하는 일은 "거대한 할당을 <b>일어나지 않게</b> 하는 것"인데,
     * 그건 통합 테스트로 확인할 수가 없다. 딕셔너리 크기만 부풀린 PDF 로는 {@code getImage()} 가
     * 데이터 부족을 먼저 감지해 예외를 던지므로 <b>상한이 없어도 같은 결과(그 이미지가 빠진다)</b>가
     * 나오고, 진짜로 5천만 픽셀짜리 이미지를 만드는 테스트는 자기가 먼저 수백 MB 를 쓴다. 그래서
     * 판정을 직접 검증하고, 이 판정이 {@code getImage()} <b>앞</b>에 있다는 사실은 호출부를 읽어
     * 확인한다.
     *
     * <p>곱셈은 {@code long} 으로 한다 — {@code int} 로 계산하면 46341×46341 부터 넘쳐 음수가 되고,
     * 그러면 가장 큰 이미지가 검사를 통과한다(막으려던 바로 그 경우).
     */
    static boolean withinDecodeLimit(int width, int height) {
        if (width <= 0 || height <= 0) return false;
        return (long) width * height <= MAX_IMAGE_PIXELS;
    }
}
