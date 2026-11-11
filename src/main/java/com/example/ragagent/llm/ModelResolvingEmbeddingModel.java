package com.example.ragagent.llm;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.function.Function;

/**
 * 임베딩 서버 한 대 앞의 데코레이터 — 호출마다 {@link ServerModelResolver} 가 정한 모델명으로 실제 모델을 쓴다(이름이 풀려
 * 바뀌면 그 이름으로 다시 만든다). 서버에 설정한 모델이 없다고 알려져 있으면({@link ServerModelResolver.State#MODEL_MISSING})
 * <b>요청을 보내지 않고</b> {@link ModelUnavailableException} 을 던진다. 서버 하나당 하나라, 여러 주소는 이 위에서
 * {@link LoadBalancingEmbeddingModel} 이 나눈다(모델이 없는 서버는 고르지 않는다).
 */
public class ModelResolvingEmbeddingModel implements EmbeddingModel, ServerStateAware {

    private final ServerModelResolver resolver;
    private final Function<String, EmbeddingModel> factory;
    private volatile String builtFor;
    private volatile EmbeddingModel current;

    /** @param initialModel 처음 만들 때 쓴 이름(설정한 값) */
    public ModelResolvingEmbeddingModel(ServerModelResolver resolver, Function<String, EmbeddingModel> factory,
                                        String initialModel) {
        this.resolver = resolver;
        this.factory = factory;
        this.builtFor = initialModel;
        this.current = factory.apply(initialModel);
    }

    @Override
    public ServerModelResolver.State serverState() {
        return resolver.state();
    }

    private EmbeddingModel model() {
        if (resolver.state() == ServerModelResolver.State.MODEL_MISSING) {
            throw new ModelUnavailableException("embedding model '" + resolver.configuredModel() + "' is not available on "
                    + resolver.label() + " (" + resolver.detail() + ") \u2014 the request was not sent");
        }
        String name = resolver.modelName();
        if (name != null && !name.equals(builtFor)) {
            synchronized (this) {
                if (!name.equals(builtFor)) {
                    current = factory.apply(name);
                    builtFor = name;
                }
            }
        }
        return current;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return model().call(request);
    }

    @Override
    public float[] embed(Document document) {
        return model().embed(document);
    }

    @Override
    public int dimensions() {
        return model().dimensions();
    }
}
