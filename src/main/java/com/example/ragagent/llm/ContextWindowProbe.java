package com.example.ragagent.llm;

import com.example.ragagent.config.HttpClientTimeouts;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Asks a local LLM server what context window the loaded model is actually running with.
 *
 * <p><b>OpenAI 호환 {@code /v1/models} 에는 컨텍스트 필드가 없다</b> — 그래서 서버별 엔드포인트를
 * 차례로 두드린다. 두 가지를 확인했다:
 * <ul>
 *   <li><b>llama.cpp {@code llama-server}</b> — {@code GET /props} 의
 *       {@code default_generation_settings.n_ctx}. 이것이 <b>실제 런타임</b> 값이다.</li>
 *   <li><b>LM Studio</b> — {@code GET /api/v0/models}(네이티브 REST, OpenAI 호환 경로가 아니다)에서
 *       해당 모델의 <b>{@code loaded_instances}</b> 안에 있는 컨텍스트 길이.</li>
 * </ul>
 *
 * <p><b>쓰면 안 되는 값이 두 개 있고, 그게 이 클래스의 존재 이유다.</b>
 * llama.cpp 의 {@code /v1/models} 는 {@code meta.n_ctx_train} 을 주는데 이건 <b>모델이 학습된</b>
 * 최대치라, 서버가 {@code -c 8192} 로 떠 있어도 Llama 3.1 이면 131072 를 돌려준다. LM Studio 의
 * 최상위 {@code max_context_length} 도 마찬가지로 <b>모델 상한</b>이지 로드된 값이 아니다. 둘 중
 * 하나라도 집어 쓰면 예산이 10배 이상 틀리고, 컨텍스트 초과를 막으려던 코드가 정확히 그 초과를
 * 부른다. 그래서 이 프로브는 <b>로드된 값을 못 찾으면 상한으로 대신하지 않고 빈 값을 반환한다</b> —
 * 추측하느니 모른다고 말하고 운영자에게 {@code context-size} 설정을 넘긴다.
 *
 * <p>파싱은 레코드가 아니라 {@link JsonNode} 탐색이다. 두 서버의 정확한 스키마가 버전마다 달라질 수
 * 있고, 특히 LM Studio 의 {@code loaded_instances} 내부 키 이름은 문서에 항목별로 못 박혀 있지
 * 않다 — 고정 레코드로 바인딩하면 키 하나가 바뀌는 순간 역직렬화가 통째로 실패한다. 후보 키를
 * 여럿 두고 찾되, <b>탐색 범위는 로드된 인스턴스 안으로 한정</b>해 위의 "모델 상한" 값이 실수로
 * 걸리지 않게 한다.
 *
 * <p>기동 경로에서 쓰이므로 <b>어떤 실패도 던지지 않는다</b>. 서버가 이 엔드포인트를 모르거나(404),
 * 응답 모양이 다르거나, 아예 닿지 않아도 빈 값일 뿐이다 — 컨텍스트 크기를 못 알아냈다고 앱이 못 뜰
 * 이유는 없다(모델 존재 검증인 {@code LlmConfig.verifyLocalModel()} 과 다른 점이다).
 */
public final class ContextWindowProbe {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowProbe.class);

    /** LM Studio 의 로드된 인스턴스 설정에서 컨텍스트 길이가 실릴 수 있는 키들. */
    private static final List<String> INSTANCE_CONTEXT_KEYS =
            List.of("context_length", "contextLength", "max_context_length", "maxContextLength", "n_ctx");

    private ContextWindowProbe() {}

    /**
     * @param apiBase {@code /v1} 를 <b>포함하지 않는</b> 서버 루트(예: {@code http://localhost:1234}).
     *                {@code /props} 와 {@code /api/v0/models} 둘 다 {@code /v1} 바깥에 있다.
     * @param model   설정된 모델 id — LM Studio 응답에서 해당 항목을 고르는 데 쓴다.
     */
    public static Optional<Integer> probe(String apiBase, String model,
                                          int connectTimeoutSeconds, int readTimeoutSeconds) {
        String root = apiBase.replaceAll("/+$", "").replaceAll("/v1$", "");
        Optional<Integer> fromProps = llamaCppProps(root, connectTimeoutSeconds, readTimeoutSeconds);
        if (fromProps.isPresent()) return fromProps;
        return lmStudioLoadedInstance(root, model, connectTimeoutSeconds, readTimeoutSeconds);
    }

    /** llama.cpp {@code GET /props} → {@code default_generation_settings.n_ctx} (런타임 슬롯 컨텍스트). */
    private static Optional<Integer> llamaCppProps(String root, int connectTimeout, int readTimeout) {
        JsonNode body = getJson(root + "/props", connectTimeout, readTimeout);
        if (body == null) return Optional.empty();
        JsonNode n = body.path("default_generation_settings").path("n_ctx");
        if (n.isIntegralNumber() && n.asInt() > 0) {
            log.debug("[CTX_PROBE] {}/props → n_ctx={}", root, n.asInt());
            return Optional.of(n.asInt());
        }
        return Optional.empty();
    }

    /**
     * LM Studio {@code GET /api/v0/models} → 해당 모델의 {@code loaded_instances} 안 컨텍스트 길이.
     * 최상위 {@code max_context_length}(모델 상한)는 <b>의도적으로 보지 않는다</b> — 클래스 주석 참고.
     */
    private static Optional<Integer> lmStudioLoadedInstance(String root, String model,
                                                            int connectTimeout, int readTimeout) {
        JsonNode body = getJson(root + "/api/v0/models", connectTimeout, readTimeout);
        if (body == null) return Optional.empty();
        for (JsonNode entry : body.path("data")) {
            if (model != null && !model.isBlank() && !model.equals(entry.path("id").asText(null))) continue;
            JsonNode instances = entry.path("loaded_instances");
            if (!instances.isArray()) continue;
            for (JsonNode instance : instances) {
                Optional<Integer> found = firstPositiveInt(instance);
                if (found.isPresent()) {
                    log.debug("[CTX_PROBE] {}/api/v0/models → loaded_instances context={}", root, found.get());
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** 인스턴스 노드(그 안의 config 객체 포함)에서 후보 키 중 처음 발견되는 양의 정수. */
    private static Optional<Integer> firstPositiveInt(JsonNode instance) {
        for (String key : INSTANCE_CONTEXT_KEYS) {
            JsonNode direct = instance.path(key);
            if (direct.isIntegralNumber() && direct.asInt() > 0) return Optional.of(direct.asInt());
            JsonNode nested = instance.path("config").path(key);
            if (nested.isIntegralNumber() && nested.asInt() > 0) return Optional.of(nested.asInt());
        }
        return Optional.empty();
    }

    /** 어떤 실패도 삼킨다 — 기동을 막지 않는 것이 이 프로브의 계약이다. */
    private static JsonNode getJson(String url, int connectTimeout, int readTimeout) {
        try {
            return HttpClientTimeouts.restClientBuilder(connectTimeout, readTimeout)
                    .build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("[CTX_PROBE] {} 조회 실패({}) — 다음 방법으로 넘어간다", url, e.getClass().getSimpleName());
            return null;
        }
    }
}
