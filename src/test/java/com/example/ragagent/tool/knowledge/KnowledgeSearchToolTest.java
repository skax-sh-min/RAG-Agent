package com.example.ragagent.tool.knowledge;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.service.CrossEncoderReranker;
import com.example.ragagent.service.CrossEncoderReranker.Ranked;
import com.example.ragagent.service.HybridSearchService;
import com.example.ragagent.service.RagService;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** searchKnowledge · findRelatedDocuments — 서버가 정하는 값(버전·호출자), 점수와 낮은 신뢰, 리랭커 장애 시 물러남, 결과 상한. */
class KnowledgeSearchToolTest {

    private HybridSearchService hybrid;
    private CrossEncoderReranker crossEncoder;
    private RagService ragService;
    private AppProperties props;
    private final ToolCaller caller = new ToolCaller("u1", false, "mcp");

    @BeforeEach
    void setUp() {
        hybrid = mock(HybridSearchService.class);
        crossEncoder = mock(CrossEncoderReranker.class);
        ragService = mock(RagService.class);
        props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(10);
        when(props.effectiveRerankTopK()).thenReturn(5);
        when(props.effectiveRerankCandidates()).thenReturn(20);
        when(props.searchRerankMinScoreSafe()).thenReturn(0.0);
        when(hybrid.withStoredText(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private KnowledgeSearchTool tool(boolean withReranker) {
        return new KnowledgeSearchTool(hybrid, withReranker ? Optional.of(crossEncoder) : Optional.empty(), ragService, props,
                new ToolGuard(mock(AuditLogger.class)));
    }

    private static Document doc(String id, String docId, String text) {
        return new Document(id, text, Map.of("doc_id", docId, "filename", docId + ".md", "page_or_slide", "3"));
    }

    private void candidates(Document... docs) {
        when(hybrid.search(any())).thenReturn(new HybridSearchService.SearchResult(List.of(docs), Map.of()));
    }

    @Test
    @DisplayName("검색 요청의 버전은 latest 로 고정이고 호출자 id·태그가 실린다 — 인자로 바꿀 수 없다")
    void requestIsBuiltFromServerValues() {
        candidates(doc("c1", "d1", "본문"));
        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(new Ranked(doc("c1", "d1", "본문"), 3.0)));

        tool(true).searchKnowledge("질문", "AbsBatch", "배치, 가이드", 3, caller.toContext());

        ArgumentCaptor<HybridSearchService.SearchRequest> cap = ArgumentCaptor.forClass(HybridSearchService.SearchRequest.class);
        verify(hybrid).search(cap.capture());
        HybridSearchService.SearchRequest r = cap.getValue();
        assertThat(r.version()).isEqualTo("latest");
        assertThat(r.userId()).isEqualTo("u1");
        assertThat(r.searchQuestion()).isEqualTo("질문");
        assertThat(r.keywordQuestion()).isEqualTo("AbsBatch");
        assertThat(r.selectedTags()).containsExactly("배치", "가이드");
        assertThat(r.candidateK()).isEqualTo(20);
        assertThat(r.allowExpansion()).as("MCP 호출은 질의 확장 LLM 을 돌리지 않는다").isFalse();
        assertThat(r.excludedDocIds()).isEmpty();
    }

    @Test
    @DisplayName("리랭커가 있으면 점수와 함께 돌려주고, 1위가 문턱 미만이면 lowConfidence")
    void scoredHitsAndLowConfidence() {
        candidates(doc("c1", "d1", "A"), doc("c2", "d2", "B"));
        when(crossEncoder.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new Ranked(doc("c2", "d2", "B"), -1.5), new Ranked(doc("c1", "d1", "A"), -3.0)));

        KnowledgeSearchTool.SearchOutput out = tool(true).searchKnowledge("질문", null, null, null, caller.toContext());

        assertThat(out.hits()).extracting(KnowledgeSearchTool.Hit::chunkId).containsExactly("c2", "c1");
        assertThat(out.hits().get(0).score()).isEqualTo(-1.5);
        assertThat(out.hits().get(0).file()).isEqualTo("d2.md");
        assertThat(out.hits().get(0).page()).isEqualTo("3");
        assertThat(out.lowConfidence()).isTrue();
        assertThat(out.totalHits()).isEqualTo(2);

        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(new Ranked(doc("c1", "d1", "A"), 2.0)));
        assertThat(tool(true).searchKnowledge("질문", null, null, null, caller.toContext()).lowConfidence()).isFalse();
    }

