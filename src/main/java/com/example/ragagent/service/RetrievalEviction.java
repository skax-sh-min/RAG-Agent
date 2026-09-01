package com.example.ragagent.service;

import com.example.ragagent.model.MetaKey;
import org.springframework.ai.document.Document;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 검증 실패 재시도에서 <b>어떤 청크를 밀어낼지</b> 정한다 — 순수 클래스(Spring 의존 없음,
 * {@code AnswerAttribution}/{@code ChunkReassembler} 선례).
 *
 * <p><b>왜 밀어내는가.</b> 재시도의 검색 escalation 은 최종 컷을 재시도당 <b>1개</b>만 늘린다
 * ({@code RetrievalService}: {@code topK + retrievalRetries}). 관련 없는 청크 열 개는 그대로 둔 채
 * 열한 번째를 끼워 넣는 셈이라, 새 근거가 프롬프트에 들어가도 이미 자리를 차지한 죽은 무게와
 * 경쟁해야 한다. 자리를 <b>비워</b> 주는 편이 하나 더 붙이는 것보다 낫다.
 *
 * <p><b>신호는 두 개이고, 둘 다 동의할 때만 민다.</b>
 * <ul>
 *   <li>{@code usedDocIndices} — 평가 LLM 이 "근거로 썼다"고 보고한 {@code [Dn]} 번호. 그런데 이건
 *       방금 "부족하다"고 반려된 시도의 <b>자기 보고</b>다. 이것만 믿으면 모델이 활용에 실패했을 뿐
 *       관련 있던 청크를 잃는다.</li>
 *   <li>RRF 순위 — {@code retrievedDocs} 는 융합 점수 내림차순이라 리스트 뒤쪽이 곧 최저 관련도다.</li>
 * </ul>
 *
 * <p><b>반드시 지켜지는 세 가지</b> (각각 실제 사고 모양이 있다):
 * <ol>
 *   <li><b>빈 {@code usedDocIndices} 는 "아무것도 안 썼다"가 아니라 "모른다"</b>. 판정을 읽지
 *       못했거나 발췌가 잘린 경로는 이 값을 빈 리스트로 남긴다 — 그대로 "전부 미사용"으로 읽으면
 *       근거를 통째로 갈아치운다. 비어 있으면 <b>아무것도 밀어내지 않는다</b>.</li>
 *   <li><b>발췌가 잘린 시도에서는 밀어내지 않는다</b>. 크기 상한에 걸려 꼬리가 잘리면 그 문서들은
 *       {@code [Dn]} 번호를 받지 못해 "안 쓰인 것"이 아니라 <b>보이지도 않은 것</b>인데 결과는
 *       미사용과 구별되지 않는다. 그 문서를 버리는 것은 정확히 반대 방향이다. 호출부가
 *       {@code excerptsTruncated} 로 알려 준다.</li>
 *   <li><b>1순위는 어떤 경우에도 남긴다</b>. {@code PromptBudget.fitByPrefix} 가 예산을 넘겨도 첫
 *       항목을 남기는 것과 같은 이유 — 다 버리면 프롬프트가 "문서를 찾을 수 없습니다"가 된다.</li>
 * </ol>
 *
 * <p>적용 대상은 {@code sufficient=false}(질문에 답하지 못함) 재시도뿐이다. {@code grounded=false}
 * 는 답변이 문서 <b>밖으로</b> 나간 경우라 근거를 빼는 것이 방향상 반대다 — 호출부가 그 분기에서만
 * 이 클래스를 부른다.
 */
public final class RetrievalEviction {

    private RetrievalEviction() {}

    /** 한 번에 밀어낼 수 있는 최대 비율 — 3개 중 1개. 넘게 비우면 재시도가 다른 질문이 된다. */
    private static final int MAX_EVICTED_DENOMINATOR = 3;

    /** 하위 신호로 볼 구간 — 리스트 뒤쪽 절반. 앞쪽 절반은 미사용이어도 밀어내지 않는다. */
    private static final int BOTTOM_HALF_DIVISOR = 2;

    /**
     * 직전 시도의 결과에서 밀어낼 청크 id 를 고른다.
     *
     * @param previousDocs      직전 시도가 답변 노드에 넘긴 문서 (RRF 내림차순)
     * @param usedDocIndices    평가가 보고한 1-based {@code [Dn]} 번호. 빈 값 = 모름 → 아무것도 안 민다
     * @param excerptsTruncated 검증 발췌가 크기 때문에 잘렸는가. true → 아무것도 안 민다
     * @return 밀어낼 문서 id 집합 (입력 순서 유지). 조건이 안 맞으면 빈 집합
     */
    public static Set<String> select(List<Document> previousDocs,
                                     List<Integer> usedDocIndices,
                                     boolean excerptsTruncated) {
        if (previousDocs == null || previousDocs.size() < 2) return Set.of();
        if (usedDocIndices == null || usedDocIndices.isEmpty()) return Set.of();   // 가드 ①
        if (excerptsTruncated) return Set.of();                                    // 가드 ②

        int maxEvicted = previousDocs.size() / MAX_EVICTED_DENOMINATOR;
        if (maxEvicted <= 0) return Set.of();
        // 하위 구간의 시작 인덱스. 1순위(index 0)는 이 구간에 절대 들어오지 않는다 — 가드 ③
        int bottomStart = Math.max(1, previousDocs.size() / BOTTOM_HALF_DIVISOR);

        Set<String> evicted = new LinkedHashSet<>();
        // 뒤에서부터 — 가장 관련 없는 것부터 자리를 내준다.
        for (int i = previousDocs.size() - 1; i >= bottomStart && evicted.size() < maxEvicted; i--) {
            if (usedDocIndices.contains(i + 1)) continue;    // 근거로 쓰였다(1-based)
            String id = idOf(previousDocs.get(i));
            if (id != null) evicted.add(id);
        }
        return evicted;
    }

    /**
     * 밀려난 문서를 후보에서 제거한다. 전부 제거되는 일은 없도록 <b>최소 한 개</b>는 남긴다 —
     * {@code excludedIds} 가 어쩌다 후보 전체를 덮더라도(설정이 극단적이거나 코퍼스가 작을 때)
     * 검색이 성공했는데 "문서를 찾을 수 없습니다"가 되는 것이 최악이다.
     */
    public static List<Document> withoutExcluded(List<Document> candidates, Set<String> excludedIds) {
        if (candidates == null || candidates.isEmpty() || excludedIds == null || excludedIds.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<Document> kept = candidates.stream()
                .filter(d -> !excludedIds.contains(idOf(d)))
                .toList();
        return kept.isEmpty() ? candidates.subList(0, 1) : kept;
    }

    /** 청크 식별자 — 벡터 스토어 id 를 쓰고, 없으면 파일명+페이지로 대신한다. */
    static String idOf(Document doc) {
        if (doc == null) return null;
        if (doc.getId() != null && !doc.getId().isBlank()) return doc.getId();
        Object file = doc.getMetadata().get(MetaKey.FILENAME);
        Object page = doc.getMetadata().get(MetaKey.PAGE_OR_SLIDE);
        return file == null ? null : file + "#" + page;
    }
}
