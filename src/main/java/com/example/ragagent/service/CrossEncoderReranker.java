package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.HttpClientTimeouts;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.llm.LlmPing;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ServerModelResolver;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    /** 리랭커 서버 하나 — 자기 클라이언트·진행 중 호출 수·건너뛰기 창을 가진다. */
    private static final class Target {
        final String label;
        final RestClient client;
        final AtomicInteger inFlight = new AtomicInteger();
        /** 건너뛰기 창의 만료 시각({@code nanoClock} 기준). 0 = 건너뛰지 않음. */
        final AtomicLong blockedUntil = new AtomicLong();
        /** 요청에 실을 모델명을 정한다 — 설정한 이름이 서버 목록의 id 에 포함되면 그 id. {@code null} = 풀지 않는다(설정 그대로). */
        final ServerModelResolver resolver;

        Target(String label, RestClient client, ServerModelResolver resolver) {
            this.label = label;
            this.client = client;
            this.resolver = resolver;
        }
    }

    private final List<Target> targets;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final String model;
    private final int timeoutSeconds;
    private final Semaphore permits;
    private final LongSupplier nanoClock;

    @Autowired
    public CrossEncoderReranker(AppProperties props) {
        this(props, System::nanoTime, true, null);
    }

    /** 테스트용 — 시계를 바꿔 끼워 건너뛰기 창을 기다리지 않고 본다. 모델명은 풀지 않는다(서버로 목록 요청이 나가지 않는다). */
    CrossEncoderReranker(AppProperties props, LongSupplier nanoClock) {
        this(props, nanoClock, false, null);
    }

    /** 테스트용 — 모델 목록을 묻는 함수(주소 → id 목록)를 바꿔 끼워 모델명 해석을 본다. */
    CrossEncoderReranker(AppProperties props, java.util.function.Function<String, List<String>> lister) {
        this(props, System::nanoTime, true, lister);
    }

    private CrossEncoderReranker(AppProperties props, LongSupplier nanoClock, boolean resolveModel,
                                 java.util.function.Function<String, List<String>> lister) {
        AppProperties.RerankerConfig cfg = props.rerankerSafe();
        List<Target> built = new ArrayList<>();
        for (String url : props.rerankerBaseUrls()) {
            RestClient.Builder builder = HttpClientTimeouts.restClientBuilder(cfg.connectTimeoutSeconds(), cfg.timeoutSeconds())
                    .baseUrl(endpointBase(url));
            // 선택 — 키가 걸린 서버(llama-server --api-key, 원격 리랭크 API)용. 임베딩의 app.embedding.api-key 와 같은 규칙: 비면 헤더를 싣지 않는다.
            if (!cfg.apiKey().isBlank()) builder.defaultHeader("Authorization", "Bearer " + cfg.apiKey());
            ServerModelResolver resolver = null;
            if (resolveModel) {
                String modelsBase = endpointBase(url) + "/v1";
                String key = cfg.apiKey().isBlank() ? "no-key" : cfg.apiKey();
                resolver = new ServerModelResolver(url, cfg.model(), lister != null
                        ? () -> lister.apply(url) : () -> LlmPing.listModelIds(modelsBase, key, 3, 5));
            }
            built.add(new Target(url, builder.build(), resolver));
        }
        this.targets = List.copyOf(built);
        this.model = cfg.model();
        this.timeoutSeconds = cfg.timeoutSeconds();
        // 동시 호출 상한은 서버 한 대 기준 값이다 — 서버가 N 대면 N 배까지 동시에 나갈 수 있다.
        this.permits = new Semaphore(cfg.maxConcurrent() * Math.max(1, targets.size()), true);
        this.nanoClock = nanoClock;
    }

    /**
     * {@code candidates} 를 {@code query} 와의 관련도로 다시 줄 세워 상위 {@code topK} 를 점수와 함께 돌려준다(점수 내림차순,
     * 같으면 입력 순서). 후보가 비어 있으면 서버를 부르지 않는다.
     *
     * <p>서버가 여럿이면 진행 중 호출이 가장 적은 곳(같으면 돌아가며)으로 보내고, <b>연결 계열 실패</b>는 그 서버를
     * {@value #BLOCK_SECONDS}초 건너뛰며 <b>같은 호출 안에서</b> 다음 서버로 다시 보낸다. 서버가 답한 오류·읽기 타임아웃은
     * 넘기지 않는다(요청 모양 문제일 수 있다 — {@code LoadBalancingEmbeddingModel} 과 같은 판단).
     *
     * @throws UnavailableException 서버에 닿지 못했거나 응답을 쓸 수 없을 때
     */
    public List<Ranked> rerank(String query, List<Document> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) return List.of();
        List<Target> order = candidateOrder();
        if (order.isEmpty()) {
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
            UnavailableException last = null;
            for (Target t : order) {
                t.inFlight.incrementAndGet();
                try {
                    // 이 서버가 아는 이름으로 — 첫 호출 때 한 번 /v1/models 로 풀린다(못 풀면 설정한 이름 그대로)
                    String body = requestBody(query, candidates, t.resolver == null ? model : t.resolver.modelName());
                    String response = t.client.post().uri("/v1/rerank")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(String.class);
                    return parse(response, candidates, topK);
                } catch (UnavailableException e) {
                    throw e;
                } catch (RestClientException e) {
                    UnavailableException failure = new UnavailableException("리랭커 호출 실패: " + e.getMessage(), e);
                    if (LlmRouter.isConnectionFailure(e)) {
                        t.blockedUntil.set(nanoClock.getAsLong() + TimeUnit.SECONDS.toNanos(BLOCK_SECONDS));
                        log.warn("[Reranker] 서버에 닿지 못했다 — {}초 건너뛴다 ({}): {}", BLOCK_SECONDS, t.label, e.getMessage());
                        last = failure;   // 다음 서버로
                    } else {
                        log.warn("[Reranker] 호출 실패 ({}): {}", t.label, e.getMessage());
                        throw failure;
                    }
                } catch (RuntimeException e) {
                    // 요청을 만들다 난 예외 등 — 어느 쪽이든 호출자가 할 일은 같다(리랭크를 건너뛴다). 건너뛰기 창은 열지 않는다.
                    log.warn("[Reranker] 예기치 않은 실패: {}", e.toString());
                    throw new UnavailableException("리랭커 호출 중 예외: " + e, e);
                } finally {
                    t.inFlight.decrementAndGet();
                }
            }
            throw last != null ? last : new UnavailableException("리랭커 서버에 닿지 못했다");
        } finally {
            permits.release();
        }
    }

    /** 건너뛰기 창 안인가 — 0 은 "차단 없음"이라 시계 값이 음수여도 차단으로 읽지 않는다. */
    private static boolean blocked(Target t, long now) {
        long until = t.blockedUntil.get();
        return until != 0 && now - until < 0;
    }

    /** 이번 호출이 시도할 서버 순서 — 건너뛰는 중이 아닌 서버를 진행 중 호출이 적은 순(같으면 돌아가며)으로. */
    private List<Target> candidateOrder() {
        long now = nanoClock.getAsLong();
        List<Target> open = new ArrayList<>();
        for (Target t : targets) if (!blocked(t, now)) open.add(t);
        if (open.size() > 1) {
            int offset = Math.floorMod(roundRobin.getAndIncrement(), open.size());
            java.util.Collections.rotate(open, -offset);
            open.sort(Comparator.comparingInt(t -> t.inFlight.get()));   // 안정 정렬 — 동률은 회전 순서가 남는다
        }
        return open;
    }

    /** 연결 계열 실패 뒤 건너뛰기 창 안인가 — {@code /settings} 의 상태 배지가 읽는다. */
    public boolean isSkipping() {
        long now = nanoClock.getAsLong();
        return targets.stream().allMatch(t -> blocked(t, now));
    }

    /** 요청 본문 — 문서는 입력 순서 그대로이고 응답의 {@code index} 가 이 순서를 가리킨다. */
    String requestBody(String query, List<Document> candidates) {
        return requestBody(query, candidates, model);
    }

    String requestBody(String query, List<Document> candidates, String modelName) {
        List<String> docs = new ArrayList<>(candidates.size());
        for (Document d : candidates) docs.add(textFor(d));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
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
