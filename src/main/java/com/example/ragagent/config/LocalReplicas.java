package com.example.ragagent.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * "같은 로컬 서버 여러 대"를 알아보는 규칙 — 기동 검증(G3, {@code LlmConfig})과 임베딩 구성 경고
 * ({@code EmbeddingBeanConfig})가 함께 쓴다.
 *
 * <p>복제본 = 등록될 LOCAL 프로바이더 중 <b>type 과 priority 가 같은</b> 것. 운영 매뉴얼이 두 번째 서버를 붙이는 방법으로
 * 안내하는 모양 그대로다(role·priority·type 은 같게, base-url 만 다르게 — application.properties [3/5]). 라우터가 같은
 * priority 안에서 부하를 나누고 한쪽이 죽으면 다른 쪽으로 넘기는 것도 정확히 이 묶음이다. priority 가 다르면 같은 작업을
 * 받더라도 복제본이 아니라 <b>폴백</b>이다 — 소형 모델(priority 0)이 죽었을 때 큰 모델이 받아 주는 것은 "그 서버가 없어도
 * 된다"는 뜻이 아니므로, 그 경우의 기동 실패는 예전 그대로 둔다.
 */
final class LocalReplicas {

    private LocalReplicas() {}

    /** {@code cfg} 와 같은 묶음의 <b>다른</b> 프로바이더들(등록될 것만). {@code cfg} 가 LOCAL 이 아니면 빈 목록. */
    static List<AppProperties.ProviderConfig> peersOf(AppProperties.ProviderConfig cfg,
                                                      List<AppProperties.ProviderConfig> all) {
        if (!cfg.isLocal()) return List.of();
        return all.stream()
                .filter(other -> other != cfg
                        && other.isEnabled()
                        && other.isLocal()
                        && other.priority() == cfg.priority()
                        && typeOf(other).equals(typeOf(cfg))
                        && !Objects.equals(other.name(), cfg.name()))
                .toList();
    }

    /** 복제본이 있는 LOCAL 프로바이더가 하나라도 있는가 — 즉 운영자가 로컬 서버 이중화를 의도했는가. */
    static boolean anyReplicated(List<AppProperties.ProviderConfig> all) {
        return all.stream().anyMatch(cfg -> cfg.isEnabled() && !peersOf(cfg, all).isEmpty());
    }

    /** 비교용으로 다듬은 루트 URL — 뒤쪽 {@code /} 와 {@code /v1} 을 걷고 소문자로. */
    static String normalizedRoot(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - 3);
        return u.toLowerCase(Locale.ROOT);
    }

    private static String typeOf(AppProperties.ProviderConfig cfg) {
        return cfg.type() != null ? cfg.type().trim().toUpperCase(Locale.ROOT) : "BOTH";
    }
}
