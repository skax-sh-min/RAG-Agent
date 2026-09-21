package com.example.ragagent.llm;

import com.example.ragagent.config.HttpClientTimeouts;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "로컬 LLM 이 살아 있는가" — {@code GET /api/v1/llm/ping} 의 실체.
 *
 * <p>서버가 응답하지 않을 때 운영자가 가장 먼저 묻는 질문인데, 답이 셋으로 갈린다는 것이 이
 * 클래스의 존재 이유다. 2026-09-21 의 GPU 소실({@code vk::Queue::submit: ErrorDeviceLost})에서
 * LM Studio 의 HTTP 서버는 끝까지 살아 있었고(500 과 빈 본문을 <em>돌려줬다</em>), 모델도 "로드됨"
 * 이었으며, 죽은 것은 추론 엔진뿐이었다. 그래서 세 단계를 따로 묻고 따로 보고한다:
 * <ol>
 *   <li><b>reachable</b> — {@code GET {baseUrl}/models} 가 2xx JSON 을 돌려주는가. 프로세스와 HTTP 의
 *       생사이지 그 이상이 아니다.</li>
 *   <li><b>modelListed / modelState</b> — 설정된 모델이 목록에 있는가, 그리고 서버가 말해 주는 상태.
 *       LM Studio 는 {@code GET /api/v0/models} 의 {@code state}(loaded/not-loaded), llama.cpp 는
 *       {@code GET /health} 의 {@code status}. 어느 쪽도 아니면 {@code unknown} — 추측하지 않는다
 *       ({@link ContextWindowProbe} 와 같은 태도). JIT 로딩이 켜진 LM Studio 는 다운로드된 모델을
 *       전부 목록에 내므로 {@code modelListed} 만으로는 "로드됨"이 아니다.</li>
 *   <li><b>inference</b>({@code deep=true}) — {@code max_tokens=1} 채팅 완성 한 번. 위 두 단계를
 *       통과하고도 실패하는 것이 정확히 엔진이 죽은 경우다.</li>
 * </ol>
 *
 * <p><b>라우터를 거치지 않는다.</b> 서킷 브레이커·동시성 게이트·사용량 집계·재시도 어느 것도 타지
 * 않는 날것의 HTTP 라, 차단 중에도 물을 수 있고(차단은 별도 필드 {@code circuitBlockedSeconds} 로
 * 보고한다) 결과가 브레이커를 바꾸지도 않는다 — 진단이 상태를 만지면 "핑을 눌렀더니 풀렸다/막혔다"
 * 가 된다. 앱이 실제로 쓰는 base-url·모델 id·API 키로 묻는 것이 운영자 셸의 curl 과 다른 점이다:
 * 셸에서 되는데 앱에서 안 되는 경우(프록시·다른 호스트·해석된 모델 id)를 이쪽이 가른다.
 *
 * <p>어떤 실패도 던지지 않는다 — 실패 자체가 답이다. 오류 문구는 예외 메시지와 응답 본문 앞부분이고
 * API 키는 요청 헤더에만 있어 문구에 실리지 않는다.
 */
public final class LlmPing {

    private static final Logger log = LoggerFactory.getLogger(LlmPing.class);

    /** 오류 문구 상한 — 서버가 HTML 오류 페이지를 통째로 돌려줘도 응답이 부풀지 않게. */
    static final int MAX_ERROR_LEN = 300;

    /** 프로바이더 하나의 판정. {@code null} 필드 = "안 물었다/모른다"이지 실패가 아니다. */
    public record Result(String name, String model, String baseUrl,
                         boolean reachable, Long latencyMs, Boolean modelListed, String modelState,
                         int circuitBlockedSeconds, Inference inference, String error) {
        /** 물어본 단계가 전부 통과했는가 — 안 물은 단계(null)는 세지 않는다. */
        @JsonProperty("ok")
        public boolean ok() {
            return reachable
                    && (modelListed == null || modelListed)
                    && (inference == null || inference.ok());
        }
    }

