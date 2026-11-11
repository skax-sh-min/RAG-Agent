package com.example.ragagent.config;

import com.example.ragagent.llm.ProviderConnectivity;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 기동 직후 임베딩 서버(EMBED_BASE_URLS)와 리랭커 서버(RERANK_BASE_URLS)의 <b>주소마다</b> 모델 목록 API 로 접속해 보고,
 * 닿지 않으면 <b>WARN 로그만</b> 남긴다 — 기동은 막지 않는다. 두 서버는 앱보다 늦게 떠도 되는 배포가 많고(폐쇄망) 복제본
 * 일부만 죽어도 서비스는 되기 때문이다. 이 점검이 없으면 서버가 꺼진 채 기동해도 첫 질문·첫 인덱싱·첫 검색에서야 드러난다
 * (리랭커는 그 턴이 RRF 순서로 조용히 물러나 더 눈에 안 띈다).
 *
 * <p>임베딩은 설정한 모델({@code EMBED_MODEL})이 서버 목록에 없을 때도 경고한다. 리랭커는 모델명을 따지지 않는다 —
 * llama-server 는 요청의 {@code model} 을 무시하므로 목록의 이름(GGUF 파일명)과 {@code RERANK_MODEL} 이 달라도 정상이다.
 * 리랭커 주소가 없으면(= 꺼짐) 아무 말도 하지 않는다.
 *
 * <p>기동을 늦추지 않도록 {@link ApplicationReadyEvent} 뒤 가상 스레드에서 돌고, 어떤 실패도 던지지 않는다. 확인은
 * {@code /settings} 상태 배지와 같은 함수({@link ProviderConnectivity#checkEndpointNow})다.
 */
@Component
public class ModelServersStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(ModelServersStartupCheck.class);

    private final AppProperties props;
    /** (주소, 대상) → 접속 결과. 대상은 {@code "embedding"} 또는 {@code "reranker"}. */
    private final BiFunction<String, String, ProviderConnectivity.Result> prober;

    @Autowired
    public ModelServersStartupCheck(AppProperties props) {
        this.props = props;
        this.prober = (url, kind) -> {
            if ("reranker".equals(kind)) {
                AppProperties.RerankerConfig r = props.rerankerSafe();
                return ProviderConnectivity.checkEndpointNow(url, r.apiKey(), r.model());
            }
            AppProperties.EmbeddingConfig e = props.embeddingSafe();
            return ProviderConnectivity.checkEndpointNow(url, e.apiKey(), e.model());
        };
    }

    /** 테스트용 — 실제로 묻는 함수를 바꿔 끼운다. */
    ModelServersStartupCheck(AppProperties props, BiFunction<String, String, ProviderConnectivity.Result> prober) {
        this.props = props;
        this.prober = prober;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread.ofVirtual().name("model-servers-startup-check").start(MdcPropagation.wrap(this::check));
    }

    /** 점검 한 번 — 남긴 경고 문구를 돌려준다(테스트가 본다). */
    List<String> check() {
        List<String> warnings = new ArrayList<>();
        try {
            AppProperties.EmbeddingConfig cfg = props.embeddingSafe();
            List<String> embedUrls = new ArrayList<>();
            if (cfg.baseUrl() != null && !cfg.baseUrl().isBlank()) embedUrls.add(cfg.baseUrl());
            if (cfg.additionalBaseUrls() != null) embedUrls.addAll(cfg.additionalBaseUrls());
            checkAll("EMBED STARTUP", "embedding", embedUrls, cfg.model(),
                    "RAG questions and indexing", warnings);
            checkAll("RERANK STARTUP", "reranker", props.rerankerBaseUrls(), null,
                    "searches fall back to the plain RRF order (no reranking)", warnings);
        } catch (RuntimeException e) {
            log.debug("[STARTUP CHECK] skipped: {}", e.toString());
        }
        warnings.forEach(log::warn);
        return warnings;
    }

    /** @param modelToList 목록에 있어야 하는 모델명 — {@code null} 이면 모델은 따지지 않는다 */
    private void checkAll(String tag, String kind, List<String> urls, String modelToList, String consequence,
                          List<String> out) {
        if (urls == null || urls.isEmpty()) return;
        int unreachable = 0;
        for (String url : urls) {
            ProviderConnectivity.Result r = null;
            String error = null;
            try {
                r = prober.apply(url, kind);
            } catch (RuntimeException e) {
                error = e.toString();
            }
            if (r == null || !r.reachable()) {
                unreachable++;
                out.add("[" + tag + "] " + kind + " server " + url + " is not reachable ("
                        + (r != null ? r.error() : error) + ") \u2014 the app starts anyway, but " + consequence
                        + " are affected while it is down.");
            } else if (modelToList != null && Boolean.FALSE.equals(r.modelListed())) {
                out.add("[" + tag + "] " + kind + " server " + url + " is up but does not list model '" + modelToList
                        + "' \u2014 calls may fail (check the model name setting).");
            }
        }
        if (urls.size() > 1 && unreachable == urls.size()) {
            out.add("[" + tag + "] none of the " + urls.size() + " " + kind + " servers answered \u2014 "
                    + consequence + " are affected until one is up.");
        }
    }
}
