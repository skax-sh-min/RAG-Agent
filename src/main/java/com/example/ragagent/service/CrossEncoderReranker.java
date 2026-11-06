package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.HttpClientTimeouts;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.llm.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 교차 인코더 리랭커 — {@code llama-server --reranking}(bge-reranker-v2-m3)의 {@code POST /v1/rerank} 를 직접 부른다
 * (AGENT_MCP_DESIGN §4.3 · §5.2). Spring AI 에 리랭크 추상이 없고 LLM 을 거치지 않으므로 {@code LlmRouter} 밖이다 — 임베딩과 같은
 * 위치·같은 이유다.
 *
 * <p>기존 {@link RerankerService}(LLM 리랭커)와 달리 <b>점수를 준다</b>({@code relevance_score}, 모델의 원점수라 음수도 나온다).
 * 점수가 있어야 "1위 점수가 문턱 미만이면 다시 찾는다"를 코드가 판단할 수 있다. 이 빈은 {@code app.reranker.base-url} 이 있을
 * 때만 만들어지고, 없으면 검색은 지금과 똑같이 동작한다({@code RetrievalService} 가 {@code Optional} 로 받는다).
 *
 * <h3>실패는 모두 {@link UnavailableException}</h3>
 * 연결 실패·타임아웃·HTTP 오류·읽을 수 없는 응답 어느 것이든 호출자는 같은 일을 한다 — 리랭크를 건너뛰고 RRF 순서로 자른다.
 * 그 위에서 <b>연결 계열 실패만</b> {@value #BLOCK_SECONDS}초 건너뛴다(죽은 서버가 질문마다 연결 타임아웃을 물리지 않게).
 * 4xx·5xx·읽기 타임아웃은 건너뛰지 않는다 — 서버가 답했거나 느린 것이고, 입력이 크면 500 이 나는 요청 모양 문제라 멀쩡한 서버를
 * 막는다({@code LoadBalancingEmbeddingModel} 과 같은 판단).
 *
 * <h3>입력 크기</h3>
 * 서버는 (질의 + 문서) 한 쌍이 {@code -ub 2048} 토큰 안에 들어가야 한다. 한글은 글자당 1토큰에 가까워 문서는 {@value #MAX_DOC_CHARS}자,
 * 질의는 {@value #MAX_QUERY_CHARS}자로 자른다. 문서는 검색용 장식을 걷은 텍스트({@link MarkdownNoiseNormalizer#normalize})다.
 */
@Service
@ConditionalOnExpression("!'${app.reranker.base-url:}'.isBlank()")
public class CrossEncoderReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 연결 계열 실패 뒤 이 서버를 건너뛰는 시간. */
    static final int BLOCK_SECONDS = 30;
    static final int MAX_DOC_CHARS = 1_800;
    static final int MAX_QUERY_CHARS = 400;

    /** 리랭크된 문서 하나와 점수(높을수록 관련 있다). */
    public record Ranked(Document doc, double score) {}

    /** 리랭커를 쓸 수 없다 — 호출자는 리랭크를 건너뛰고 RRF 순서로 물러난다. */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient client;
    private final String model;
    private final int timeoutSeconds;
    private final Semaphore permits;
    private final LongSupplier nanoClock;
    private volatile long blockedUntilNanos = 0L;

    @Autowired
    public CrossEncoderReranker(AppProperties props) {
        this(props, System::nanoTime);
    }

    /** 테스트용 — 시계를 바꿔 끼워 건너뛰기 창을 기다리지 않고 본다. */
    CrossEncoderReranker(AppProperties props, LongSupplier nanoClock) {
        AppProperties.RerankerConfig cfg = props.rerankerSafe();
        this.client = HttpClientTimeouts.restClientBuilder(cfg.connectTimeoutSeconds(), cfg.timeoutSeconds())
                .baseUrl(endpointBase(cfg.baseUrl()))
                .build();
        this.model = cfg.model();
        this.timeoutSeconds = cfg.timeoutSeconds();
        this.permits = new Semaphore(cfg.maxConcurrent(), true);
        this.nanoClock = nanoClock;
    }

    /**
     * {@code candidates} 를 {@code query} 와의 관련도로 다시 줄 세워 상위 {@code topK} 를 점수와 함께 돌려준다(점수 내림차순,
     * 같으면 입력 순서). 후보가 비어 있으면 서버를 부르지 않는다.
     *
     * @throws UnavailableException 서버에 닿지 못했거나 응답을 쓸 수 없을 때
     */
    public List<Ranked> rerank(String query, List<Document> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) return List.of();
        if (nanoClock.getAsLong() < blockedUntilNanos) {
            throw new UnavailableException("리랭커를 잠시 건너뛴다(연결 실패 후 " + BLOCK_SECONDS + "초)");
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("리랭커 호출 대기 중 중단됨", e);
        }
        if (!acquired) throw new UnavailableException("리랭커 동시 호출 자리를 " + timeoutSeconds + "초 안에 얻지 못했다");
        try {
            String body = requestBody(query, candidates);
            String response = client.post().uri("/v1/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(response, candidates, topK);
        } catch (UnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            if (LlmRouter.isConnectionFailure(e)) {
                blockedUntilNanos = nanoClock.getAsLong() + TimeUnit.SECONDS.toNanos(BLOCK_SECONDS);
                log.warn("[Reranker] 서버에 닿지 못했다 — {}초 건너뛴다: {}", BLOCK_SECONDS, e.getMessage());
            } else {
                log.warn("[Reranker] 호출 실패: {}", e.getMessage());
            }
            throw new UnavailableException("리랭커 호출 실패: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // 요청을 만들다 난 예외 등 — 어느 쪽이든 호출자가 할 일은 같다(리랭크를 건너뛴다). 건너뛰기 창은 열지 않는다.
            log.warn("[Reranker] 예기치 않은 실패: {}", e.toString());
            throw new UnavailableException("리랭커 호출 중 예외: " + e, e);
        } finally {
            permits.release();
        }
    }

    /** 연결 계열 실패 뒤 건너뛰기 창 안인가 — {@code /settings} 의 상태 배지가 읽는다. */
    public boolean isSkipping() {
        return nanoClock.getAsLong() < blockedUntilNanos;
    }

    /** 요청 본문 — 문서는 입력 순서 그대로이고 응답의 {@code index} 가 이 순서를 가리킨다. */
    String requestBody(String query, List<Document> candidates) {
        List<String> docs = new ArrayList<>(candidates.size());
        for (Document d : candidates) docs.add(textFor(d));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("query", truncate(query == null ? "" : query.strip(), MAX_QUERY_CHARS));
        body.put("documents", docs);
        body.put("top_n", docs.size());   // 자르기는 이쪽이 한다 — 서버의 top_n 해석에 기대지 않는다
        return MAPPER.writeValueAsString(body);
    }

    /**
     * 응답을 점수순 상위 {@code topK} 로. 범위 밖 {@code index}·점수가 없는 항목은 버리고, 쓸 수 있는 것이 하나도 없으면
     * 실패다.
     */
    List<Ranked> parse(String response, List<Document> candidates, int topK) {
        if (response == null || response.isBlank()) throw new UnavailableException("리랭커 응답이 비었다");
        JsonNode results;
        try {
            results = MAPPER.readTree(response).path("results");
        } catch (RuntimeException e) {
            throw new UnavailableException("리랭커 응답을 읽을 수 없다: " + e.getMessage(), e);
        }
        if (!results.isArray()) throw new UnavailableException("리랭커 응답에 results 배열이 없다");
        List<Ranked> ranked = new ArrayList<>();
        for (JsonNode r : results) {
            int index = r.path("index").asInt(-1);
            JsonNode scoreNode = r.has("relevance_score") ? r.get("relevance_score") : r.get("score");
            if (index < 0 || index >= candidates.size() || scoreNode == null || !scoreNode.isNumber()) continue;
            double score = scoreNode.asDouble();
            if (Double.isNaN(score) || Double.isInfinite(score)) continue;
            ranked.add(new Ranked(candidates.get(index), score));
        }
        if (ranked.isEmpty()) throw new UnavailableException("리랭커 응답에 쓸 수 있는 항목이 없다");
        // 같은 청크가 두 번 오는 응답은 첫 항목만 — 점수가 같으면 입력 순서(List.sort 가 안정 정렬이다).
        List<Ranked> deduped = new ArrayList<>();
        java.util.Set<Document> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Ranked r : ranked) if (seen.add(r.doc())) deduped.add(r);
        deduped.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return List.copyOf(deduped.subList(0, Math.min(topK, deduped.size())));
    }

    /** 서버가 보는 문서 텍스트 — 검색용 장식을 걷고 한 쌍이 서버 배치에 들어가게 자른다. */
    static String textFor(Document d) {
        String t = d.getText();
        if (t == null) return "";
        return truncate(MarkdownNoiseNormalizer.normalize(t), MAX_DOC_CHARS);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 설정의 주소에서 끝의 {@code /} 와 {@code /v1} 을 걷는다 — 경로는 {@code /v1/rerank} 로 고정이다. */
    static String endpointBase(String configured) {
        String s = configured == null ? "" : configured.strip().replaceAll("/+$", "");
        if (s.endsWith("/v1")) s = s.substring(0, s.length() - 3);
        return s.replaceAll("/+$", "");
    }
}
