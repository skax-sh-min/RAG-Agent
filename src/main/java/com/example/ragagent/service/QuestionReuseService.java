package com.example.ragagent.service;

import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.repository.QuestionReuseRepository;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class QuestionReuseService {

    private static final int MAX_SUGGESTION_QUESTION_LENGTH = 50;
    private static final String DELETED_REFERENCE_LABEL = "참조 원문 삭제됨";
    private static final String DELETED_REFERENCE_PREVIEW = "원본 대화가 삭제되어 출처 미리보기를 표시할 수 없습니다.";
    private static final String DELETED_CHUNK_PREVIEW = "이 출처 청크는 삭제되어 원문을 표시할 수 없습니다.";

    private static final Pattern DIRECTIVE_WORD = Pattern.compile(
            "(?:^|\\s)(이거|그거|저거|이것|그것|저것|여기|거기|저기|얘|걔|쟤|요거|저거요)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HAS_CONCRETE_SIGNAL = Pattern.compile(
            "(?:\\d|[A-Za-z]{3,}|\\.[A-Za-z]{2,4}|[/#:_-]|오류코드|에러코드|클래스|메서드|함수|설정|포트|버전|로그|파일|문서|테이블|컬럼|endpoint|api)",
            Pattern.CASE_INSENSITIVE);

    private final QuestionReuseRepository repository;
    private final DocRegistry docRegistry;

    public QuestionReuseService(QuestionReuseRepository repository, DocRegistry docRegistry) {
        this.repository = repository;
        this.docRegistry = docRegistry;
    }

    public void recordTurnSources(long turnId, String userId, String threadId, List<Document> retrievedDocs) {
        recordTurnSources(turnId, userId, threadId, retrievedDocs, List.of());
    }

    /**
     * 턴의 출처 스냅샷 저장. {@code sources}는 FINALIZE에서 응답 참여도가 붙은 뒤의
     * {@link SourceRef} 목록으로, 여기서는 청크별 {@code answerShare}를 함께 기록하기 위해서만
     * 쓴다 — 행 자체는 검색된 청크 <em>전부</em>에 대해 남는다(대화 복원 시 출처 배지가 그대로
     * 나와야 하므로). 참여도는 {@link #validateTurn}의 검증 <em>범위</em>만 좁힌다.
     */
    public void recordTurnSources(long turnId, String userId, String threadId,
                                  List<Document> retrievedDocs, List<SourceRef> sources) {
        if (turnId <= 0 || retrievedDocs == null || retrievedDocs.isEmpty()) return;
        Map<String, Double> sharesByChunkId = sharesByChunkId(sources);
        Map<String, String> hashes = repository.currentChunkHashesByDocs(retrievedDocs);
        List<QuestionReuseRepository.SourceSnapshot> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Document doc : retrievedDocs) {
            String chunkId = doc.getId();
            if (chunkId == null || chunkId.isBlank()) continue;
            if (!seen.add(chunkId)) continue;
            String docId = String.valueOf(doc.getMetadata().getOrDefault(MetaKey.DOC_ID, ""));
            String hash = hashes.getOrDefault(chunkId, "");
            if (hash.isBlank()) continue;
            rows.add(new QuestionReuseRepository.SourceSnapshot(
                    chunkId, docId, hash, sharesByChunkId.get(chunkId), "active"));
        }
        repository.saveTurnSourceRefs(turnId, userId, threadId, rows);
    }

    private static Map<String, Double> sharesByChunkId(List<SourceRef> sources) {
        if (sources == null || sources.isEmpty()) return Map.of();
        Map<String, Double> out = new java.util.HashMap<>();
        for (SourceRef ref : sources) {
            if (ref == null || ref.chunkId() == null || ref.chunkId().isBlank()) continue;
            if (ref.answerShare() == null) continue;
            // 같은 청크가 두 번 실릴 일은 없지만, 실리더라도 큰 쪽이 남는 편이 안전하다.
            out.merge(ref.chunkId(), ref.answerShare(), Math::max);
        }
        return out;
    }

    public void cloneTurnSources(long fromTurnId, long toTurnId, String userId, String threadId) {
        if (fromTurnId <= 0 || toTurnId <= 0) return;
        repository.cloneTurnSourceRefs(fromTurnId, toTurnId, userId, threadId);
    }

    public void markChunkDeleted(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return;
        repository.markSourceRefsStaleByChunkIds(List.of(chunkId), SourceRef.STALE_DELETED);
    }

    /**
     * 청크 텍스트만 편집된 경우(재임베딩 없이 {@code AdminService.updateChunk})의 재사용 차단.
     *
     * <p>{@link #invalidateChunkIfHashChanged}로는 잡히지 않는다 — 그 해시는 {@code chunk_fts}에서
     * 계산되는데 텍스트 전용 편집은 FTS 인덱스를 건드리지 않으므로, 저장된 원문이 바뀌었는데도 해시는
     * 그대로다. 즉 편집 직후부터 재인덱싱 전까지 옛 원문 기준의 답변이 계속 재사용될 수 있다.
     */
    public void invalidateChunk(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return;
        repository.markSourceRefsStaleByChunkIds(List.of(chunkId), SourceRef.STALE_MODIFIED);
    }

    public void markChunksDeleted(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        List<String> normalized = chunkIds.stream()
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .toList();
        if (normalized.isEmpty()) return;
        repository.markSourceRefsStaleByChunkIds(normalized, SourceRef.STALE_DELETED);
    }

    public String currentChunkHash(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return "";
        return repository.currentChunkHashes(Set.of(chunkId)).getOrDefault(chunkId, "");
    }

    public void invalidateChunkIfHashChanged(String chunkId, String previousHash) {
        if (chunkId == null || chunkId.isBlank()) return;
        if (previousHash == null || previousHash.isBlank()) return;
        String current = currentChunkHash(chunkId);
        if (current.isBlank()) {
            // 재인덱싱 후 FTS에서 사라졌다 = 이 청크는 더 이상 없다.
            repository.markSourceRefsStaleByChunkIds(List.of(chunkId), SourceRef.STALE_DELETED);
        } else if (!current.equals(previousHash)) {
            repository.markSourceRefsStaleByChunkIds(List.of(chunkId), SourceRef.STALE_MODIFIED);
        }
    }

    public List<Suggestion> suggest(String userId, Scope scope, String q, int limit) {
        String query = q == null ? "" : q.strip();
        if (query.length() < 2) return List.of();

        int fetch = Math.max(limit * 4, 20);
        boolean meOnly = scope == Scope.ME;
        List<QuestionReuseRepository.CandidateTurn> candidates =
                repository.findSuggestionCandidates(query, meOnly, userId, fetch);

        List<Suggestion> out = new ArrayList<>();
        Set<String> seenQuestions = new LinkedHashSet<>();
        for (QuestionReuseRepository.CandidateTurn c : candidates) {
            if (isTooLongForSuggestion(c.question())) continue;
            if (isDirectiveOnlyQuestion(c.question())) continue;
            ValidationResult valid = validateTurn(c.turnId());
            if (!valid.reusable()) continue;
            String key = normalizeQuestionKey(c.question());
            if (!seenQuestions.add(key)) continue;
            out.add(new Suggestion(c.turnId(), c.question(), summarize(c.answer()),
                    scope == Scope.ME ? "me" : "shared"));
            if (out.size() >= limit) break;
        }
        return out;
    }

    public ReuseLookup reuseLookup(String userId, Scope scope, long turnId) {
        boolean meOnly = scope == Scope.ME;
        QuestionReuseRepository.CandidateTurn turn = repository.findTurnForReuse(turnId, meOnly, userId);
        if (turn == null) {
            return ReuseLookup.notReusable("선택한 항목을 찾을 수 없거나 접근 권한이 없습니다.", null);
        }
        ValidationResult valid = validateTurn(turn.turnId());
        if (!valid.reusable()) {
            return ReuseLookup.notReusable(valid.reason(), turn.question());
        }
        List<String> chunkIds = repository.findSourceRefs(turn.turnId()).stream()
            .map(QuestionReuseRepository.SourceSnapshot::chunkId)
            .filter(v -> v != null && !v.isBlank())
            .distinct()
            .toList();
        return ReuseLookup.reusable(turn.turnId(), turn.question(), turn.answer(), turn.threadId(), chunkIds);
    }

    public List<SourceRef> sourceRefsForTurn(long turnId) {
        if (turnId <= 0) return List.of();
        Long sourceTurnId = repository.findReusedFromTurnId(turnId);
        long previewTurnId = sourceTurnId != null ? sourceTurnId : turnId;
        List<QuestionReuseRepository.SourcePreviewRow> rows = repository.findSourcePreviewRows(previewTurnId);
        // § 표시 이름 — one batch lookup for the whole turn instead of one per row; toSourceRef()
        // falls back to the real filename for any docId absent from the map.
        List<String> docIds = rows.stream()
                .map(QuestionReuseRepository.SourcePreviewRow::docId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        Map<String, String> displayNames = docRegistry.findDisplayNames(docIds);
        List<SourceRef> refs = rows.stream()
                .map(row -> toSourceRef(row, displayNames))
                .toList();
        if (!refs.isEmpty()) return refs;
        if (sourceTurnId != null && !repository.existsTurn(sourceTurnId)) {
            return List.of(new SourceRef(
                    DELETED_REFERENCE_LABEL,
                    DELETED_REFERENCE_PREVIEW,
                    null,
                    null,
                    "?"));
        }
        return List.of();
    }

    /**
     * 대화 기록의 한 턴에서 출처 청크 하나를 숨긴다 (채팅 원문 보기 모달의 "현재 대화에서 이 청크
     * 제거"). 표시에서만 사라질 뿐 답변 재사용 검증은 그대로 이 청크를 본다 —
     * {@link QuestionReuseRepository#hideSourceRef} 참고.
     *
     * @return 실제로 숨겨졌으면 {@code true} (이미 숨겨졌거나 그 턴의 출처가 아니면 {@code false})
     */
    public boolean hideSourceForTurn(long turnId, String userId, String threadId, String chunkId) {
        return repository.hideSourceRef(turnId, userId, threadId, chunkId) > 0;
    }

    /**
     * Full untruncated text for one source chunk, for the chat 출처 badge's click-to-expand "원문
     * 보기" modal — unlike {@link #sourceRefsForTurn}'s {@code preview}, which is always capped
     * (§UI 출처 hover 미리보기 길이) for the lightweight hover popover. {@code null} when the chunk
     * no longer exists (deleted/re-indexed since the turn was recorded); callers show a fallback
     * message rather than an empty modal.
     */
    public String chunkFullText(String chunkId) {
        return repository.findChunkFullText(chunkId);
    }

    /**
     * 이 턴의 답변을 지금 다시 내놓아도 되는가.
     *
     * <p><b>검증 대상은 검색된 청크 전부가 아니라 답변에 실제 지분이 있었던 청크</b>다
     * (§2단계 응답 참여도, {@code AnswerAttribution}). topK개가 검색돼도 답변을 실제로 떠받친 건
     * 보통 그중 두세 개이고, 나머지는 한 글자도 답변에 반영되지 않는다 — 그런 청크가 수정됐다는
     * 이유로 멀쩡한 답변을 폐기하면 문서를 손볼 때마다 재사용이 통째로 무력화된다.
     *
     * <p>참여도를 아는 행이 하나도 없으면(컬럼 추가 이전 기록, 귀속이 {@code Method.NONE}으로
     * 끝난 턴) 예전처럼 전체 출처를 검증한다 — 모르는 상태에서 좁히면 조용히 느슨해지므로,
     * 폴백은 항상 엄격한 쪽이어야 한다.
     */
    public ValidationResult validateTurn(long turnId) {
        List<QuestionReuseRepository.SourceSnapshot> all = repository.findAllSourceRefs(turnId);
        if (all.isEmpty()) {
            return ValidationResult.invalid("출처 청크가 없어 재사용할 수 없습니다.");
        }
        List<QuestionReuseRepository.SourceSnapshot> scope = all.stream()
                .filter(QuestionReuseRepository.SourceSnapshot::contributed)
                .toList();
        if (scope.isEmpty()) {
            scope = all.stream()
                    .filter(ref -> ref.answerShare() == null)
                    .toList();
        }
        if (scope.isEmpty()) {
            // 모든 출처의 참여도가 0으로 측정된 턴 — 검증할 근거가 없으니 재사용도 못 한다.
            return ValidationResult.invalid("답변에 반영된 출처가 없어 재사용할 수 없습니다.");
        }
        for (QuestionReuseRepository.SourceSnapshot ref : scope) {
            if (ref.stale()) {
                return ValidationResult.invalid("문서/청크가 삭제 또는 재인덱싱되어 기존 답변을 재사용할 수 없습니다.");
            }
        }
        Set<String> ids = scope.stream().map(QuestionReuseRepository.SourceSnapshot::chunkId).collect(java.util.stream.Collectors.toSet());
        Map<String, String> current = repository.currentChunkHashes(ids);
        for (QuestionReuseRepository.SourceSnapshot ref : scope) {
            String now = current.get(ref.chunkId());
            if (now == null || now.isBlank()) {
                return ValidationResult.invalid("문서/청크가 삭제 또는 재인덱싱되어 기존 답변을 재사용할 수 없습니다.");
            }
            if (!now.equals(ref.chunkHash())) {
                return ValidationResult.invalid("청크 내용이 변경되어 기존 답변을 재사용할 수 없습니다.");
            }
        }
        return ValidationResult.ok();
    }

    static boolean isDirectiveOnlyQuestion(String q) {
        if (q == null) return true;
        String s = q.strip().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return true;
        boolean hasDirective = DIRECTIVE_WORD.matcher(s).find();
        if (!hasDirective) return false;
        boolean hasConcrete = HAS_CONCRETE_SIGNAL.matcher(s).find();
        return !hasConcrete;
    }

    private static String summarize(String answer) {
        if (answer == null || answer.isBlank()) return "";
        String flat = answer.replaceAll("\\s+", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 120) + "..." : flat;
    }

    private static String normalizeQuestionKey(String question) {
        if (question == null) return "";
        return question
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    private SourceRef toSourceRef(QuestionReuseRepository.SourcePreviewRow row, Map<String, String> displayNames) {
        // CuratedQaService.buildDocument() always sets MetaKey.DOC_ID to "curated:<id>", regardless
        // of chunk index — the one stable signal this SQL-sourced row has for "this chunk came from
        // a curated Q&A entry, not a document" (no DOC_TYPE column here, unlike the live Document
        // metadata RetrievalService reads from).
        boolean curated = row.docId() != null && row.docId().startsWith("curated:");
        String filename = (row.filename() == null || row.filename().isBlank())
                ? (row.docId() == null ? "source" : row.docId())
                : row.filename();
        String page = (row.pageOrSlide() == null || row.pageOrSlide().isBlank()) ? "?" : row.pageOrSlide();
        Map<String, Object> meta = new java.util.HashMap<>();
        meta.put(MetaKey.FILENAME, filename);
        meta.put(MetaKey.PAGE_OR_SLIDE, page);
        if (row.docId() != null) {
            meta.put(MetaKey.DOC_ID, row.docId());
        }
        if (curated) {
            meta.put(MetaKey.DOC_TYPE, "curated_qa");
        }
        String chapter = row.chapterNo();
        if (chapter != null && !chapter.isBlank() && !"null".equalsIgnoreCase(chapter)) {
            meta.put(MetaKey.CHAPTER_NO, chapter);
        }
        String label = RetrievalService.formatSource(new Document("", meta), displayNames);
        // Same 요약/참고 stripping as the live-session preview (RetrievalService.previewSource) —
        // otherwise a curated hit's preview flips a "## 요약" section in and out depending only on
        // whether that answer was short enough to embed as a single vector.
        String content = curated ? CuratedTextUtils.stripStructuralSections(row.content()) : row.content();
        String preview = truncate(content, 700);
        String stale = row.status() == null || "active".equals(row.status()) ? null : row.status();
        if (SourceRef.STALE_DELETED.equals(stale) && preview.isBlank()) {
            // 삭제된 청크는 chunk_fts/vec_document_chunks 조인이 비므로 미리보기가 없다. 빈 팝오버
            // 대신 왜 비었는지를 보여준다.
            preview = DELETED_CHUNK_PREVIEW;
        }
        return new SourceRef(label, preview, row.chunkId(), row.docId(), page,
                null, null, null, null, stale);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + " ……";
    }

    private static boolean isTooLongForSuggestion(String question) {
        if (question == null) return true;
        return question.strip().length() > MAX_SUGGESTION_QUESTION_LENGTH;
    }

    public enum Scope {
        ME,
        SHARED;

        public static Scope parse(String raw) {
            if (raw == null || raw.isBlank()) return SHARED;
            return "me".equalsIgnoreCase(raw) ? ME : SHARED;
        }
    }

    public record Suggestion(long turnId, String question, String answerPreview, String scope) {}

    public record ReuseLookup(boolean reusable, String reason, Long sourceTurnId,
                              String question, String answer, String sourceThreadId,
                              List<String> sourceChunkIds) {
        static ReuseLookup reusable(long sourceTurnId, String question, String answer, String sourceThreadId,
                                    List<String> sourceChunkIds) {
            return new ReuseLookup(true, null, sourceTurnId, question, answer, sourceThreadId, sourceChunkIds);
        }

        static ReuseLookup notReusable(String reason, String question) {
            return new ReuseLookup(false, reason, null, question, null, null, List.of());
        }
    }

    public record ValidationResult(boolean reusable, String reason) {
        static ValidationResult ok() { return new ValidationResult(true, null); }
        static ValidationResult invalid(String reason) { return new ValidationResult(false, reason); }
    }
}