    /** {@code deep=true} 의 1토큰 완성 결과. */
    public record Inference(boolean ok, Long latencyMs, String error) {}

    private LlmPing() {}

    /**
     * @param provider              앱이 실제로 쓰는 프로바이더(해석된 모델 id·base-url·키를 그대로 쓴다)
     * @param deep                  1토큰 완성까지 할지
     * @param connectTimeoutSeconds 세 호출 공통 연결 타임아웃
     * @param readTimeoutSeconds    목록·상태 조회의 읽기 타임아웃
     * @param inferenceTimeoutSeconds 1토큰 완성의 읽기 타임아웃 — JIT 로딩이 켜진 서버는 첫 요청에서
     *                              모델을 올리느라 이 안에 못 끝낼 수 있고, 그것도 "지금은 못 한다"는
     *                              참인 답이다
     * @param circuitBlockedSeconds 라우터의 서킷 브레이커가 이 프로바이더를 막고 있는 남은 초(없으면 0) —
     *                              여기서 재지 않고 실어만 준다
     * @param includeBaseUrl        응답에 base-url 을 싣는지(내부 호스트가 드러나므로 호출자가 정한다)
     */
    public static Result probe(LlmProvider provider, boolean deep,
                               int connectTimeoutSeconds, int readTimeoutSeconds, int inferenceTimeoutSeconds,
                               int circuitBlockedSeconds, boolean includeBaseUrl) {
        String baseUrl = provider.baseUrl() == null ? "" : provider.baseUrl().replaceAll("/+$", "");
        String root = baseUrl.replaceAll("/v1$", "");
        String apiKey = provider.hasValidApiKey() ? provider.apiKey() : null;
        String model = provider.model();

        // 1. reachable + modelListed — OpenAI 호환 /models
        boolean reachable = false;
        Long latencyMs = null;
        Boolean modelListed = null;
        String error = null;
        long t0 = System.nanoTime();
        try {
            JsonNode body = client(connectTimeoutSeconds, readTimeoutSeconds).get()
                    .uri(baseUrl + "/models")
                    .headers(h -> { if (apiKey != null) h.setBearerAuth(apiKey); })
                    .retrieve()
                    .body(JsonNode.class);
            latencyMs = elapsedMs(t0);
            reachable = true;
            JsonNode data = body == null ? null : body.path("data");
            if (data != null && data.isArray()) {
                List<String> ids = new ArrayList<>();
                for (JsonNode entry : data) {
                    String id = entry.path("id").asText(null);
                    if (id != null) ids.add(id);
                }
                modelListed = model != null && !model.isBlank() && ModelNameResolver.resolve(model, ids).found();
            } else {
                error = "models: unexpected body (no data[])";
            }
        } catch (Exception e) {
            latencyMs = elapsedMs(t0);
            error = "models: " + describe(e);
        }

        // 2. modelState — 서버별 경로, 어느 것도 안 맞으면 unknown
        String modelState = reachable ? modelState(root, model, apiKey, connectTimeoutSeconds, readTimeoutSeconds) : "unknown";

        // 3. inference — deep 일 때만, 그리고 닿을 때만(닿지도 않는 서버에 1토큰을 묻는 건 같은 실패를 두 번 적는 것)
        Inference inference = null;
        if (deep && reachable) {
            inference = inference(baseUrl, model, apiKey, connectTimeoutSeconds, inferenceTimeoutSeconds);
        } else if (deep) {
            inference = new Inference(false, null, "skipped: server unreachable");
        }

        Result result = new Result(provider.name(), model, includeBaseUrl ? baseUrl : null,
                reachable, latencyMs, modelListed, modelState, Math.max(0, circuitBlockedSeconds), inference, error);
        log.info("[LLM_PING] provider={} reachable={} modelListed={} modelState={} inference={} blocked={}s error={}",
                result.name(), result.reachable(), result.modelListed(), result.modelState(),
                inference == null ? "-" : (inference.ok() ? "ok" : "fail"), result.circuitBlockedSeconds(), error);
        return result;
    }

