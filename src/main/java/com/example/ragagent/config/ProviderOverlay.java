package com.example.ragagent.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code application.properties} 의 프로바이더 목록에 화면의 편집({@link ProviderOverride})을 얹는 규칙 — 순수 함수.
 *
 * <p>기동 때 {@code ProviderCatalog} 가 이 결과를 한 번 계산해 굳혀 두고 {@code props.llmSafe().providers()} 가 그것을 돌려준다.
 * 그래서 라우터·설정 화면·생각 수준 미리보기·{@code ThinkingBudget}·사용량 화면이 모두 같은 목록 — <b>지금 돌고 있는 목록</b> —
 * 을 본다. 실행 중에 저장한 편집은 다음 기동에 이 함수를 다시 지난다.
 */
public final class ProviderOverlay {

    private ProviderOverlay() {}

    /**
     * @param base      properties 에서 온 목록(순서 유지)
     * @param overrides 이름 → 편집
     * @return 편집을 얹은 목록 — 숨긴 것은 빠지고, 화면에서 추가한 것은 뒤에 이름 순으로 붙는다
     */
    public static List<AppProperties.ProviderConfig> apply(List<AppProperties.ProviderConfig> base,
                                                           Map<String, ProviderOverride> overrides) {
        List<AppProperties.ProviderConfig> source = base == null ? List.of() : base;
        if (overrides == null || overrides.isEmpty()) return source;

        List<AppProperties.ProviderConfig> out = new ArrayList<>();
        Set<String> baseNames = new HashSet<>();
        for (AppProperties.ProviderConfig cfg : source) {
            baseNames.add(cfg.name());
            ProviderOverride o = overrides.get(cfg.name());
            if (o == null) {
                out.add(cfg);
            } else if (!o.deleted()) {
                out.add(merge(cfg, o));
            }
        }
        overrides.values().stream()
                .filter(o -> o.added() && !o.deleted() && !baseNames.contains(o.name()))
                .sorted(Comparator.comparing(ProviderOverride::name))
                .map(ProviderOverlay::create)
                .forEach(out::add);
        return List.copyOf(out);
    }

    /** 고친 칸만 바꾼다 — 키·stream·동시성·출력 상한·생각 dialect 는 properties 값 그대로다. */
    static AppProperties.ProviderConfig merge(AppProperties.ProviderConfig cfg, ProviderOverride o) {
        return new AppProperties.ProviderConfig(
                cfg.name(),
                o.baseUrl() != null ? o.baseUrl() : cfg.baseUrl(),
                cfg.apiKey(),
                o.model() != null ? o.model() : cfg.model(),
                o.type() != null ? o.type() : cfg.type(),
                o.role() != null ? o.role() : cfg.role(),
                o.priority() != null ? o.priority() : cfg.priority(),
                cfg.stream(), cfg.concurrency(),
                o.contextSize() != null ? o.contextSize() : cfg.contextSize(),
                cfg.maxTokens(), cfg.thinkingDialect());
    }

    /** 화면에서 만든 프로바이더 — 키가 없다(LOCAL 만 만들 수 있는 이유). 나머지 칸은 기본값(stream 켬 · 전역 동시성·상한 · auto). */
    static AppProperties.ProviderConfig create(ProviderOverride o) {
        return new AppProperties.ProviderConfig(o.name(), o.baseUrl(), null, o.model(), o.type(), o.role(),
                o.priority() != null ? o.priority() : 0, null, null, o.contextSize(), null, null);
    }
}
