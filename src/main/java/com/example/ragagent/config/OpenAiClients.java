package com.example.ragagent.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.net.URI;
import java.time.Duration;

/**
 * OpenAI 호환 서버로 나가는 SDK 클라이언트를 만드는 <b>유일한</b> 자리 — 채팅({@code LlmConfig})과
 * 임베딩({@code EmbeddingBeanConfig})이 같은 규칙으로 쓴다.
 *
 * <p><b>왜 Spring AI 의 옵션({@code OpenAiChatOptions.timeout}) 대신 클라이언트를 직접 만드는가.</b> Spring AI 2.0 의
 * 옵션은 {@code Duration} 하나만 받고 그것이 <b>요청 전체의 상한</b>(OkHttp 의 callTimeout)이 된다(2026-11-05 실측: 20초로
 * 두면 응답 중이든 아니든 20초에 끊기고 연결 타임아웃도 따로 없다). 이 앱은 예전부터 연결 타임아웃과 읽기 타임아웃을
 * 나눴고(연결이 안 되는 서버는 빨리 포기해 다음 프로바이더로 넘기고, 스트리밍 답변은 청크가 오는 한 길이를 제한하지
 * 않는다), 그 의미를 그대로 지키려면 SDK 의 {@link Timeout} 을 직접 줘야 한다. 모델 빌더의 http 클라이언트 customizer 로 주는
 * 타임아웃은 클라이언트 옵션의 타임아웃에 덮여 소용이 없다(같은 날 실측).
 *
 * <ul>
 *   <li>{@code connect} — 연결 타임아웃</li>
 *   <li>{@code read}/{@code write} — 소켓 읽기·쓰기 타임아웃. 블로킹 호출은 응답 전체가 한 번의 읽기라 사실상 호출 전체의
 *       상한이고, 스트리밍은 청크 사이의 간격이다</li>
 *   <li>{@code request} — 0 = 호출 전체에는 상한을 두지 않는다. 두면 오래 걸리는 정상 스트리밍 답변이 끊긴다</li>
 * </ul>
 */
public final class OpenAiClients {

    private OpenAiClients() {}

    /**
     * SDK 에 넘길 base URL. SDK 는 이 뒤에 {@code /chat/completions}, {@code /embeddings} 를 그대로 붙인다. 설정의 base-url 을
     * 이렇게 다듬는다({@code /v1} 을 걷어 낸 {@code ProviderConfig.apiBase()} 는 컨텍스트 탐지·G3 가 {@code /v1/models} 를
     * 직접 붙이는 쪽의 기준이다):
     * <ul>
     *   <li>{@code …/v1} 로 끝난다 → 그대로 ({@code http://localhost:1234/v1})</li>
     *   <li>경로가 없다 → {@code /v1} 을 붙인다 ({@code https://api.openai.com})</li>
     *   <li>다른 경로가 있다 → 그대로 ({@code …/v1beta/openai/} 같은 호환 엔드포인트)</li>
     * </ul>
     */
    public static String sdkBaseUrl(String configured) {
        if (configured == null) return null;
        String trimmed = configured.trim().replaceAll("/+$", "");
        if (trimmed.endsWith("/v1")) return trimmed;
        String path;
        try {
            path = URI.create(trimmed).getPath();
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
        return path == null || path.isEmpty() ? trimmed + "/v1" : trimmed;
    }

    /** 위 규칙의 타임아웃·재시도를 담은 클라이언트 옵션. {@code maxRetries} 0 = SDK 의 자동 재시도를 끈다(재시도는 라우터가 한다). */
    public static ClientOptions clientOptions(String baseUrl, String apiKey, int connectTimeoutSeconds,
                                              int readTimeoutSeconds, int maxRetries) {
        Duration read = Duration.ofSeconds(readTimeoutSeconds);
        return ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder().build())
                .baseUrl(sdkBaseUrl(baseUrl))
                .apiKey(apiKey)
                .maxRetries(maxRetries)
                .timeout(Timeout.builder()
                        .connect(Duration.ofSeconds(connectTimeoutSeconds))
                        .read(read)
                        .write(read)
                        .request(Duration.ZERO)
                        .build())
                .build();
    }

    public static OpenAIClient syncClient(ClientOptions options) {
        return new OpenAIClientImpl(options);
    }

    public static OpenAIClientAsync asyncClient(ClientOptions options) {
        return new OpenAIClientAsyncImpl(options);
    }

    /**
     * 같은 클라이언트 옵션 위에 올린 채팅 모델. 블로킹은 동기 클라이언트, 스트리밍은 비동기 클라이언트를 쓰므로 둘 다 만든다.
     * 프로바이더 하나가 이 모델을 둘 가진다(체인용 · 스트리밍 직행용) — 호출마다 클라이언트를 새로 만들지 않도록 옵션은 한 번만 만들어
     * 넘긴다.
     */
    public static OpenAiChatModel chatModel(ClientOptions clientOptions, OpenAiChatOptions defaults) {
        return OpenAiChatModel.builder()
                .openAiClient(syncClient(clientOptions))
                .openAiClientAsync(asyncClient(clientOptions))
                .options(defaults)
                .build();
    }
}