    /** LM Studio {@code /api/v0/models} 의 {@code state} → llama.cpp {@code /health} 의 {@code status} → unknown. */
    private static String modelState(String root, String model, String apiKey, int connectTimeout, int readTimeout) {
        JsonNode lmStudio = getJson(root + "/api/v0/models", apiKey, connectTimeout, readTimeout);
        if (lmStudio != null && lmStudio.path("data").isArray()) {
            List<String> ids = new ArrayList<>();
            for (JsonNode entry : lmStudio.path("data")) {
                String id = entry.path("id").asText(null);
                if (id != null) ids.add(id);
            }
            ModelNameResolver.Resolution match = model == null ? null : ModelNameResolver.resolve(model, ids);
            if (match != null && match.found()) {
                for (JsonNode entry : lmStudio.path("data")) {
                    if (match.resolved().equals(entry.path("id").asText(null))) {
                        String state = entry.path("state").asText(null);
                        if (state != null && !state.isBlank()) return state;
                    }
                }
            }
            return "not-listed";
        }
        JsonNode health = getJson(root + "/health", apiKey, connectTimeout, readTimeout);
        if (health != null) {
            String status = health.path("status").asText(null);
            if (status != null && !status.isBlank()) return status;
        }
        return "unknown";
    }

    /** {@code max_tokens=1} 채팅 완성 — 2xx 이고 {@code choices[]} 가 비어 있지 않으면 ok. */
    private static Inference inference(String baseUrl, String model, String apiKey,
                                       int connectTimeout, int readTimeout) {
        long t0 = System.nanoTime();
        try {
            JsonNode body = client(connectTimeout, readTimeout).post()
                    .uri(baseUrl + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (apiKey != null) h.setBearerAuth(apiKey); })
                    .body(Map.of(
                            "model", model == null ? "" : model,
                            "messages", List.of(Map.of("role", "user", "content", "ping")),
                            "max_tokens", 1,
                            "temperature", 0,
                            "stream", false))
                    .retrieve()
                    .body(JsonNode.class);
            long ms = elapsedMs(t0);
            JsonNode choices = body == null ? null : body.path("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                return new Inference(true, ms, null);
            }
            return new Inference(false, ms, "chat/completions: unexpected body (no choices[])");
        } catch (Exception e) {
            return new Inference(false, elapsedMs(t0), "chat/completions: " + describe(e));
        }
    }

    private static JsonNode getJson(String url, String apiKey, int connectTimeout, int readTimeout) {
        try {
            return client(connectTimeout, readTimeout).get()
                    .uri(url)
                    .headers(h -> { if (apiKey != null) h.setBearerAuth(apiKey); })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("[LLM_PING] {} 조회 실패({}) — 다음 방법으로 넘어간다", url, e.getClass().getSimpleName());
            return null;
        }
    }

    private static RestClient client(int connectTimeout, int readTimeout) {
        return HttpClientTimeouts.restClientBuilder(connectTimeout, readTimeout).build();
    }

    /** 예외 → 한 줄. 4xx/5xx 는 상태와 본문 앞부분까지 — 서버가 준 사유("ErrorDeviceLost")가 거기 있다. */
    static String describe(Throwable e) {
        String text;
        if (e instanceof RestClientResponseException r) {
            String snippet = r.getResponseBodyAsString().replaceAll("\\s+", " ").strip();
            text = "HTTP " + r.getStatusCode().value() + (snippet.isEmpty() ? "" : " " + snippet);
        } else {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String msg = root.getMessage() == null ? "" : root.getMessage().replaceAll("\\s+", " ").strip();
            text = root.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg);
        }
        return text.length() <= MAX_ERROR_LEN ? text : text.substring(0, MAX_ERROR_LEN) + "…";
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }
}
