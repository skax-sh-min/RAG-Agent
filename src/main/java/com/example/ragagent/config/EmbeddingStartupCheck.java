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
import java.util.function.Function;

/**
 * 기동 직후 임베딩 서버(EMBED_BASE_URLS 의 주소마다)에 모델 목록 API 로 접속해 보고, 닿지 않거나 설정한 모델
 * ({@code EMBED_MODEL})이 목록에 없으면 <b>WARN 로그만</b> 남긴다 — 기동은 막지 않는다. 임베딩 서버는 앱보다 늦게 떠도 되는
 * 배포가 많고(폐쇄망), 복제본 일부만 죽어도 서비스는 되기 때문이다. 이 점검이 없으면 서버가 꺼진 채 기동해도 첫 질문·첫
 * 인덱싱에서야 실패가 드러난다.
 *
 * <p>기동을 늦추지 않도록 {@link ApplicationReadyEvent} 뒤 가상 스레드에서 돌고, 어떤 실패도 던지지 않는다. 확인은
 * {@code /settings} 상태 배지와 같은 함수({@link ProviderConnectivity#checkEndpointNow})다.
 */
@Component
public class EmbeddingStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStartupCheck.class);

    private final AppProperties props;
    private final Function<String, ProviderConnectivity.Result> prober;

    @Autowired
    public EmbeddingStartupCheck(AppProperties props) {
        this.props = props;
        AppProperties.EmbeddingConfig cfg = props.embeddingSafe();
        this.prober = url -> ProviderConnectivity.checkEndpointNow(url, cfg.apiKey(), cfg.model());
    }

    /** 테스트용 — 실제로 묻는 함수를 바꿔 끼운다. */
    EmbeddingStartupCheck(AppProperties props, Function<String, ProviderConnectivity.Result> prober) {
        this.props = props;
        this.prober = prober;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread.ofVirtual().name("embed-startup-check").start(MdcPropagation.wrap(this::check));
    }

    /** 점검 한 번 — 남긴 경고 문구를 돌려준다(테스트가 본다). */
    List<String> check() {
        List<String> warnings = new ArrayList<>();
        try {
            AppProperties.EmbeddingConfig cfg = props.embeddingSafe();
            List<String> urls = new ArrayList<>();
            if (cfg.baseUrl() != null && !cfg.baseUrl().isBlank()) urls.add(cfg.baseUrl());
            if (cfg.additionalBaseUrls() != null) urls.addAll(cfg.additionalBaseUrls());
            for (String url : urls) {
                ProviderConnectivity.Result r;
                try {
                    r = prober.apply(url);
                } catch (RuntimeException e) {
                    warnings.add(unreachable(url, e.toString()));
                    continue;
                }
                if (!r.reachable()) {
                    warnings.add(unreachable(url, r.error()));
                } else if (Boolean.FALSE.equals(r.modelListed())) {
                    warnings.add("[EMBED STARTUP] embedding server " + url + " is up but does not list model '" + cfg.model()
                            + "' — embedding calls may fail (check EMBED_MODEL).");
                }
            }
            if (urls.size() > 1 && warnings.size() >= urls.size()
                    && warnings.stream().allMatch(w -> w.contains("is not reachable"))) {
                warnings.add("[EMBED STARTUP] none of the " + urls.size()
                        + " embedding servers answered — RAG questions and indexing will fail until one is up.");
            }
        } catch (RuntimeException e) {
            log.debug("[EMBED STARTUP] check skipped: {}", e.toString());
        }
        warnings.forEach(log::warn);
        return warnings;
    }

    private static String unreachable(String url, String error) {
        return "[EMBED STARTUP] embedding server " + url + " is not reachable (" + error
                + ") — the app starts anyway, but RAG questions and indexing fail while it is down.";
    }
}
