package com.example.ragagent.llm;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 설정된 모델명을 서버가 보고한 모델 id 목록에 맞춰 해석한다 (G3 의 이름 규칙).
 *
 * <p>규칙은 셋이고 <b>순서가 곧 우선순위</b>다:
 * <ol>
 *   <li>정확히 같은 id 가 있으면 그것 — 예전 동작 그대로라, 정확한 이름을 적은 배포는 아무것도
 *       달라지지 않는다. 다른 id 가 그 이름을 포함하더라도({@code gemma-4-e4b} 와
 *       {@code gemma-4-e4b-it}) 정확 일치가 이긴다.</li>
 *   <li>없으면, 설정값을 <b>부분 문자열</b>로 포함하는 id 가 <b>정확히 하나</b>일 때 그 id.
 *       대소문자는 무시한다 — 결과는 어차피 서버의 표기이므로 느슨한 비교가 틀린 요청을 만들 수 없다.</li>
 *   <li>그 외(0개 또는 2개 이상)는 실패. 후보 목록을 함께 돌려주므로 호출자가 "없음"과 "모호함"을
 *       구분해 안내할 수 있다.</li>
 * </ol>
 *
 * <p><b>왜 부분 일치인가.</b> 같은 모델을 서버마다 다르게 부른다 — LM Studio 는 {@code google/gemma-4-e4b},
 * llama-server 는 {@code -a} 별칭이나 파일명({@code gemma-4-e4b-it-Q4_K_M.gguf}), Ollama 는
 * {@code gemma-4-e4b:latest}. 운영자가 매번 그 표기를 정확히 옮겨 적지 않아도 되게 하되, 답은
 * <b>언제나 서버의 id</b> 다: 요청 본문의 {@code model} 필드는 서버가 아는 이름이어야 하고, LM Studio 의
 * 컨텍스트 프로브도 id 를 정확히 비교하기 때문이다.
 *
 * <p><b>왜 모호하면 실패인가.</b> G3 는 오타를 기동 시점에 잡으려고 있다. 둘 중 하나를 골라 주면
 * 그 목적이 사라진다 — 어느 모델과 대화하고 있는지 로그를 뒤져야 알게 된다.
 *
 * <p>순수 클래스다({@code TokenEstimator}·{@code PromptBudget} 선례) — HTTP 없이 규칙만 검사할 수 있어야 한다.
 */
public final class ModelNameResolver {

    private ModelNameResolver() {}

    /**
     * 해석 결과.
     *
     * @param configured 운영자가 적은 값(그대로)
     * @param resolved   실제로 써야 할 서버 id. {@code null} 이면 실패 — {@link #candidates} 가 비었으면
     *                   "없음", 둘 이상이면 "모호함"
     * @param candidates 설정값을 포함하는 서버 id 전부(정확 일치도 포함) — 실패 안내문에 싣는다
     */
    public record Resolution(String configured, String resolved, List<String> candidates) {

        public boolean found() {
            return resolved != null;
        }

        /** 설정값이 아니라 서버 표기로 바뀌었는가 — 로그에 "무엇으로 바꿨는지" 남길 때 쓴다. */
        public boolean renamed() {
            return found() && !resolved.equals(configured);
        }

        public boolean ambiguous() {
            return !found() && candidates.size() > 1;
        }
    }

    /**
     * @param configured 설정된 모델명. {@code null}/공백이면 어떤 id 와도 맞지 않는다 — "아무거나"로 읽어
     *                   서버의 유일한 모델을 고르는 편의는 두지 않는다(그건 검증이 아니라 추측이다)
     * @param available  서버가 보고한 id 목록({@code null} 항목은 무시)
     */
    public static Resolution resolve(String configured, List<String> available) {
        List<String> ids = available == null ? List.of()
                : available.stream().filter(Objects::nonNull).distinct().toList();
        if (configured == null || configured.isBlank()) {
            return new Resolution(configured, null, List.of());
        }
        String needle = configured.toLowerCase(Locale.ROOT);
        List<String> candidates = ids.stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        String resolved;
        if (ids.contains(configured)) {
            resolved = configured;
        } else {
            resolved = candidates.size() == 1 ? candidates.get(0) : null;
        }
        return new Resolution(configured, resolved, candidates);
    }
}
