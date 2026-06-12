# IMAGE_PROCESS — 이미지 처리 전략

> **구현 현황**: 전체 파이프라인 구현 완료. ImageExtractorService·PdfImageExtractor·PptxImageExtractor·DocxToMarkdownConverter(DOCX 이미지)·OcrService·EmfToPngConverter·LibreOfficeConverter·LazyVisionService·ImageTypeClassifier·ImageDescriptionRepository·VisionDescriptionService 모두 구현됨.

문서 포맷별 이미지 처리 방식과 검색 결과 연동 전략을 아키텍트 및 개발자 관점에서 정의합니다.

---

## 1. 현황 및 문제

### 1.1 현재 처리 방식

| 포맷 | 이미지 유형 | 현재 처리 | 문제 |
|------|-----------|-----------|------|
| PDF  | 페이지 내 임베드 래스터/벡터 이미지 | 무시 | 다이어그램·도표 정보 유실 |
| PPTX | 슬라이드 그림·다이어그램 | `TextShape` 텍스트만 추출 | 슬라이드 핵심 정보 유실 |
| DOCX | 본문 인라인 이미지 | 단락 텍스트만 추출 | 삽화·스크린샷 정보 유실 |
| MD   | `![alt](path)` 링크 | 마크다운 문법 그대로 남음 | 검색 노이즈 |
| TXT  | 없음 | — | 해당 없음 |

### 1.2 개선 목표

1. 이미지 정보를 텍스트(설명)로 변환하여 벡터 검색 품질 향상
2. 추출된 이미지를 문서 단위로 저장하고 검색 결과에 포함하여 답변 시 근거 이미지 제시
3. 처리 비용(LLM 호출 수)과 검색 품질 사이의 균형을 설정(Level) 기반으로 제어

---

## 2. 처리 레벨 정의

| Level | 이름 | 방식 | 비용 | 적용 대상 |
|-------|------|------|------|-----------|
| L0 | 무시 | 이미지 참조 제거, 텍스트에 흔적 없음 | 없음 | 장식용 이미지 |
| L1 | 정제 | alt 텍스트·캡션만 유지, 마크다운 문법 제거 | 없음 | alt가 충분한 MD 이미지 |
| L2 | Vision 설명 | 이미지를 멀티모달 LLM에 전달 → 설명 텍스트 생성 | 이미지당 LLM 호출 | 다이어그램·캡처 |
| L3 | OCR | Tesseract로 이미지 내 텍스트 추출 | CPU | 텍스트 위주 스크린샷·표 |

---

## 3. 아키텍처 설계

### 3.1 컴포넌트 구조

```
┌─────────────────────────────────────────────────────────────────┐
│  인덱싱 파이프라인                                               │
│                                                                  │
│  RagService.indexDocument()                                      │
│    ├── 1. SHA-256 계산 → docId 확정                              │
│    ├── 2. ImageExtractorService.extract(filePath, docId)         │
│    │       └── 포맷 판별 → 이미지 추출 → data/images/{docId}/ 저장│
│    │           반환: Map<pageOrSlide, List<imagePath>>            │
│    ├── 3. DocumentLoaderService.load(filePath) → 텍스트 청크     │
│    ├── 4. 메타데이터 태깅 (image_paths 포함)                     │
│    ├── 5. KeywordMetadataEnricher.apply()                        │
│    └── 6. VectorStore.add()                                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  검색 파이프라인                                                 │
│                                                                  │
│  RetrievalService.execute()                                      │
│    ├── MultiQueryExpander → VectorStore 검색                     │
│    ├── 중복 제거 → unique chunks                                 │
│    ├── chunk.metadata["image_paths"] 수집 → imageRefs 목록 생성  │
│    └── AgentState.withImageRefs(imageRefs) 반환                  │
│                                                                  │
│  AgentService (최종 응답)                                        │
│    └── ChatResponse { answer, sources, imageRefs, ... }          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  이미지 서빙                                                     │
│                                                                  │
│  GET /api/images/{docId}/{filename}                              │
│    └── ApiController → data/images/{docId}/{filename} 스트리밍   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 구현된 컴포넌트

| 컴포넌트 | 역할 | 조건 |
|---------|------|------|
| `ImageExtractorService` | 포맷별 이미지 추출 + 디스크 저장 조율 | 항상 활성 |
| `PdfImageExtractor` | PDFBox `PDImageXObject` 기반 PDF 이미지 추출 | 항상 활성 |
| `PptxImageExtractor` | POI `XSLFPictureShape` 기반 PPTX 이미지 추출 | 항상 활성 |
| `DocxToMarkdownConverter` | DOCX → Markdown 변환 + 인라인 이미지 추출 (EMF/WMF 변환 포함) | 항상 활성 |
| `VisionDescriptionService` | 멀티모달 LLM 호출 → 이미지 설명 텍스트 생성 (L2); 유형별 프롬프트 내장 | 항상 활성 |
| `LazyVisionService` | 검색 시점 Vision 설명 생성 + SQLite 캐시 | `enabled=true` |
| `ImageDescriptionRepository` | `image_descriptions` 테이블 CRUD | 항상 활성 |
| `ImageTypeClassifier` | 이미지 유형 분류 → 전용 프롬프트 선택 | `enabled=true` |
| `OcrService` | Tesseract 호출 → 이미지 내 텍스트 추출 (L3) | `ocr-enabled=true` |
| `EmfToPngConverter` | Batik `WMFTranscoder` → SVG → `PNGTranscoder` 파이프라인 | `docx-emf-convert=true` |
| `LibreOfficeConverter` | `soffice --headless` 기반 WMF→PNG 변환 (20s 타임아웃) | `docx-wmf-convert=true` |

### 3.3 저장 구조

```
data/
└── images/
    └── {docId}/                     ← 문서 단위 격리
        ├── p1_img0.png              ← PDF: p{페이지}_{순번}
        ├── p1_img1.jpeg
        ├── s3_img0.png              ← PPTX: s{슬라이드}_{순번}
        ├── d0_img0.png              ← DOCX: d{섹션}_{순번}
        └── md_img0.png              ← MD: md_{순번} (로컬 경로 복사)
