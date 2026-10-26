package com.example.ragagent.model;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * §6.30 — 한 답변 시도가 <b>실제로 보낸</b> 프롬프트의 모양: 실린 문서, 이력을 얼마나 줄였는가, 그리고 그 답변을 평가한
 * 검증이 근거로 썼다고 보고한 문서.
 *
 * <p><b>재시도의 판단은 모델이 실제로 본 것으로 한다</b>(PLAN §6.30 "검증 실패 재시도와의 관계"). 검색 결과
 * ({@code AgentState.retrievedDocs})는 사다리가 줄이기 <b>전</b>의 목록이라, 그것으로 판단하면 프롬프트에 실리지도 않은
 * 문서가 "안 쓰임"으로 밀려나고(교체), 검색 결과만 바뀌고 모델이 본 문서는 같은 재시도가 멈추지 않는다(조기 종료). 예전에는
 * 이 사실이 출처의 {@code 미사용} 표시({@code SourceRef.promptExcluded})로만 남아 재시도가 읽을 수 없었다.
 *
 * @param docKeys     실린 문서의 키({@link #keyOf}), 순위 순
 * @param historyCut  이력을 어떻게 줄였는가 — 재시도는 이보다 더 줄이지 않는다(규칙 C)
 * @param usedDocKeys 이 답변을 평가한 검증이 근거로 썼다고 보고한 문서의 키. 빈 리스트 = 모름(검증 전·판정 없음·C 모드)
 */
public record SentPrompt(List<String> docKeys, HistoryCut historyCut, List<String> usedDocKeys) {

    public SentPrompt {
        docKeys     = docKeys     == null ? List.of() : List.copyOf(docKeys);
        usedDocKeys = usedDocKeys == null ? List.of() : List.copyOf(usedDocKeys);
        if (historyCut == null) historyCut = HistoryCut.NONE;
    }

    public SentPrompt withUsedDocKeys(List<String> keys) {
        return new SentPrompt(docKeys, historyCut, keys);
    }

    /**
     * 문서의 식별 키 — 벡터 스토어 id, 없으면 파일명+페이지, 그것도 없으면 빈 문자열. 교체({@code RetrievalEviction})와
     * 조기 종료({@code AgentState.rejectionKey})가 같은 키로 문서를 맞춘다.
     */
    public static String keyOf(Document doc) {
        if (doc == null) return "";
        if (doc.getId() != null && !doc.getId().isBlank()) return doc.getId();
        Object file = doc.getMetadata().get(MetaKey.FILENAME);
        Object page = doc.getMetadata().get(MetaKey.PAGE_OR_SLIDE);
        return file == null ? "" : file + "#" + page;
    }

    /**
     * 이력을 어떻게 줄였는가 — 사다리의 이력 칸(1·2·4·5)이 남긴 것. 같은 턴 안에서는 이력 원문이 바뀌지 않으므로 이 값만으로
     * 같은 절단을 다시 만들 수 있다({@code PromptLadder}).
     *
     * @param questionsCapped     칸 1 — 직전 질문을 뺀 과거 질문을 잘랐다
     * @param leadDropped         칸 2 — 턴 앞의 덩어리(요약 블록)를 뺐다
     * @param olderTurnsDropped   칸 2 — 오래된 쪽부터 뺀 턴 수
     * @param lastAnswerDigested  칸 4 — 직전 턴의 답변을 요약으로 바꿨다
     * @param leadLinesDropped    칸 5 — 턴으로 나뉘지 않는 이력(요약 블록뿐)에서 앞쪽부터 뺀 줄 수
     * @param allDropped          칸 5 — 이력을 전부 뺐다
     */
    public record HistoryCut(boolean questionsCapped, boolean leadDropped, int olderTurnsDropped,
                             boolean lastAnswerDigested, int leadLinesDropped, boolean allDropped) {

        public static final HistoryCut NONE = new HistoryCut(false, false, 0, false, 0, false);

        /** 이력의 일부라도 빠졌는가(칸 2·4·5 의 줄) — 칸 1 은 세지 않는다(그 턴은 답변이 대표한다, 안내도 없다). */
        public boolean partlyDropped() {
            return leadDropped || olderTurnsDropped > 0 || lastAnswerDigested || leadLinesDropped > 0;
        }
    }
}
