package com.example.ragagent.tool.knowledge;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.TagUtils;
import com.example.ragagent.service.CrossEncoderReranker;
import com.example.ragagent.service.HybridSearchService;
import com.example.ragagent.service.RagService;
import com.example.ragagent.tool.ToolAccess;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.tool.ToolResultFormatter;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 지식 검색 Tool 둘 — {@code searchKnowledge}(청크 단위)와 {@code findRelatedDocuments}(문서 단위). AGENT_MCP_DESIGN §3.3.
 *
 * <p><b>인자에 {@code version}·{@code userId} 가 없다.</b> 서버가 {@value #VERSION} 으로 고정한다 — 모델이 만든 값이 예약
 * 네임스페이스({@code curated})에 닿을 길을 없앤다. 큐레이션 Q&amp;A 는 {@link HybridSearchService} 의 한 축으로 결과에 섞여 나오며
 * 이 Tool 이 그 저장소를 직접 열지는 않는다.
 *
 * <p>MCP 호출은 재검색 없이 한 번만 검색한다(§3.3). 외부 LLM 은 {@code score}·{@code lowConfidence} 를 보고 스스로 다시 부를 수 있다.
 * 교차 인코더가 없거나 죽어 있으면 RRF 순서 그대로이고 {@code score} 는 {@code null}, {@code lowConfidence} 는 {@code false} 다 —
 * 점수가 없는데 낮다고 말할 수는 없다.
 */
@Component
public class KnowledgeSearchTool {

    /** 이 Tool 이 보는 문서 버전 — 인자로 받지 않는다. */
    static final String VERSION = "latest";

    public record Hit(String chunkId, String docId, String file, Object page, String snippet, Double score) {}

    public record SearchOutput(List<Hit> hits, int totalHits, boolean lowConfidence) {}

    public record RelatedDocument(String docId, String file, List<String> tags, int hitCount, String topSnippet) {}

    public record RelatedOutput(List<RelatedDocument> documents) {}

    private final HybridSearchService hybrid;
    private final Optional<CrossEncoderReranker> crossEncoder;
    private final RagService ragService;
    private final AppProperties props;
    private final ToolGuard guard;

    public KnowledgeSearchTool(HybridSearchService hybrid, Optional<CrossEncoderReranker> crossEncoder,
                               RagService ragService, AppProperties props, ToolGuard guard) {
        this.hybrid = hybrid;
        this.crossEncoder = crossEncoder;
        this.ragService = ragService;
        this.props = props;
        this.guard = guard;
    }

    @Tool(name = "searchKnowledge",
            description = "Search the indexed documents and approved Q&A for passages relevant to a query. "
                    + "Returns up to topK passages with a relevance score; lowConfidence=true means the best passage is weakly related, "
                    + "so try a differently worded query.")
    public SearchOutput searchKnowledge(
            @ToolParam(description = "What to look for, in the language of the documents (required)") String query,
            @ToolParam(required = false, description = "Extra exact words such as class names or error codes, space-separated") String keywords,
            @ToolParam(required = false, description = "Comma-separated document tags to limit the search to") String tags,
            @ToolParam(required = false, description = "How many passages to return (default and maximum: the configured cut)") Integer topK,
            ToolContext context) {
        ToolCaller caller = ToolCaller.from(context);
        guard.require(caller, ToolAccess.PUBLIC, "searchKnowledge");
        String q = ToolResultFormatter.requireText(query, "query");
        String kw = (keywords == null || keywords.isBlank()) ? q : ToolResultFormatter.requireText(keywords, "keywords");

        boolean reranker = crossEncoder.isPresent();
        int cut = reranker ? props.effectiveRerankTopK() : props.searchTopKSafe();
        int want = ToolResultFormatter.clamp(topK, 1, cut, cut);
        int candidateK = reranker ? props.effectiveRerankCandidates() : props.searchTopKSafe();

        HybridSearchService.SearchResult found = hybrid.search(new HybridSearchService.SearchRequest(
                caller.userId(), VERSION, q, q, kw, TagUtils.parseTagList(tags), Set.of(), Optional.empty(),
                candidateK, false));
        List<Document> candidates = hybrid.withStoredText(found.candidates());

        List<Hit> hits = new ArrayList<>();
        boolean low = false;
        if (reranker) {
            try {
                List<CrossEncoderReranker.Ranked> ranked = crossEncoder.get().rerank(q, candidates, want);
                for (CrossEncoderReranker.Ranked r : ranked) hits.add(hit(r.doc(), r.score()));
                low = !ranked.isEmpty() && ranked.get(0).score() < props.searchRerankMinScoreSafe();
            } catch (CrossEncoderReranker.UnavailableException e) {
                // 리랭커를 쓸 수 없다 — RRF 순서로 물러난다(채팅과 같다). 점수가 없으니 낮다고도 말하지 않는다.
                hits.clear();
                for (Document d : candidates.subList(0, Math.min(want, candidates.size()))) hits.add(hit(d, null));
            }
        } else {
            for (Document d : candidates.subList(0, Math.min(want, candidates.size()))) hits.add(hit(d, null));
        }
        return new SearchOutput(List.copyOf(hits), candidates.size(), low);
    }

    @Tool(name = "findRelatedDocuments",
            description = "Find which documents (not passages) are related to a subject or identifier. "
                    + "Returns documents with how many matching passages each has and one top passage.")
    public RelatedOutput findRelatedDocuments(
            @ToolParam(description = "Subject, class name, error code or other identifier (required)") String subject,
            @ToolParam(required = false, description = "Maximum documents to return (default 10, at most 50)") Integer limit,
            ToolContext context) {
        ToolCaller caller = ToolCaller.from(context);
        guard.require(caller, ToolAccess.PUBLIC, "findRelatedDocuments");
        String s = ToolResultFormatter.requireText(subject, "subject");
        int max = ToolResultFormatter.clamp(limit, 1, ToolResultFormatter.MAX_ROWS, 10);

        HybridSearchService.SearchResult found = hybrid.search(new HybridSearchService.SearchRequest(
                caller.userId(), VERSION, s, s, s, List.of(), Set.of(), Optional.empty(),
                props.searchTopKSafe() * 2, false));
        // 문서 단위로 묶는다 — 큐레이션 Q&A 는 문서가 아니라 제외한다. 처음 나온 순서(= 검색 순위)를 지킨다.
        Map<String, List<Document>> byDoc = new LinkedHashMap<>();
        for (Document d : hybrid.withStoredText(found.candidates())) {
            if ("curated_qa".equals(String.valueOf(d.getMetadata().get(MetaKey.DOC_TYPE)))) continue;
            Object id = d.getMetadata().get(MetaKey.DOC_ID);
            if (id == null || String.valueOf(id).isBlank()) continue;
            byDoc.computeIfAbsent(String.valueOf(id), k -> new ArrayList<>()).add(d);
        }
        List<String> docIds = byDoc.keySet().stream().limit(max).toList();
        Map<String, String> names = ragService.findDisplayNames(docIds);
        List<RelatedDocument> out = new ArrayList<>();
        for (String docId : docIds) {
            List<Document> docs = byDoc.get(docId);
            Document top = docs.get(0);
            String file = names.getOrDefault(docId, String.valueOf(top.getMetadata().getOrDefault(MetaKey.FILENAME, docId)));
            // PK 로 한 건씩 — 전체 목록 조회(listDocuments)는 문서 수에 비례한다.
            List<String> docTags = ragService.findDocument(caller.userId(), docId)
                    .map(com.example.ragagent.model.DocumentInfo::tags).orElse(List.of());
            out.add(new RelatedDocument(docId, file, docTags, docs.size(), ToolResultFormatter.snippet(top.getText())));
        }
        return new RelatedOutput(List.copyOf(out));
    }

    private static Hit hit(Document d, Double score) {
        Map<String, Object> m = d.getMetadata();
        return new Hit(d.getId(), String.valueOf(m.getOrDefault(MetaKey.DOC_ID, "")),
                String.valueOf(m.getOrDefault(MetaKey.FILENAME, "")), m.getOrDefault(MetaKey.PAGE_OR_SLIDE, ""),
                ToolResultFormatter.snippet(d.getText()), score);
    }
}