```

파일명 규칙: `{prefix}{pageOrSlide}_img{순번}.{ext}`
- 이미지 해시 기반 중복 저장 방지: 동일 byte[] → 동일 파일명 (SHA-256 prefix 8자리)

### 3.4 메타데이터 확장

청크 메타데이터에 이미지 경로 추가:

```json
{
  "doc_id":       "manual.pdf_a1b2c3d4",
  "filename":     "manual.pdf",
  "version":      "latest",
  "page_or_slide": 5,
  "image_paths":  ["images/manual.pdf_a1b2c3d4/p5_img0.png",
                   "images/manual.pdf_a1b2c3d4/p5_img1.png"],
  "image_description": "[이미지1: 3계층 아키텍처 다이어그램] [이미지2: 시퀀스 다이어그램]"
}
```

`image_description`은 L2/L3 처리 시에만 추가되며 벡터 임베딩 텍스트에 포함되어 검색 품질에 기여합니다.

### 3.5 응답 모델 확장

**`AgentState`** — 새 필드 추가:
```java
public record AgentState(
    ...
    List<String> imageRefs,   // 검색된 청크에서 수집한 이미지 상대 경로 목록
    ...
)
```

**`ChatResponse`** — 새 필드 추가:
```java
public record ChatResponse(
    String answer,
    @JsonProperty("question_type") String questionType,
    List<SourceRef> sources,                              // UI step 18에서 SourceRef로 전환 완료
    @JsonProperty("image_refs") List<String> imageRefs,   // /api/images/... URL 목록 (미구현)
    ...
)
```

---

## 4. 포맷별 처리 전략

### 4.1 PDF

**이미지 유형**: 페이지 임베드 래스터 이미지 (`PDImageXObject`), 인라인 이미지

**추출 방법** (PDFBox — 기존 의존성):
```java
// PdfImageExtractor.java
PDDocument pdf = PDDocument.load(filePath.toFile());
for (int i = 0; i < pdf.getNumberOfPages(); i++) {
    PDPage page = pdf.getPage(i);
    PDResources resources = page.getResources();
    int imgIdx = 0;
    for (COSName name : resources.getXObjectNames()) {
        PDXObject xobj = resources.getXObject(name);
        if (xobj instanceof PDImageXObject img) {
            BufferedImage buffered = img.getImage();
            String filename = "p" + (i + 1) + "_img" + imgIdx++ + ".png";
            ImageIO.write(buffered, "PNG", imagesDir.resolve(filename).toFile());
        }
    }
}
```

**처리 레벨 판단**:
- 스캔 PDF (`source_type=ocr`) → **L3 OCR** 우선 (이미지가 곧 페이지 전체)
- 일반 PDF 도표·그래프 → **L2 Vision**
- 배경·워터마크 (width < 100px 또는 height < 100px) → **L0 무시**

**설명 삽입 위치**: 해당 페이지 청크 텍스트 말미에 `\n[이미지: {설명}]` 추가

---

### 4.2 PPTX

**이미지 유형**: `XSLFPictureShape` (그림, 다이어그램, 스크린샷)

**추출 방법** (Apache POI — 기존 의존성):
```java
// PptxImageExtractor.java
XMLSlideShow pptx = new XMLSlideShow(new FileInputStream(filePath.toFile()));
int slideNum = 0;
for (XSLFSlide slide : pptx.getSlides()) {
    slideNum++;
    int imgIdx = 0;
    for (XSLFShape shape : slide.getShapes()) {
        if (shape instanceof XSLFPictureShape picShape) {
            XSLFPictureData data = picShape.getPictureData();
            String ext = data.getType().extension;  // png, jpeg, gif, ...
            String filename = "s" + slideNum + "_img" + imgIdx++ + "." + ext;
            Files.write(imagesDir.resolve(filename), data.getData());
        }
    }
}
```

**처리 레벨 판단**:
- 슬라이드 텍스트 길이 < 50자이고 이미지 존재 → 슬라이드 전체가 이미지 위주 → **L2 Vision 필수**
- 텍스트가 충분하고 이미지가 보조적 → **L2 Vision** 또는 설정에 따라 **L1**

**특이사항**: PPTX는 포맷 중 이미지 의존도가 가장 높아 L2 효과가 극대화됨

---

### 4.3 DOCX

**이미지 유형**: 단락 내 인라인 이미지 (`XWPFPictureData`)

**추출 방법** (Apache POI — 기존 의존성):
```java
// DocxImageExtractor.java
XWPFDocument docx = new XWPFDocument(new FileInputStream(filePath.toFile()));
List<XWPFPictureData> pics = docx.getAllPictures();
for (int i = 0; i < pics.size(); i++) {
    XWPFPictureData pic = pics.get(i);
    String ext = pic.suggestFileExtension();   // png, jpeg, wmf, emf, ...
    String filename = "d0_img" + i + "." + ext;
    Files.write(imagesDir.resolve(filename), pic.getData());
}
```

**처리 레벨 판단**:
- WMF/EMF (Windows Metafile) → 렌더링 후 PNG 변환 필요 → **L2 Vision**
- PNG/JPEG → **L2 Vision** 또는 alt 텍스트 확인 후 **L1**

**제약**: DOCX는 이미지와 단락의 정확한 위치 매핑이 어려움. `getAllPictures()`는 문서 전체 이미지를 반환하므로 섹션 단위 매핑은 `XWPFRun.getEmbeddedPictures()`로 보완 필요.

---

### 4.4 MD

**이미지 유형**: `![alt](path)` 로컬 파일 참조, `![alt](https://...)` URL 참조

**처리 방법**:

```java
// MarkdownImageExtractor.java — loadText() 전처리 단계
private static final Pattern IMG_TAG = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");

String preprocess(String content, Path mdFilePath, String docId, Path imagesDir) {
    Matcher m = IMG_TAG.matcher(content);
    StringBuffer sb = new StringBuffer();
    int imgIdx = 0;
    while (m.find()) {
        String alt  = m.group(1);
        String href = m.group(2);
        String replacement;

        if (href.startsWith("http://") || href.startsWith("https://")) {
            // URL 이미지: alt 텍스트만 유지 (L1)
            replacement = alt.isBlank() ? "" : "[이미지: " + alt + "]";
        } else {
            // 로컬 경로: 파일 복사 후 처리 (L2 또는 L1)
            Path imgPath = mdFilePath.getParent().resolve(href).normalize();
            if (Files.exists(imgPath)) {
                String ext = getExtension(href);
                String filename = "md_img" + imgIdx++ + "." + ext;
                Files.copy(imgPath, imagesDir.resolve(filename), REPLACE_EXISTING);
                // L2: Vision 설명 생성
                String desc = visionService.describe(imgPath);
                replacement = "[이미지: " + (alt.isBlank() ? desc : alt + " — " + desc) + "]";
            } else {
                replacement = alt.isBlank() ? "" : "[이미지: " + alt + "]";
            }
        }
        m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(sb);
    return sb.toString();
}
```

**링크 정제** (PDF 등 파일 링크):
```java
// [텍스트](doc.pdf) → 텍스트 (링크 자체는 별도 인덱싱으로 처리)
content = content.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
```

---

### 4.5 TXT

이미지 없음 — 처리 불필요.

---

## 5. Vision 설명 생성 (L2)

> 본 절은 **인덱싱 시점에 동기로** Vision을 호출하는 기본 설계입니다.
> 12절 Lazy Vision은 동일한 `VisionDescriptionService`를 검색 시점에 호출하는 방식이며,
> `app.image-description.lazy=true`(기본)일 때 활성화됩니다.

### 5.1 VisionDescriptionService 설계

> **구현 완료** — `service/VisionDescriptionService.java`.

현재 구현:
- `describe(byte[] imageBytes, String mimeType): String` — 기본 프롬프트("other" 유형) 사용
- `describe(byte[] imageBytes, String mimeType, String prompt): String` — `ImageTypeClassifier`가 선택한 유형별 전용 프롬프트 사용
- `@ConditionalOnProperty` 없음 — 항상 빈으로 등록; Vision 프로바이더 미등록 시 fallback 문자열 반환
- `LlmProviderExhaustedException` catch → `"[이미지 설명 불가: Vision 프로바이더 미등록]"` 반환

내장 유형별 프롬프트 (`PROMPTS` 맵):
| image_type | 프롬프트 핵심 |
|-----------|-------------|
| `diagram` | 구성 요소·관계·흐름 방향 |
| `screenshot` | UI 요소 위치·화면 텍스트, 코드면 언어·핵심 로직 |
| `chart` | 축·범례·관찰 트렌드 |
| `photo` | 피사체·상황 1문장 |
| `other` | 일반 설명 3문장 이내 |

**라우팅 동작**:
- `LlmRouter.route(TaskType.VISION, RoutingMode.COST_FIRST)` → `local-vision(VISION)` → `local(LIGHT_BOTH)` → `NORMAL(BOTH)` 순 fallback
- 모든 프로바이더 불가 시 `LlmProviderExhaustedException` → catch 후 L1 fallback 문자열 반환

### 5.2 배치 처리

인덱싱 시 이미지가 많으면 LLM 호출이 급증. 두 가지 완화 전략:

1. **임계값 필터**: 이미지 크기 < 5KB → 아이콘으로 간주 → L0 무시
2. **비동기 후처리**: 인덱싱 시 이미지만 저장 (동기), Vision 설명은 백그라운드 처리 후 메타데이터 업데이트 (비동기)

---

## 6. OCR 처리 (L3)

> **구현 완료** — `service/OcrService.java`, `pom.xml` tess4j:5.11.0 추가.

스캔 PDF 또는 텍스트 위주 스크린샷에 적용.

### 6.1 의존성

```xml
<dependency>
    <groupId>net.sourceforge.tess4j</groupId>
    <artifactId>tess4j</artifactId>
    <version>5.11.0</version>
</dependency>
```

### 6.2 OcrService 구현

```java
@Service
@ConditionalOnProperty(name = "app.image-description.ocr-enabled", havingValue = "true")
public class OcrService {

    private final String tessdataPath;  // AppProperties에서 주입

    public String extractText(BufferedImage image) {
        Tesseract tesseract = new Tesseract();  // 호출마다 새 인스턴스 (thread-safe 아님)
        if (tessdataPath != null && !tessdataPath.isBlank()) {
            tesseract.setDatapath(tessdataPath);
        }
        tesseract.setLanguage("kor+eng");
        return tesseract.doOCR(image);
    }
}
```

> **주의**: `Tesseract` 인스턴스는 thread-safe하지 않으므로 호출마다 new 생성. `tessdataPath`가 blank면 `TESSDATA_PREFIX` 환경변수 또는 시스템 기본 경로를 사용.

**스캔 PDF 처리 연동** (`DocumentLoaderService.loadPdf()`):
1. `PagePdfDocumentReader`로 텍스트 추출 → 페이지의 50% 이상이 50자 미만이면 "스캔 PDF" 판정
2. `ocrService != null`이면 `ocrWithPdfRenderer()` 호출
3. `PDFRenderer.renderImageWithDPI(page, 300, ImageType.RGB)` → `BufferedImage` → `OcrService.extractText()`
4. 추출된 텍스트로 새 `Document` 생성, `source_type=ocr` 태깅

---

## 7. 이미지 서빙 및 검색 결과 연동

> **구현 완료** — AgentState·ChatResponse imageRefs, RetrievalService 수집, ApiController 서빙, message-assistant.html 썸네일 모두 구현됨.

### 7.1 이미지 서빙 엔드포인트

`DocumentController`에 구현됨:

```java
// GET /api/v1/images/{docId}/{filename}
@GetMapping("/api/v1/images/{docId}/{filename}")
public ResponseEntity<Resource> getImage(
        ThreadContext ctx,
        @PathVariable String docId,
        @PathVariable String filename) {

    // Path traversal 방어: docId, filename에 ".." "/" "\" 불허
    if (docId.contains("..") || docId.contains("/") || docId.contains("\\")
            || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
        return ResponseEntity.badRequest().build();
    }

    Path imgPath = ragService.userDocumentsDir(ctx.userId())
            .getParent().resolve("images").resolve(docId).resolve(filename);

    if (!Files.exists(imgPath)) return ResponseEntity.notFound().build();

    String contentType = Files.probeContentType(imgPath);
    if (contentType == null) contentType = "application/octet-stream";
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
            .header("X-Robots-Tag", "noindex, nofollow")
            .body(new FileSystemResource(imgPath));
}
```

### 7.2 AgentState 확장

```java
public record AgentState(
    // ... 기존 필드 ...
    List<String> imageRefs      // 검색 결과 청크에서 수집한 이미지 경로 목록
) {
    public AgentState {
        // ... 기존 방어 복사 ...
        imageRefs = imageRefs == null ? List.of() : List.copyOf(imageRefs);
    }

    public AgentState withImageRefs(List<String> imageRefs) {
        return new AgentState(question, version, threadId, questionType,
                retrievedDocs, sources, retrievalWarnings, imageRefs,
                answer, retryCount, needsRetry, conversationHistory,
                totalInputTokens, totalOutputTokens, llmCallCount);
    }
}
```

### 7.3 RetrievalService — imageRefs 수집

`execute()` 메서드 내 sources 수집 직후:

```java
List<String> imageRefs = unique.stream()
        .flatMap(d -> {
            Object val = d.getMetadata().get("image_paths");
            if (val instanceof List<?> list) {
                return list.stream().map(Object::toString);
            }
            return Stream.empty();
        })
        .distinct()
        .toList();

return state
        .withRetrievedDocs(unique)
        .withSources(sources)
        .withImageRefs(imageRefs)
        .withRetrievalWarnings(warnings)
        .withNeedsRetry(false);
```

### 7.4 ChatResponse 확장

```java
public record ChatResponse(
    String answer,
    @JsonProperty("question_type") String questionType,
    List<SourceRef> sources,
    @JsonProperty("image_refs") List<String> imageRefs,
    @JsonProperty("total_input_tokens") int totalInputTokens,
    @JsonProperty("total_output_tokens") int totalOutputTokens,
    @JsonProperty("llm_call_count") int llmCallCount,
    @JsonProperty("elapsed_seconds") double elapsedSeconds,
    @JsonProperty("premium_upgraded") String premiumUpgraded,   // PROGRESSIVE: 업그레이드된 PREMIUM provider명
    @JsonProperty("used_provider") String usedProvider,
    @JsonProperty("dual_local_answer") String dualLocalAnswer,   // DUAL: LOCAL 모델 답변
    @JsonProperty("dual_local_provider") String dualLocalProvider
) {}
```

`AgentService`에서 `ChatResponse` 생성 시 `state.imageRefs()` 전달.

### 7.5 Web UI 표시

`message-assistant.html` 프래그먼트에 이미지 섹션 추가:

```html
<!-- th:if="${not #lists.isEmpty(imageRefs)}" -->
<div class="message-images mt-2" th:if="${imageRefs != null and not #lists.isEmpty(imageRefs)}">
    <small class="text-muted">관련 이미지</small>
    <div class="d-flex flex-wrap gap-2 mt-1">
        <a th:each="ref : ${imageRefs}"
           th:href="@{'/api/images/' + ${ref}}"
           target="_blank">
            <img th:src="@{'/api/images/' + ${ref}}"
                 class="img-thumbnail"
                 style="max-height: 120px; cursor: pointer;"
                 loading="lazy" />
        </a>
    </div>
</div>
```

REST API 응답 예시:
```json
{
  "answer": "...",
  "sources": ["manual.pdf | vlatest | p.5"],
  "image_refs": [
    "manual.pdf_a1b2c3d4/p5_img0.png",
    "manual.pdf_a1b2c3d4/p5_img1.png"
  ]
}
```

클라이언트는 `/api/images/{image_refs[i]}` 로 이미지를 직접 요청합니다.

---

## 8. 문서 삭제 시 이미지 정리

> **구현 완료** — `RagService.deleteByDocId()`에서 `deleteImagesQuietly()` 호출로 `data/images/{docId}/` 전체 삭제.

`RagService.deleteByDocId()` 구현:

```java
private void deleteByDocId(String docId, String version) {
    DocRegistryEntry existing = registry.get(docId);
    if (existing != null && !existing.springDocIds().isEmpty()) {
        VectorStore store = vectorStoreRegistry.getStore(version);
        store.delete(existing.springDocIds());
    }
    // 이미지 디렉터리 정리
    Path imgDir = dataDir.resolve("images").resolve(docId);
    if (Files.exists(imgDir)) {
        try (Stream<Path> files = Files.walk(imgDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }
}
```

---

## 9. 설정

`application.properties` 추가:

```properties
# 이미지 처리 모드 (strip | describe)
app.image-description.mode=strip

# Vision 파이프라인 활성화 (LazyVisionService, ImageTypeClassifier)
app.image-description.enabled=true

# PDF OCR 활성화 (OcrService)
app.image-description.ocr-enabled=true

# Tesseract tessdata 경로 — 미설정 시 TESSDATA_PREFIX 환경변수 또는 시스템 기본 경로 사용
# app.image-description.tessdata-path=/usr/share/tesseract-ocr/5/tessdata

# 이미지 크기 필터 — 이 값 미만(bytes) 이미지는 L0(무시)
app.image-description.min-image-bytes=1000

# Lazy Vision: true=검색 시점 생성+캐시, false=인덱싱 시점 동기 생성
app.image-description.lazy=true

# 이미지 유형 분류기 활성화 여부 — Vision 호출이 2회로 증가
app.image-description.classify-type=true

# DOCX EMF→PNG 변환 (Batik — 추가 설치 불필요)
app.image-description.docx-emf-convert=true

# DOCX WMF→PNG 변환 (LibreOffice headless 필요)
app.image-description.docx-wmf-convert=false
```

`AppProperties` 레코드 확장:

```java
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    String dataDir,
    int maxRetryCount,
    int maxConversationChars,
    int chunkSize,
    int chunkOverlap,
    int searchTopK,
    ImageDescriptionProperties imageDescription
) {
    public record ImageDescriptionProperties(
        String mode,                 // none | strip | vision | ocr
        boolean enabled,
        boolean ocrEnabled,
        String tessdataPath,
        int minImageBytes,
        boolean lazy,                // 12절
        boolean classifyType,        // 13절
        boolean docxEmfConvert,      // 14절
        boolean docxWmfConvert       // 14절
    ) {}
}
```

### 9.1 옵션 상세

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `mode` | `strip` | 이미지 참조 처리 방식. `strip`: 이미지 마커 제거(L0). `describe`: Vision 설명 포함(L2). 현재 코드에서 실질적 분기는 `enabled` 플래그가 담당하므로 이 값은 참고용 |
| `enabled` | `true` | `true`이면 `LazyVisionService`와 `ImageTypeClassifier` 빈이 등록됨. `false`이면 이미지는 추출·저장되지만 검색 시 Vision 설명이 프롬프트에 합성되지 않음 |
| `ocr-enabled` | `true` | `true`이면 `OcrService` 빈이 등록됨. 스캔 PDF 판정(페이지 텍스트 50자 미만) 시 Tesseract OCR로 텍스트를 추출하여 청크에 `source_type=ocr` 태깅. Tesseract(`tesseract-ocr`, `tesseract-ocr-data-kor`)가 시스템에 설치되어 있어야 함 |
| `tessdata-path` | _(없음)_ | Tesseract tessdata 디렉터리 절대 경로. 미설정 시 `TESSDATA_PREFIX` 환경변수 → 시스템 기본 경로(`/usr/share/tesseract-ocr/…`) 순으로 탐색. Docker 이미지에서는 `apk add tesseract-ocr-data-kor` 설치 후 미설정해도 동작 |
| `min-image-bytes` | `1000` | 이 크기(bytes) 미만 이미지는 아이콘·구분선으로 간주하여 L0(무시) 처리. Vision 호출 낭비 방지. 값을 높이면 더 많은 이미지가 무시되고, `0`으로 설정하면 모든 이미지를 처리 |
| `lazy` | `true` | `true`: 검색 시점에 Vision 설명 생성(Lazy Vision, 12절). 인덱싱 시 LLM 호출 없이 이미지만 저장, 첫 검색 시 캐시 미스 이미지만 Vision 호출 후 SQLite 캐시. `false`: 인덱싱 시점에 모든 이미지를 즉시 Vision으로 설명(동기 L2). 대량 문서 인덱싱 시 LLM 호출이 폭주하므로 기본 `true` 유지 권장 |
| `classify-type` | `true` | `true`: 이미지를 Vision으로 먼저 분류(`diagram`·`screenshot`·`chart`·`photo`·`other`) 후 유형별 전용 프롬프트로 설명 생성(13절). 이미지당 LLM 호출이 2회로 증가. 비용 절감 시 `false`로 설정 |
| `docx-emf-convert` | `true` | `true`: DOCX 내 EMF(Enhanced Metafile) 이미지를 Apache Batik으로 PNG 변환 후 저장. 변환 실패 시 원본 `.emf` 보존. Batik 의존성은 `pom.xml`에 이미 포함되어 있어 별도 설치 불필요 |
| `docx-wmf-convert` | `false` | `true`: DOCX 내 WMF(Windows Metafile) 이미지를 LibreOffice headless로 PNG 변환(14절). `soffice` 명령이 PATH에 있어야 하며 변환 타임아웃은 20s. LibreOffice 미설치 환경에서는 `false`로 유지하면 WMF 원본만 저장되고 Vision 설명은 건너뜀 |

### 9.2 권장 설정 시나리오

**기본 (이미지 무시, 빠른 인덱싱)**
```properties
app.image-description.enabled=false
app.image-description.ocr-enabled=false
```

**Vision 설명 활성화 (Lazy, 비용 최소)**
```properties
app.image-description.enabled=true
app.image-description.lazy=true
app.image-description.min-image-bytes=1000
app.image-description.classify-type=false
```
> Vision 프로바이더(`VISION` 또는 `BOTH` type) 가 `app.llm.providers`에 등록되어 있어야 함.

**OCR 포함 (스캔 PDF 지원)**
```properties
app.image-description.enabled=true
app.image-description.ocr-enabled=true
# app.image-description.tessdata-path=/usr/share/tesseract-ocr/5/tessdata
```

**DOCX 메타파일 변환 포함 (완전 처리)**
```properties
app.image-description.enabled=true
app.image-description.docx-emf-convert=true   # Batik — 추가 설치 불필요
app.image-description.docx-wmf-convert=true   # LibreOffice 설치 필요
```

---

## 10. 구현 완료 현황

> 모든 항목 구현 완료. Lazy Vision이 기본 동작(`app.image-description.lazy=true`).

| 항목 | 구현 파일 | 상태 |
|------|-----------|------|
| VisionDescriptionService | `service/VisionDescriptionService.java` | ✅ |
| MD 이미지 정제 (L1) | `DocumentLoaderService.preprocessMarkdown()` | ✅ |
| 이미지 추출 저장 | `ImageExtractorService`, `PdfImageExtractor`, `PptxImageExtractor`, `DocxToMarkdownConverter` | ✅ |
| AgentState·ChatResponse 확장 | `AgentState.imageRefs`, `ChatResponse.imageRefs`, `RetrievalService`, `AgentService` | ✅ |
| 이미지 서빙 엔드포인트 | `ApiController GET /api/images/{docId}/{filename}` | ✅ |
| `image_descriptions` 캐시 | `ImageDescriptionRepository`, SQLite `image_descriptions` 테이블 | ✅ |
| LazyVisionService + RetrievalService 통합 | `LazyVisionService`, `RetrievalService.augmentWithDescriptions()` | ✅ |
| ImageTypeClassifier + 유형별 프롬프트 | `ImageTypeClassifier`, `VisionDescriptionService.PROMPTS` | ✅ |
| PPTX 이미지 추출 (Lazy Vision 연동) | `PptxImageExtractor` | ✅ |
| DOCX Vision 설명 (Lazy) + EMF 변환 | `DocxToMarkdownConverter`, `EmfToPngConverter` | ✅ |
| PDF 스캔 OCR (L3) | `OcrService`, `DocumentLoaderService.ocrWithPdfRenderer()` | ✅ |
| Web UI 이미지 썸네일 | `message-assistant.html` 이미지 그리드 | ✅ |
| PDF 일반 이미지 (Lazy) | `PdfImageExtractor` | ✅ |
| DOCX WMF 변환 (LibreOffice fallback) | `LibreOfficeConverter` | ✅ |

---

## 11. 제약 및 주의사항

- **Vision LLM 라우팅**: L2 사용 시 `LlmRouter.route(TaskType.VISION)` 호출 → gemma4(LIGHT_BOTH)가 priority=0으로 기본 처리. gemma4 미지원 모델로 교체하는 경우 `app.llm.providers[0].type=LIGHT_TEXT`로 변경하면 VISION 태스크는 gemini-1(BOTH)로 자동 라우팅됨
- **인덱싱 시간 증가**: L2 적용 시 이미지가 많은 문서는 청크당 1회 + 이미지당 1회 LLM 호출 발생 — 12절 Lazy Vision으로 대폭 완화 가능
- **Path Traversal 방어**: `/api/images/{docId}/{filename}` 엔드포인트에서 `..`, `/` 포함 입력 차단 필수 (7.1절 코드 포함)
- **이미지 저장 용량**: `data/images/` 하위 파일은 문서 삭제 시 반드시 함께 정리 (8절 참조)
- **WMF/EMF 포맷** (DOCX에서 발생): Java 표준 `ImageIO`가 지원하지 않으므로 Apache Batik 또는 외부 변환 필요 (14절 상세)

---

## 12. Lazy Vision — 검색 시점 설명 생성

> **구현 완료** — `LazyVisionService`, `ImageDescriptionRepository`, `RetrievalService.augmentWithDescriptions()`.

### 12.1 동기 L2 vs Lazy L2

5절(L2)의 기본 설계는 **인덱싱 시점에 모든 이미지를 즉시 LLM으로 설명**합니다.
대량 문서 동기화 시 LLM 호출이 폭주하는 단점이 있어 **Lazy를 기본 동작**으로 채택.
`app.image-description.lazy=false`로 두면 인덱싱 시점 동기 L2로 전환.

| 구분 | 동기 L2 (5절 기본) | Lazy L2 (본 절) |
|------|---------------------|-----------------|
| 인덱싱 시 LLM 호출 | 이미지당 1회 | 0회 (alt·캡션·OCR만) |
| 첫 검색 응답 시간 | 변화 없음 | 검색 결과에 신규 이미지 N개 시 +N×Vision 지연 (캐시 후 0) |
| 최종 검색 품질 | 즉시 최고 | 검색·재방문 누적될수록 동기 L2 수준 수렴 |
| 적합한 환경 | 정적·소규모 코퍼스 | 대량 인덱싱·자주 갱신되는 코퍼스 |

### 12.2 Lazy 흐름

```
인덱싱 시
  ImageExtractorService.extract()
    └─ 이미지 추출 + data/images/{docId}/ 저장
       chunk.metadata["image_paths"] 채움
       chunk.metadata["image_description"]는 비워둠 (또는 alt 텍스트만 채움)

검색 시 (RetrievalService.execute)
  unique chunks 수집 후:
    1. chunks의 image_paths 수집
    2. visionDescriptionCache 에서 캐시된 설명 조회
    3. 캐시 미스 항목만 VisionDescriptionService 호출 (병렬)
    4. 설명을 chunk.text 말미에 동적 합성하여 AnswerService에 전달
       (벡터 임베딩 자체는 변경하지 않음 — 순수 프롬프트 시점 보강)
    5. 새로 생성한 설명은 visionDescriptionCache에 영속 저장
```

### 12.3 캐시 스키마 (SQLite — 기존 memory.db 공유)

```sql
CREATE TABLE IF NOT EXISTS image_descriptions (
    image_path     TEXT PRIMARY KEY,    -- "{docId}/{filename}"
    description    TEXT NOT NULL,
    image_type     TEXT,                -- 13절: diagram | screenshot | photo | chart | other
    created_at     TEXT NOT NULL,
    provider       TEXT NOT NULL        -- 사용된 LLM provider 이름 (모니터링용)
);
```

> **키 선택 이유**: 이미지 byte 해시가 아닌 `image_path`를 키로 쓰는 이유는 동일 이미지를 여러
> 문서가 가져도 docId가 다르면 격리하기 위함입니다. 동일 이미지 dedup은 별도 작업으로 분리.

### 12.4 LazyVisionService

```java
@Service
@ConditionalOnProperty(name = "app.image-description.enabled", havingValue = "true")
public class LazyVisionService {

    private final VisionDescriptionService visionService;
    private final ImageDescriptionRepository repo;
    private final Path dataDir;
    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    /** 검색 결과에서 수집한 image_paths 에 대해 캐시된 설명을 가져오거나 새로 생성한다. */
    public Map<String, String> describeIfNeeded(List<String> imagePaths) {
        // 1) 캐시 일괄 조회
        Map<String, String> cached = repo.findAll(imagePaths);
        List<String> misses = imagePaths.stream()
                .filter(p -> !cached.containsKey(p)).toList();
        if (misses.isEmpty()) return cached;

        // 2) 캐시 미스만 병렬 Vision 호출
        Map<String, String> generated = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = misses.stream()
            .map(path -> CompletableFuture.runAsync(() -> {
                try {
                    Path file = dataDir.resolve("images").resolve(path);
                    String desc = visionService.describe(
                        Files.readAllBytes(file), detectMimeType(file));
                    generated.put(path, desc);
                    repo.save(path, desc, "auto", LlmRouter.lastUsedProvider());
                } catch (Exception e) {
                    log.warn("Lazy vision failed for {}: {}", path, e.getMessage());
                    generated.put(path, "");   // 빈 설명 캐시 — 무한 재시도 방지
                }
            }, executor)).toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<String, String> merged = new HashMap<>(cached);
        merged.putAll(generated);
        return merged;
    }
}
```

### 12.5 RetrievalService 통합

```java
// RetrievalService.execute() 변경 (sources 수집 직후, AnswerService 호출 전)
List<String> imagePaths = unique.stream()
    .flatMap(d -> {
        Object v = d.getMetadata().get("image_paths");
        return (v instanceof List<?> l) ? l.stream().map(Object::toString) : Stream.empty();
    })
    .distinct().toList();

if (!imagePaths.isEmpty() && lazyVision != null) {
    Map<String, String> descByPath = lazyVision.describeIfNeeded(imagePaths);
    // chunk.text 합성: 원본 청크 텍스트 + 해당 이미지 설명들
    List<Document> enriched = unique.stream().map(d -> {
        List<?> paths = (List<?>) d.getMetadata().getOrDefault("image_paths", List.of());
        if (paths.isEmpty()) return d;
        String suffix = paths.stream()
            .map(p -> descByPath.getOrDefault(p.toString(), ""))
            .filter(s -> !s.isBlank())
            .map(s -> "\n[이미지: " + s + "]")
            .collect(Collectors.joining());
        return suffix.isEmpty() ? d
            : new Document(d.getText() + suffix, d.getMetadata());
    }).toList();
    return state.withRetrievedDocs(enriched).withSources(sources)
                .withImageRefs(imagePaths).withNeedsRetry(false);
}
```

### 12.6 장단점 및 고려사항

| 항목 | 내용 |
|------|------|
| 장점 | 인덱싱 시 LLM 호출 폭주 방지, 검색에 도달한 이미지만 비용 발생, 점진적 품질 수렴 |
| 단점 | 첫 검색 응답이 느려짐 — 캐시 미스 N개 시 가장 느린 Vision 호출만큼 추가 지연 |
| 완화책 | (a) 가상 스레드로 병렬 호출, (b) Critic 재검색 시 동일 이미지면 캐시 hit → 0 비용 재호출 |
| 임베딩 미보강 | 설명은 프롬프트 합성 시점에만 추가됨 — 벡터 검색의 recall에는 기여하지 않음 |
| recall 개선 옵션 | 후속 작업으로 캐시된 설명을 다음 인덱싱 사이클에 청크 임베딩에 포함시키는 "warm-up reindex" 스케줄 잡 |
| 빈 설명 캐싱 | 실패한 호출도 빈 문자열로 캐시 — 무한 재시도 폭주 방지. 일정 기간 후 재시도하려면 `created_at` 기반 TTL |
| Circuit Breaker 연계 | `LlmRouter`가 모든 Vision 프로바이더를 차단한 상태면 `describeIfNeeded`는 실패만 캐시 — 차단 해제 후 새 검색에서 자연 복구 |
| 설정 | `app.image-description.lazy=true` (기본값) — false로 두면 5절 동기 L2 동작 |

---

## 13. 이미지 유형 분류기

> **구현 완료** — `ImageTypeClassifier` (`enabled=true` 조건), `VisionDescriptionService.PROMPTS` 맵.

### 13.1 동기

이미지를 모두 동일 프롬프트로 설명하면 다이어그램·사진·코드 스크린샷 등 유형별 특성이 사라집니다.
유형을 먼저 가볍게 분류하고 유형별 전용 프롬프트로 Vision을 호출하면 설명 품질이 향상됩니다.

### 13.2 분류 카테고리

| image_type | 설명 | 전용 프롬프트 핵심 |
|-----------|------|------------------|
| `diagram` | 박스·화살표가 있는 구조도/플로우/시퀀스 | 구성 요소·관계·방향 |
| `screenshot` | UI 캡처, 코드 캡처 | UI 요소 위치, 표시된 텍스트 우선 |
| `chart` | 차트·그래프 (막대·선·파이) | 축·범례·트렌드 |
| `photo` | 일반 사진 | 피사체 중심, 1문장 요약 |
| `other` | 분류 불가 | 일반 설명 |

### 13.3 분류 흐름 (Lazy Vision과 결합)

```
LazyVisionService.describeIfNeeded(imagePath) — 캐시 미스 시:
  1. ImageTypeClassifier.classify(imagePath)          → LIGHT_TEXT는 안 됨 (이미지 필요)
                                                       → 실제로는 LIGHT_BOTH(gemma4)로 빠르게
  2. promptByType.get(type) 선택
  3. VisionDescriptionService.describe(bytes, mime, prompt)
  4. 결과 + type 함께 캐시
```

### 13.4 ImageTypeClassifier

```java
@Service
@ConditionalOnProperty(name = "app.image-description.enabled", havingValue = "true")
public class ImageTypeClassifier {
    // LlmRouter.route(TaskType.LIGHT_BOTH, RoutingMode.COST_FIRST) 사용
    // (VISION 전용 모델이 아닌 범용 멀티모달 모델로 분류 — 빠르고 저비용)
    public String classify(byte[] imageBytes, String mimeType): String
    // 응답값: "diagram" | "screenshot" | "chart" | "photo" | "other"
    // LLM 응답이 VALID_TYPES 외 단어면 "other" fallback
    // 예외 발생 시 "other" 반환 (운영 안정성)
}
```

> **주의**: 설계 문서(13.4)에서는 `TaskType.VISION`으로 명시했으나 실제 구현은 `TaskType.LIGHT_BOTH`를 사용함. 분류 작업은 정밀 Vision 모델보다 저비용 멀티모달 모델로도 충분.

### 13.5 유형별 프롬프트

```java
private static final Map<String, String> PROMPTS = Map.of(
    "diagram",    "이 다이어그램의 구성 요소와 관계, 흐름의 방향을 한국어로 3문장 이내 설명.",
    "screenshot", "이 스크린샷에 표시된 UI 요소와 화면 텍스트를 한국어로 정리. 코드면 언어와 핵심 로직 요약.",
    "chart",      "이 차트의 축·범례·관찰되는 트렌드를 한국어 3문장 이내 요약.",
    "photo",      "이 사진의 피사체와 상황을 한국어로 1문장 요약.",
    "other",      "이 이미지를 한국어로 간결히 3문장 이내 설명."
);
```

### 13.6 장단점 및 고려사항

| 항목 | 내용 |
|------|------|
| 장점 | 설명 품질 ↑ (다이어그램은 구조 중심, 차트는 트렌드 중심), 후속 검색 필터에 `image_type` 활용 가능 |
| 단점 | Vision 호출이 이미지당 2회로 증가 — 분류 1회 + 설명 1회 |
| 완화책 | 분류는 작은 비전 모델(gemma4)로 고정, 실제 설명만 큰 모델로 라우팅하도록 `route(TaskType)` 분리 검토 |
| 캐시 | 12.3 `image_descriptions.image_type` 컬럼에 함께 저장 → 재호출 시 분류 비용 0 |
| 분류 실패 | LLM 응답이 카테고리 외 단어면 `other`로 fallback — 운영 안정성 확보 |
| 활용 | Web UI에서 이미지 갤러리에 유형 뱃지 표시 가능 (선택) |

---

## 14. WMF/EMF 변환 (DOCX 한정)

> **구현 완료** — `EmfToPngConverter` (Batik, `docx-emf-convert=true`), `LibreOfficeConverter` (`docx-wmf-convert=true`), `DocxToMarkdownConverter`에 통합.


### 14.1 문제

DOCX의 `XWPFPictureData`는 Word 작성 환경에 따라 WMF/EMF (Windows Metafile) 포맷 이미지를 포함합니다.
Java 표준 `ImageIO`는 WMF/EMF를 디코딩하지 못하므로:
- `data/images/{docId}/d0_img0.wmf` 로 저장은 되지만 브라우저가 표시 불가
- Vision LLM도 WMF를 일반적으로 입력으로 받지 않음 (PNG/JPEG/WEBP 위주)

### 14.2 변환 전략

Apache Batik (SVG·메타파일 변환 라이브러리)을 활용하여 WMF/EMF → PNG로 변환:

```xml
<dependency>
    <groupId>org.apache.xmlgraphics</groupId>
    <artifactId>batik-transcoder</artifactId>
    <version>1.17</version>
</dependency>
<dependency>
    <groupId>org.apache.xmlgraphics</groupId>
    <artifactId>batik-codec</artifactId>
    <version>1.17</version>
</dependency>
```

> Batik 자체는 WMF 직접 디코딩이 약합니다. WMF의 경우 보조로 `freehep-graphicsio-emf` 또는
> 외부 `LibreOffice headless` 변환을 권장합니다. 본 절에서는 EMF 변환 위주로 다루고,
> WMF는 LibreOffice fallback을 명시합니다.

### 14.3 DocxImageExtractor 통합

```java
// DocxImageExtractor.java
private void persistPicture(XWPFPictureData pic, Path imagesDir, int idx) throws IOException {
    String ext = pic.suggestFileExtension();        // png, jpeg, wmf, emf, ...
    byte[] bytes = pic.getData();

    if ("emf".equalsIgnoreCase(ext)) {
        bytes = EmfToPngConverter.convert(bytes);   // Batik / freehep
        ext = "png";
    } else if ("wmf".equalsIgnoreCase(ext)) {
        try {
            bytes = LibreOfficeConverter.convert(bytes, "wmf", "png");
            ext = "png";
        } catch (Exception e) {
            log.warn("WMF conversion failed, keeping original (will be skipped by Vision): {}",
                     e.getMessage());
            // 원본 보존 — 다운스트림에서 ImageTypeClassifier가 'other'로 분류
        }
    }

    String filename = "d0_img" + idx + "." + ext;
    Files.write(imagesDir.resolve(filename), bytes);
}
```

### 14.4 LibreOffice fallback (WMF만)

LibreOffice가 시스템에 설치되어 있다면 가장 견고합니다. `ProcessBuilder` 기반 호출:

```java
public class LibreOfficeConverter {
    public static byte[] convert(byte[] input, String fromExt, String toExt) throws Exception {
        Path tmpDir = Files.createTempDirectory("rag-conv-");
        try {
            Path src = tmpDir.resolve("in." + fromExt);
            Files.write(src, input);
            ProcessBuilder pb = new ProcessBuilder(
                "soffice", "--headless", "--convert-to", toExt,
                "--outdir", tmpDir.toString(), src.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("LibreOffice timeout");
            }
            Path out = tmpDir.resolve("in." + toExt);
            return Files.readAllBytes(out);
        } finally {
            // 재귀 삭제
            try (Stream<Path> s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
    }
}
```

### 14.5 장단점 및 고려사항

| 항목 | 내용 |
|------|------|
| 장점 | DOCX 처리 완결성 ↑, Vision LLM이 PNG로 정상 입력, Web UI에서 모든 이미지 표시 |
| 단점 | Batik 의존성 추가(약 5MB), LibreOffice 사용 시 OS 패키지 설치 전제 |
| 운영 | LibreOffice 미설치 환경에서는 WMF 원본만 보존되고 다운스트림에서 무시 → 시스템 무중단 |
| 보안 | `ProcessBuilder` 호출 시 인자에 사용자 제어 입력 없음 (모두 임시 파일) — command injection 방어됨 |
| 성능 | EMF는 Batik 인메모리 변환으로 수백ms, WMF는 LibreOffice 호출로 수 초 — 인덱싱 처리량 영향 큼 |
| 권장 설정 | `app.image-description.docx-emf-convert=true` (Batik), `app.image-description.docx-wmf-convert=false` (기본 비활성, 운영 환경에서 LibreOffice 확인 후 enable) |

---