    @Test
    @DisplayName("리랭커가 죽으면 RRF 순서로 물러난다 — 점수는 null, 낮다고도 말하지 않는다")
    void fallsBackToRrfOrder() {
        candidates(doc("c1", "d1", "A"), doc("c2", "d2", "B"), doc("c3", "d3", "C"));
        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenThrow(new CrossEncoderReranker.UnavailableException("다운"));

        KnowledgeSearchTool.SearchOutput out = tool(true).searchKnowledge("질문", null, null, 2, caller.toContext());

        assertThat(out.hits()).extracting(KnowledgeSearchTool.Hit::chunkId).containsExactly("c1", "c2");
        assertThat(out.hits()).allMatch(h -> h.score() == null);
        assertThat(out.lowConfidence()).isFalse();
    }

    @Test
    @DisplayName("리랭커가 없으면 후보 수·컷이 search-top-k 이고 점수가 없다")
    void withoutRerankerUsesTopK() {
        List<Document> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(doc("c" + i, "d" + i, "본문 " + i));
        when(hybrid.search(any())).thenReturn(new HybridSearchService.SearchResult(many, Map.of()));

        KnowledgeSearchTool.SearchOutput out = tool(false).searchKnowledge("질문", null, null, 99, caller.toContext());

        assertThat(out.hits()).hasSize(10);
        assertThat(out.hits()).allMatch(h -> h.score() == null);
        ArgumentCaptor<HybridSearchService.SearchRequest> cap = ArgumentCaptor.forClass(HybridSearchService.SearchRequest.class);
        verify(hybrid).search(cap.capture());
        assertThat(cap.getValue().candidateK()).isEqualTo(10);
    }

    @Test
    @DisplayName("topK 는 컷 이하로 눌린다 · 발췌는 300자 · 빈 검색어와 너무 긴 검색어는 거절")
    void limitsAndValidation() {
        candidates(doc("c1", "d1", "가".repeat(1000)));
        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(new Ranked(doc("c1", "d1", "가".repeat(1000)), 1.0)));

        KnowledgeSearchTool.SearchOutput out = tool(true).searchKnowledge("질문", null, null, 0, caller.toContext());
        assertThat(out.hits().get(0).snippet()).hasSize(301).endsWith("…");
        ArgumentCaptor<Integer> topK = ArgumentCaptor.forClass(Integer.class);
        verify(crossEncoder).rerank(anyString(), anyList(), topK.capture());
        assertThat(topK.getValue()).isEqualTo(1);

        assertThatThrownBy(() -> tool(true).searchKnowledge("  ", null, null, null, caller.toContext()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool(true).searchKnowledge("가".repeat(501), null, null, null, caller.toContext()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool(true).searchKnowledge(null, null, null, null, caller.toContext()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("findRelatedDocuments — 문서별로 묶고(큐레이션·문서 id 없음은 제외), 표시 이름·태그·건수·대표 발췌를 붙인다")
    void relatedDocumentsGroupByDocument() {
        Document curated = new Document("q1", "큐레이션 답", Map.of("doc_type", "curated_qa", "doc_id", "d1"));
        candidates(doc("c1", "d1", "첫 청크"), curated, doc("c2", "d2", "둘째 문서"), doc("c3", "d1", "첫 문서 또 다른 청크"),
                new Document("x", "id 없음", Map.of()));
        when(ragService.findDisplayNames(anyList())).thenReturn(Map.of("d1", "배치 가이드"));
        when(ragService.findDocument(anyString(), anyString())).thenAnswer(inv -> Optional.of(new DocumentInfo(
                inv.getArgument(1), "f.md", null, "latest", 3, "2026-11-06", "sha", List.of("배치"), List.of())));

        KnowledgeSearchTool.RelatedOutput out = tool(false).findRelatedDocuments("AbsBatch", 10, caller.toContext());

        assertThat(out.documents()).extracting(KnowledgeSearchTool.RelatedDocument::docId).containsExactly("d1", "d2");
        KnowledgeSearchTool.RelatedDocument d1 = out.documents().get(0);
        assertThat(d1.file()).isEqualTo("배치 가이드");
        assertThat(d1.hitCount()).isEqualTo(2);
        assertThat(d1.topSnippet()).isEqualTo("첫 청크");
        assertThat(d1.tags()).containsExactly("배치");
        assertThat(out.documents().get(1).file()).as("표시 이름이 없으면 파일 이름").isEqualTo("d2.md");
    }

    @Test
    @DisplayName("findRelatedDocuments — limit 로 문서 수를 자른다")
    void relatedDocumentsRespectLimit() {
        List<Document> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) many.add(doc("c" + i, "d" + i, "본문 " + i));
        when(hybrid.search(any())).thenReturn(new HybridSearchService.SearchResult(many, Map.of()));
        when(ragService.findDisplayNames(anyList())).thenReturn(Map.of());
        when(ragService.findDocument(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(tool(false).findRelatedDocuments("주제", 3, caller.toContext()).documents()).hasSize(3);
        assertThat(tool(false).findRelatedDocuments("주제", null, caller.toContext()).documents()).hasSize(8);
    }
}
