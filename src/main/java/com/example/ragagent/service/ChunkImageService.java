package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code /admin} 청크 편집의 그림 추가·삭제·교체 — 텍스트 변환({@link ChunkImageEditor})에 파일 저장·경로 검사·Vision 설명을 붙인다.
 *
 * <p>화면은 <b>본문 텍스트를 보내고 바뀐 본문을 받는다</b>. 그림의 자리가 마커로 본문 안에 있으므로 편집이 곧 텍스트 편집이고,
 * 서버는 저장하지 않는다 — 저장은 평소의 청크 「저장」 버튼 하나다(그때 {@code AdminService.updateChunk} 가 {@code image_paths}
 * 메타데이터를 본문의 마커에 맞춘다). 임베딩·키워드 검색에 반영하려면 「이 청크만 재인덱싱」을 누른다 — 설명도 본문의 일부이기 때문이다.
 *
 * <p>클라이언트가 보내는 경로({@code insert}/{@code replace})는 <b>허용된 모양이고 실제로 있는 파일일 때만</b> 마커가 된다
 * ({@link CuratedImageStore#isResolvable}). 본문은 이후 {@code LazyVisionService} 등이 경로를 {@code dataDir} 아래에서 풀기 때문에,
 * 관리자 전용 화면이라도 임의 경로를 마커로 만들 수 있게 두지 않는다.
 */
@Service
public class ChunkImageService {

    private static final Logger log = LoggerFactory.getLogger(ChunkImageService.class);

    /** 편집 요청의 본문 상한 — 청크는 보통 수천 자이고, 관리자 화면이라도 요청 크기는 정해 둔다. */
    static final int MAX_TEXT_CHARS = 200_000;

    /** 화면 목록의 한 행. {@code url} 은 썸네일용({@code /api/v1/images/**} 가 내려준다). */
    public record MarkerView(int index, String path, String url, boolean exists, boolean unconvertible,
                             boolean described, String description) {}

    /** 편집 결과 — 바뀐 본문, 그 본문의 마커 목록, 있으면 사용자에게 말할 경고(설명 생성 실패 등). */
    public record EditResult(String text, List<MarkerView> markers, String warning) {}

    /** 편집 요청. {@code action} 은 insert · replace · remove · describe. */
    public record EditRequest(String action, String text, Integer index, String path, Integer offset, Boolean describe) {}

    private final CuratedImageStore imageStore;
    private final VisionDescriptionService visionService;
    private final AppProperties props;

    public ChunkImageService(CuratedImageStore imageStore, VisionDescriptionService visionService, AppProperties props) {
        this.imageStore = imageStore;
        this.visionService = visionService;
        this.props = props;
    }

    /** 그림 파일을 올리고 마커 경로({@code images/chunks/{sha16}.ext})를 돌려준다. 검사는 {@link CuratedImageStore#store} 와 같다. */
    public String upload(MultipartFile file) throws IOException {
        return imageStore.storeChunkImage(file);
    }

    public static String urlOf(String path) {
        return "/api/v1/" + path;
    }

    public List<MarkerView> markers(String text) {
        return ChunkImageEditor.markers(text).stream().map(m -> new MarkerView(
                m.index(), m.path(), urlOf(m.path()), !m.unconvertible() && imageStore.isResolvable(m.path()),
                m.unconvertible(), m.hasDescription(), m.description())).toList();
    }

    public EditResult edit(EditRequest req) {
        if (req == null || req.action() == null) throw new IllegalArgumentException("action 이 필요합니다");
        String text = req.text() == null ? "" : req.text();
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("본문이 너무 깁니다 (최대 " + MAX_TEXT_CHARS + "자)");
        }
        boolean describe = Boolean.TRUE.equals(req.describe());
        String warning = null;
        String out;
        switch (req.action().toLowerCase(Locale.ROOT)) {
            case "insert" -> {
                requireResolvable(req.path());
                int offset = req.offset() == null ? text.length() : req.offset();
                out = ChunkImageEditor.insert(text, offset, req.path());
                if (describe) {
                    // 방금 넣은 마커를 찾는다 — 같은 경로가 이미 여럿일 수 있어 offset 에 가장 가까운 새 마커가 아니라 "개수가 늘어난 뒤
                    // 설명이 없는 마커 중 offset 이후 첫 것"으로 고른다.
                    int idx = indexOfInserted(out, req.path(), offset);
                    DescribeResult d = describeInto(out, idx, req.path());
                    out = d.text();
                    warning = d.warning();
                }
            }
            case "replace" -> {
                requireResolvable(req.path());
                int idx = requireIndex(req.index());
                out = ChunkImageEditor.replace(text, idx, req.path());
                if (describe) {
                    DescribeResult d = describeInto(out, idx, req.path());
                    out = d.text();
                    warning = d.warning();
                }
            }
            case "remove" -> out = ChunkImageEditor.remove(text, requireIndex(req.index()));
            case "describe" -> {
                int idx = requireIndex(req.index());
                String path = ChunkImageEditor.markers(text).stream().filter(m -> m.index() == idx)
                        .map(ChunkImageEditor.Marker::path).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("이미지 마커를 찾을 수 없습니다 (순번 " + idx + ")"));
                requireResolvable(path);
                DescribeResult d = describeInto(text, idx, path);
                out = d.text();
                warning = d.warning();
            }
            default -> throw new IllegalArgumentException("알 수 없는 작업입니다: " + req.action());
        }
        return new EditResult(out, markers(out), warning);
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private record DescribeResult(String text, String warning) {}

    /** Vision 으로 설명을 만들어 그 마커에 붙인다. 실패해도 편집은 계속된다 — 그림은 이미 들어갔고 설명은 나중에 다시 만들 수 있다. */
    private DescribeResult describeInto(String text, int index, String path) {
        String description = describe(path);
        if (VisionDescriptionService.isPlaceholder(description)) {
            return new DescribeResult(text, "이미지 설명을 만들지 못했습니다 — 그림만 반영했습니다. 나중에 「설명 생성」으로 다시 시도할 수 있습니다.");
        }
        return new DescribeResult(ChunkImageEditor.setDescription(text, index, description), null);
    }

    private String describe(String path) {
        try {
            Path file = Path.of(props.dataDir()).resolve(path);
            return visionService.describe(Files.readAllBytes(file), mimeOf(path));
        } catch (IOException | RuntimeException e) {
            log.warn("[IMAGE] 설명 생성 실패 {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static String mimeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private void requireResolvable(String path) {
        if (!imageStore.isResolvable(path)) {
            throw new IllegalArgumentException("사용할 수 없는 이미지 경로입니다: " + path);
        }
    }

    private static int requireIndex(Integer index) {
        if (index == null) throw new IllegalArgumentException("index 가 필요합니다");
        return index;
    }

    /** 삽입 직후 본문에서 {@code offset} 이후 처음 나오는 {@code path} 마커의 순번 — 없으면 마지막 것. */
    private static int indexOfInserted(String text, String path, int offset) {
        List<ChunkImageEditor.Marker> all = ChunkImageEditor.markers(text);
        for (ChunkImageEditor.Marker m : all) {
            if (m.path().equals(path) && m.start() >= Math.min(offset, text.length())) return m.index();
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).path().equals(path)) return all.get(i).index();
        }
        throw new IllegalStateException("삽입한 마커를 찾을 수 없습니다");
    }
}
