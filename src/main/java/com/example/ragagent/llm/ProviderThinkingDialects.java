package com.example.ragagent.llm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프로바이더마다 생각 수준을 어떤 필드로 보내는지({@link ThinkingDialect}), 그리고 그 서버가 어떤 필드를 거부했는지.
 *
 * <p>{@code LlmProvider} 레코드에 넣지 않고 이름 키 사이드 맵으로 둔 이유는 {@link ProviderContextWindows} 와 같다 —
 * 그 레코드는 여러 곳에서 생성되고, 이 값은 프로바이더를 식별하는 정보가 아니라 그에 관한 설정·관측이다.
 * 기동 시 {@code LlmConfig} 가 채운다.
 *
 * <p><b>거부 기억이 여기 있는 이유</b>: 블로킹 체인({@link ThinkingControlChatModel})과 체인을 지나지 않는 스트리밍
 * 경로(3단계)가 같은 기억을 봐야 한다. 데코레이터 인스턴스에 두면 스트리밍은 매번 거부당한다. 기억은 프로세스가
 * 사는 동안만 간다 — 서버를 바꾸면 앱도 다시 뜨는 것이 이 앱의 운영 형태다.
 */
@Component
public class ProviderThinkingDialects {

    /**
     * @param configured 설정에 적힌 값(비었거나 모르면 {@code AUTO})
     * @param resolved   실제로 쓰는 값 — {@code AUTO} 가 아니다
     */
    public record Entry(ThinkingDialect configured, ThinkingDialect resolved) {}

    private final Map<String, Entry> dialects = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> rejected = new ConcurrentHashMap<>();

    public void record(String providerName, ThinkingDialect configured, boolean localServer) {
        ThinkingDialect c = configured == null ? ThinkingDialect.AUTO : configured;
        dialects.put(providerName, new Entry(c, ThinkingDialect.resolve(c, localServer)));
    }

    public Optional<Entry> find(String providerName) {
        return providerName == null ? Optional.empty() : Optional.ofNullable(dialects.get(providerName));
    }

    /** 모르는 프로바이더에는 아무것도 싣지 않는다 — 표준 밖 필드를 모르는 서버에 보내 차단당하는 것보다 낫다. */
    public ThinkingDialect dialectOf(String providerName) {
        return find(providerName).map(Entry::resolved).orElse(ThinkingDialect.NONE);
    }

    /**
     * 이 프로바이더에 이 수준을 보낼 때 실제로 실을 것 — dialect 의 변환에서 이 서버가 거부한 필드를 뺀 것이다.
     * 블로킹·스트리밍·화면이 모두 이 한 함수로 같은 답을 얻는다.
     */
    public ThinkingWire wireFor(String providerName, ThinkingLevel level) {
        return dialectOf(providerName).wire(level).without(rejectedFields(providerName));
    }

    /** @return 처음 기억한 것이면 {@code true} — 경고를 한 번만 남기기 위해서다. */
    public boolean markRejected(String providerName, String field) {
        if (providerName == null || field == null) return false;
        return rejected.computeIfAbsent(providerName, k -> ConcurrentHashMap.newKeySet()).add(field);
    }

    public Set<String> rejectedFields(String providerName) {
        if (providerName == null) return Set.of();
        Set<String> fields = rejected.get(providerName);
        return fields == null ? Set.of() : Set.copyOf(fields);
    }

    public Map<String, Entry> snapshot() {
        return Map.copyOf(dialects);
    }
}
