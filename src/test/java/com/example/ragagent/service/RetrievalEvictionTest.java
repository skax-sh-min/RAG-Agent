package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재시도 청크 교체의 세 가드.
 *
 * <p>"버려야 할 것을 버리는가"보다 <b>"버리면 안 되는 것을 남기는가"</b>가 이 클래스의 본체다 —
 * 근거를 잘못 버리면 재시도가 직전 시도보다 나빠지고, 그 손실은 화면에 실패로 드러나지도 않는다.
 */
class RetrievalEvictionTest {

    private static List<Document> docs(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> new Document("id-" + i, "본문 " + i, Map.<String, Object>of()))
                .toList();
    }

    private static Set<String> select(List<Document> docs, List<Integer> used, boolean truncated) {
        return RetrievalEviction.select(docs, used, truncated);
    }

    // ── 정상 동작 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("근거로 안 쓰인 하위 청크를 뒤에서부터 최대 1/3 까지 밀어낸다")
    void evictsUnusedBottomChunks() {
        List<Document> ten = docs(10);            // 최대 3개, 하위 구간은 index 5..9

        Set<String> evicted = select(ten, List.of(1, 2, 3), false);

        assertThat(evicted).containsExactly("id-9", "id-8", "id-7");   // 뒤에서부터
    }

    @Test
    @DisplayName("하위 구간이라도 근거로 쓰인 청크는 남는다")
    void keepsUsedChunksEvenInTheBottomRange() {
        List<Document> ten = docs(10);

        Set<String> evicted = select(ten, List.of(1, 9, 10), false);   // D9, D10 = index 8, 9

        assertThat(evicted).doesNotContain("id-8", "id-9");
        assertThat(evicted).containsExactly("id-7", "id-6", "id-5");
    }

    @Test
    @DisplayName("상위 절반은 근거로 안 쓰였어도 밀어내지 않는다 — 신호 하나로는 버리지 않는다")
    void neverEvictsFromTheTopHalf() {
        List<Document> six = docs(6);             // 최대 2개(6/3), 하위 구간은 index 3..5

        Set<String> evicted = select(six, List.of(6), false);   // D6 = index 5 만 사용

        assertThat(evicted).containsExactly("id-4", "id-3");
        assertThat(evicted).doesNotContain("id-0", "id-1", "id-2");   // 상위 절반은 손대지 않는다
    }

    // ── 가드 ① 빈 usedDocIndices = "모른다" ────────────────────────────────

    @Test
    @DisplayName("usedDocIndices 가 비면 아무것도 밀어내지 않는다 — '전부 미사용'이 아니라 '판정 없음'이다")
    void emptyUsedIndicesMeansUnknownNotUnused() {
        assertThat(select(docs(10), List.of(), false)).isEmpty();
        assertThat(select(docs(10), null, false)).isEmpty();
    }

    // ── 가드 ② 발췌가 잘린 시도 ────────────────────────────────────────────

    @Test
    @DisplayName("검증 발췌가 잘렸으면 밀어내지 않는다 — 뒤쪽은 '안 쓰인' 것이 아니라 '보이지도 않은' 것이다")
    void truncatedExcerptsSuppressEviction() {
        assertThat(select(docs(10), List.of(1, 2), true)).isEmpty();
    }

    // ── 가드 ③ 1순위 보존 ─────────────────────────────────────────────────

    @Test
    @DisplayName("1순위는 어떤 경우에도 밀어내지 않는다")
    void neverEvictsTheTopRankedChunk() {
        for (int n = 2; n <= 12; n++) {
            assertThat(select(docs(n), List.of(n), false))
                    .as("문서 %d개", n)
                    .doesNotContain("id-0");
        }
    }

    @Test
    @DisplayName("문서가 너무 적으면 밀어낼 것이 없다 (1/3 이 0)")
    void tooFewDocumentsMeansNoEviction() {
        assertThat(select(docs(2), List.of(2), false)).isEmpty();
        assertThat(select(docs(1), List.of(1), false)).isEmpty();
        assertThat(select(List.of(), List.of(1), false)).isEmpty();
    }

    // ── 후보 필터 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("밀려난 청크는 후보에서 빠지고, 나머지 순서는 그대로다")
    void withoutExcluded_removesAndPreservesOrder() {
        List<Document> five = docs(5);

        List<Document> kept = RetrievalEviction.withoutExcluded(five, Set.of("id-1", "id-3"));

        assertThat(kept).extracting(Document::getId).containsExactly("id-0", "id-2", "id-4");
    }

    @Test
    @DisplayName("제외가 후보 전체를 덮어도 최소 1개는 남긴다 — 검색이 성공했는데 '문서 없음'이 되면 안 된다")
    void withoutExcluded_neverReturnsEmpty() {
        List<Document> three = docs(3);

        List<Document> kept = RetrievalEviction.withoutExcluded(three, Set.of("id-0", "id-1", "id-2"));

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getId()).isEqualTo("id-0");   // 최상위를 남긴다
    }

    @Test
    @DisplayName("제외 목록이 비면 후보는 그대로 통과한다")
    void withoutExcluded_emptyExclusionIsIdentity() {
        List<Document> five = docs(5);
        assertThat(RetrievalEviction.withoutExcluded(five, Set.of())).isSameAs(five);
    }

    // ── §6.30 A — 보낸 집합 안에서만 ───────────────────────────────────────

    private static List<String> keys(List<Document> docs) {
        return docs.stream().map(Document::getId).toList();
    }

    @Test
    @DisplayName("§6.30 A — 다 보냈으면 예전 select 와 같다")
    void amongSent_allSentMatchesSelect() {
        List<Document> ten = docs(10);
        assertThat(RetrievalEviction.selectAmongSent(ten, keys(ten), List.of(1, 2, 3), 10))
                .isEqualTo(select(ten, List.of(1, 2, 3), false));
        assertThat(RetrievalEviction.selectAmongSent(ten, null, List.of(1, 2, 3), 10))
                .isEqualTo(select(ten, List.of(1, 2, 3), false));
    }

    @Test
    @DisplayName("§6.30 A — 답변 프롬프트에 실리지 않은 문서는 밀어내지 않는다(보이지도 않았다)")
    void amongSent_unsentDocsAreNotEvicted() {
        List<Document> ten = docs(10);
        List<String> sent = keys(ten.subList(0, 6));   // 사다리가 하위 4개를 뺐다

        Set<String> evicted = RetrievalEviction.selectAmongSent(ten, sent, List.of(1, 2), 10);

        assertThat(evicted).doesNotContain("id-6", "id-7", "id-8", "id-9")
                .as("보낸 6개 안에서 — 하위 절반(3..5) 에서 최대 2개").containsExactly("id-5", "id-4");
    }

    @Test
    @DisplayName("§6.30 A — 가드 ②는 '검증이 보낸 문서를 전부 봤는가'로 묻는다")
    void amongSent_guardTwoLooksAtTheSentDocsOnly() {
        List<Document> ten = docs(10);
        List<String> sent = keys(ten.subList(0, 6));

        assertThat(RetrievalEviction.selectAmongSent(ten, sent, List.of(1, 2), 6))
                .as("검증 발췌는 6개에서 잘렸지만 보낸 것은 전부 봤다 — 밀어낸다").isNotEmpty();
        assertThat(RetrievalEviction.selectAmongSent(ten, sent, List.of(1, 2), 5))
                .as("보낸 문서 하나를 검증이 못 봤다 — 밀지 않는다").isEmpty();
    }

    @Test
    @DisplayName("§6.30 A — [Dn] 은 검증 발췌의 번호다: 보낸 집합이 접두사가 아니어도 문서로 맞춘다")
    void amongSent_mapsEvalNumbersThroughDocuments() {
        List<Document> six = docs(6);
        // 재시도의 뺄 순서로 index 1 이 빠지고 나머지가 실렸다 — 위치로 맞추면 [D6] 이 보낸 집합의 6번째(없음)를 가리킨다
        List<String> sent = List.of("id-0", "id-2", "id-3", "id-4", "id-5");

        Set<String> evicted = RetrievalEviction.selectAmongSent(six, sent, List.of(1, 6), 6);

        assertThat(evicted).as("[D6] = id-5 는 쓰였다 — 하위 구간에서 안 쓰인 id-4 만").containsExactly("id-4");
    }
}
