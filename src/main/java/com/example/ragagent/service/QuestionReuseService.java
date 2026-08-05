package com.example.ragagent.service;

import com.example.ragagent.model.MetaKey;
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

    private static final Pattern DIRECTIVE_WORD = Pattern.compile(
            "(?:^|\\s)(이거|그거|저거|이것|그것|저것|여기|거기|저기|얘|걔|쟤|요거|저거요)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HAS_CONCRETE_SIGNAL = Pattern.compile(
            "(?:\\d|[A-Za-z]{3,}|\\.[A-Za-z]{2,4}|[/#:_-]|오류코드|에러코드|클래스|메서드|함수|설정|포트|버전|로그|파일|문서|테이블|컬럼|endpoint|api)",
            Pattern.CASE_INSENSITIVE);

    private final QuestionReuseRepository repository;

    public QuestionReuseService(QuestionReuseRepository repository) {
        this.repository = repository;
    }

    public void recordTurnSources(long turnId, String userId, String threadId, List<Document> retrievedDocs) {
        if (turnId <= 0 || retrievedDocs == null || retrievedDocs.isEmpty()) return;
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
            rows.add(new QuestionReuseRepository.SourceSnapshot(chunkId, docId, hash));
        }
        repository.saveTurnSourceRefs(turnId, userId, threadId, rows);
    }

    public void cloneTurnSources(long fromTurnId, long toTurnId, String userId, String threadId) {
        if (fromTurnId <= 0 || toTurnId <= 0) return;
        repository.cloneTurnSourceRefs(fromTurnId, toTurnId, userId, threadId);
    }

    public void markChunkDeleted(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return;
        repository.markSourceRefsInactiveByChunkIds(List.of(chunkId));
    }

    public void markChunksDeleted(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        List<String> normalized = chunkIds.stream()
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .toList();
        if (normalized.isEmpty()) return;
        repository.markSourceRefsInactiveByChunkIds(normalized);
    }

    public String currentChunkHash(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return "";
        return repository.currentChunkHashes(Set.of(chunkId)).getOrDefault(chunkId, "");
    }

    public void invalidateChunkIfHashChanged(String chunkId, String previousHash) {
        if (chunkId == null || chunkId.isBlank()) return;
        if (previousHash == null || previousHash.isBlank()) return;
        String current = currentChunkHash(chunkId);
        if (current.isBlank() || !current.equals(previousHash)) {
            repository.markSourceRefsInactiveByChunkIds(List.of(chunkId));
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

    public ValidationResult validateTurn(long turnId) {
        List<QuestionReuseRepository.SourceSnapshot> refs = repository.findSourceRefs(turnId);
        if (refs.isEmpty()) {
            return ValidationResult.invalid("출처 청크가 없어 재사용할 수 없습니다.");
        }
        Set<String> ids = refs.stream().map(QuestionReuseRepository.SourceSnapshot::chunkId).collect(java.util.stream.Collectors.toSet());
        Map<String, String> current = repository.currentChunkHashes(ids);
        for (QuestionReuseRepository.SourceSnapshot ref : refs) {
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
