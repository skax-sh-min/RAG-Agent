package com.example.ragagent.llm;

import com.example.ragagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 기동 시 한 번 — 호출 지점별 생각 수준을 INFO 한 줄로 남기고, 설정이 틀린 곳을 WARN 으로 알린다(PLAN §6.29).
 *
 * <p><b>왜 기동 시에 한 번인가.</b> 수준은 LLM 호출마다 읽히고({@code AppProperties.LlmConfig.thinkingLevel}), 값이
 * 틀리면 거기서 조용히 출하값으로 떨어진다 — 호출마다 경고하면 로그가 그 줄로 덮인다. 대신 여기서 한 번, 운영자가
 * 고칠 키 이름과 함께 알린다. INFO 줄은 업그레이드 직후 "무엇이 켜졌는가"를 로그만 보고 알게 하기 위해서다.
 */
@Component
public class ThinkingStartupReport {

    private static final Logger log = LoggerFactory.getLogger(ThinkingStartupReport.class);

    private final AppProperties props;

    public ThinkingStartupReport(AppProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        AppProperties.LlmConfig llm = props.llmSafe();
        Report r = build(llm.thinking(), llm::thinkingLevel);
        r.warnings().forEach(log::warn);
        log.info(r.summary());
    }

    /** @param warnings 운영자가 고칠 것 — 줄마다 고칠 키가 들어 있다 */
    record Report(String summary, List<String> warnings) {}

    /**
     * 순수 계산 — 로그를 붙잡지 않고 검사할 수 있게 나눴다.
     *
     * @param configured {@code app.llm.thinking.*} 원본(키 = 사이트 id)
     * @param effective  지금 실제로 쓰이는 수준 — 런타임과 같은 함수({@code thinkingLevel})
     */
    static Report build(Map<String, String> configured, Function<ThinkingSite, ThinkingLevel> effective) {
        Map<String, String> raw = configured == null ? Map.of() : configured;
        List<String> warnings = new ArrayList<>();
        int fromFile = 0;
        int fromShipped = 0;
        for (ThinkingSite site : ThinkingSite.values()) {
            String value = raw.get(site.id());
            if (value == null || value.isBlank()) {
                fromShipped++;
            } else if (ThinkingLevel.parse(value).isEmpty()) {
                fromShipped++;
                warnings.add("[THINKING] %s=%s 은(는) off/low/medium/high 가 아니다 — 출하값 %s 를 쓴다"
                        .formatted(site.propertyKey(), value.strip(), site.shippedDefault().value()));
            } else {
                fromFile++;
            }
        }
        new TreeSet<>(raw.keySet()).stream()
                .filter(key -> ThinkingSite.byId(key).isEmpty())
                .forEach(key -> warnings.add("[THINKING] app.llm.thinking.%s 은(는) 모르는 호출 지점이다 — 무시한다 (%s)"
                        .formatted(key, knownIds())));

        Map<ThinkingLevel, List<String>> bySite = new EnumMap<>(ThinkingLevel.class);
        for (ThinkingLevel level : ThinkingLevel.values()) bySite.put(level, new ArrayList<>());
        for (ThinkingSite site : ThinkingSite.values()) bySite.get(effective.apply(site)).add(site.id());
        String levels = bySite.entrySet().stream()
                .map(e -> "%s=%s".formatted(e.getKey().value(), e.getValue()))
                .collect(Collectors.joining(" "));
        String summary = "[THINKING] 호출 지점별 생각 수준 — %s · 설정 파일 %d곳 · 출하값 %d곳"
                .formatted(levels, fromFile, fromShipped);
        return new Report(summary, List.copyOf(warnings));
    }

    private static String knownIds() {
        return Arrays.stream(ThinkingSite.values()).map(ThinkingSite::id).collect(Collectors.joining(", "));
    }
}
