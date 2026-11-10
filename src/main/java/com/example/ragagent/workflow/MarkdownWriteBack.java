package com.example.ragagent.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 변환 MD 되쓰기(§6.33 4b-2) — 청크를 고친 수정을 {@code converted/{docId}_corrected.md}(없으면 {@code {docId}.md})에도 반영한다. 문서 단위
 * 재인덱싱은 청크를 이 MD 에서 다시 만들므로, 되쓰지 않으면 다음 재인덱싱이 수정을 되돌린다.
 *
 * <p><b>구간 일치에 기대지 않는다.</b> 청크에는 부모 제목·{@code (2)} 제목·이어짐 마커·표 머리글이 더해져 MD 본문과 다르다. 그래서 청크 텍스트로
 * MD 를 찾지 않고 <b>바꿀 말의 발생 수</b>만 대조한다: ① 이 문서의 어느 청크에도 바꿀 말이 남아 있지 않아야 하고(일부만 고친 문서에서 MD 만 바꾸면
 * 둘이 갈라진다) ② MD 의 발생 수가 1 이상이며 ③ 적용한 청크들에서 센 발생 수 이하여야 한다(청크는 겹침 구간 때문에 같은 자리를 두 번 셀 수는 있어도
 * MD 의 자리를 빠뜨리지는 않는다). 하나라도 어긋나면 <b>건드리지 않고</b> {@link #CONFLICT} 로 남긴다. 임시 파일에 쓴 뒤 원자적으로 옮긴다.
 *
 * <p>MD 가 없으면({@code MD_MISSING} — 스캔 PDF 처럼 변환 MD 를 만들지 않는 문서) 아무것도 하지 않는다. 이 되쓰기는 <b>원본 파일(DOCX/PDF)은 고치지
 * 않는다</b> — 원본을 다시 올리면 이 수정은 사라진다.
 */
public final class MarkdownWriteBack {

    public static final String APPLIED = "applied";
    public static final String CONFLICT = "MD_CONFLICT";
    public static final String MISSING = "MD_MISSING";

    public record Result(String status, int replaced, int mdOccurrences, String detail) {}

    private MarkdownWriteBack() {}

    /**
     * @param chunkOccurrences 이번에 적용한 청크들의 수정 전 본문에서 센 {@code from} 의 발생 수 합
     * @param noChunkLeftWithTerm 적용 뒤 이 문서의 어느 청크에도 {@code from} 이 남아 있지 않은가
     */
    public static Result apply(Path convertedDir, String docId, String from, String to,
                               int chunkOccurrences, boolean noChunkLeftWithTerm) {
        Path md = resolve(convertedDir, docId);
        if (md == null) return new Result(MISSING, 0, 0, "변환 MD 없음");
        if (!noChunkLeftWithTerm) return new Result(CONFLICT, 0, 0, "일부 청크에 바꿀 말이 남아 있어 MD 만 바꾸면 청크와 갈라진다");
        try {
            String text = Files.readString(md, StandardCharsets.UTF_8);
            int count = occurrences(text, from);
            if (count == 0) return new Result(CONFLICT, 0, 0, "MD 에 바꿀 말이 없다(MD 가 청크와 다르다)");
            if (count > chunkOccurrences) {
                return new Result(CONFLICT, 0, count, "MD 의 발생 수(" + count + ")가 청크에서 센 수(" + chunkOccurrences + ")보다 많다");
            }
            Path tmp = md.resolveSibling(md.getFileName() + ".tmp");
            Files.writeString(tmp, text.replace(from, to), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, md, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, md, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(APPLIED, count, count, "");
        } catch (IOException e) {
            return new Result(CONFLICT, 0, 0, "MD 를 쓰지 못했다: " + e.getMessage());
        }
    }

    /** 재인덱싱이 읽는 MD 와 같은 우선순위 — 교정본 먼저, 없으면 원본 변환본. */
    static Path resolve(Path convertedDir, String docId) {
        Path corrected = convertedDir.resolve(docId + "_corrected.md");
        if (Files.exists(corrected)) return corrected;
        Path raw = convertedDir.resolve(docId + ".md");
        return Files.exists(raw) ? raw : null;
    }

    /** 겹치지 않는 발생 수. */
    static int occurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) return 0;
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
