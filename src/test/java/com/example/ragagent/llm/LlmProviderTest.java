package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — LlmProvider record
 *
 * Verifies the supports(TaskType) matrix and hasValidApiKey() contract.
 * These determine which providers LlmRouter considers eligible per request.
 */
class LlmProviderTest {

    private LlmProvider provider(TaskType type, String apiKey) {
        return new LlmProvider("p1", type, ProviderRole.NORMAL, 1, apiKey, null, null, true, null, null);
    }

    @Test
    @DisplayName("LIGHT_TEXT 프로바이더는 LIGHT_TEXT + MICRO_TEXT 지원, TEXT/VISION 거부 (§6.21)")
    void lightTextSupportsLightAndMicro() {
        LlmProvider p = provider(TaskType.LIGHT_TEXT, "k");
        assertThat(p.supports(TaskType.LIGHT_TEXT)).isTrue();
        assertThat(p.supports(TaskType.MICRO_TEXT)).isTrue(); // §6.21 — light absorbs micro (fallback)
        assertThat(p.supports(TaskType.TEXT)).isFalse();
        assertThat(p.supports(TaskType.VISION)).isFalse();
    }

    @Test
    @DisplayName("MICRO_TEXT 프로바이더(소형)는 MICRO_TEXT 만 지원 — 분류·직답(LIGHT_TEXT)은 안 받음 (§6.21 B안)")
    void microTextSupportsOnlyMicroText() {
        LlmProvider p = provider(TaskType.MICRO_TEXT, "k");
        assertThat(p.supports(TaskType.MICRO_TEXT)).isTrue();
        assertThat(p.supports(TaskType.LIGHT_TEXT)).isFalse(); // classifier/direct stay on the capable model
        assertThat(p.supports(TaskType.TEXT)).isFalse();
        assertThat(p.supports(TaskType.VISION)).isFalse();
    }

    /**
     * 사다리(MICRO ⊂ LIGHT ⊂ TEXT)의 맨 윗칸. 예전에는 {@code TEXT} 가 {@code TEXT} 만 받아
     * 텍스트 전용 모델 하나짜리 배포(`LOCAL_LLM_TYPE=TEXT`)와 클라우드 전용 배포(출하되는
     * 클라우드 프로바이더가 전부 {@code type=TEXT})에서 {@code MICRO_TEXT}/{@code LIGHT_TEXT}
     * 자격 프로바이더가 0개가 됐다 — 키워드+맥락 추출·MD 교정·TXT 구조화·제목·요약이 통째로
     * 죽는데, 채팅만은 {@code TEXT} 로 라우팅돼 멀쩡히 동작하므로 아무도 눈치채지 못했다.
     */
    @Test
    @DisplayName("TEXT 프로바이더는 TEXT + LIGHT_TEXT + MICRO_TEXT 지원, VISION 거부 (사다리 상단)")
    void textAbsorbsLighterTextTasks() {
        LlmProvider p = provider(TaskType.TEXT, "k");
        assertThat(p.supports(TaskType.TEXT)).isTrue();
        assertThat(p.supports(TaskType.LIGHT_TEXT)).isTrue();
        assertThat(p.supports(TaskType.MICRO_TEXT)).isTrue();
        assertThat(p.supports(TaskType.VISION)).isFalse();
    }

    /**
     * 사다리 전체를 한 곳에 못 박는다 — 개별 케이스는 위 테스트들이 보지만, "무거운 타입은
     * 가벼운 작업을 반드시 흡수한다"는 규칙 자체가 깨지는 것은 여기서만 잡힌다.
     */
    @Test
    @DisplayName("텍스트 사다리 — 무거운 타입은 가벼운 텍스트 작업을 전부 흡수 (MICRO ⊂ LIGHT ⊂ TEXT)")
    void textLadderIsMonotonic() {
        assertThat(provider(TaskType.MICRO_TEXT, "k").supports(TaskType.MICRO_TEXT)).isTrue();

        for (TaskType heavier : List.of(TaskType.LIGHT_TEXT, TaskType.TEXT, TaskType.BOTH)) {
            assertThat(provider(heavier, "k").supports(TaskType.MICRO_TEXT))
                    .as("%s must absorb MICRO_TEXT", heavier).isTrue();
        }
        for (TaskType heavier : List.of(TaskType.TEXT, TaskType.BOTH)) {
            assertThat(provider(heavier, "k").supports(TaskType.LIGHT_TEXT))
                    .as("%s must absorb LIGHT_TEXT", heavier).isTrue();
        }
    }

    @Test
    @DisplayName("VISION 프로바이더는 VISION 만 지원")
    void visionSupportsOnlyVision() {
        LlmProvider p = provider(TaskType.VISION, "k");
        assertThat(p.supports(TaskType.VISION)).isTrue();
        assertThat(p.supports(TaskType.TEXT)).isFalse();
    }

    @Test
    @DisplayName("LIGHT_BOTH 는 LIGHT_TEXT + MICRO_TEXT + VISION 지원, TEXT 거부")
    void lightBothSupportsLightTextAndVision() {
        LlmProvider p = provider(TaskType.LIGHT_BOTH, "k");
        assertThat(p.supports(TaskType.LIGHT_TEXT)).isTrue();
        assertThat(p.supports(TaskType.MICRO_TEXT)).isTrue(); // §6.21
        assertThat(p.supports(TaskType.VISION)).isTrue();
        assertThat(p.supports(TaskType.TEXT)).isFalse();
    }

    /**
     * {@code LIGHT_BOTH} 는 프로바이더 type 이자 요청 task 다 — 요청하는 곳은
     * {@code ImageTypeClassifier}(이미지 유형 분류) 하나이고 "멀티모달이면 되고 대형일 필요는 없다"는 뜻이다.
     * 예전에는 {@code case LIGHT_BOTH} 분기가 자기 아래 작업만 열거하고 자기 자신을 빼놓아
     * {@code BOTH} 만 이 요청을 받을 수 있었다 — 즉 이 값이 존재하는 이유인 "범용 로컬 LLM" 으로
     * 등록하면 이미지 설명({@code VISION} 요청)은 되는데 유형 분류만 그 모델에서 못 돌았다.
     *
     * <p>{@code VISION} 전용은 텍스트를 못 하므로 여전히 거부되어야 한다 — 이 요청은
     * "경량 텍스트 + 이미지" 를 함께 요구한다.
     */
    @Test
    @DisplayName("LIGHT_BOTH 요청 — 멀티모달(LIGHT_BOTH·BOTH)만 받고 텍스트 전용·VISION 전용은 거부")
    void lightBothRequestIsServedByMultimodalProvidersOnly() {
        assertThat(provider(TaskType.LIGHT_BOTH, "k").supports(TaskType.LIGHT_BOTH)).isTrue();
        assertThat(provider(TaskType.BOTH, "k").supports(TaskType.LIGHT_BOTH)).isTrue();
        for (TaskType t : List.of(TaskType.MICRO_TEXT, TaskType.LIGHT_TEXT, TaskType.TEXT, TaskType.VISION)) {
            assertThat(provider(t, "k").supports(TaskType.LIGHT_BOTH))
                    .as("%s must not serve a LIGHT_BOTH request", t).isFalse();
        }
    }

    @Test
    @DisplayName("BOTH 는 모든 TaskType 지원")
    void bothSupportsAll() {
        LlmProvider p = provider(TaskType.BOTH, "k");
        for (TaskType t : TaskType.values()) {
            assertThat(p.supports(t)).as("BOTH should support %s", t).isTrue();
        }
    }

    @Test
    @DisplayName("hasValidApiKey — null/blank 모두 false, 그 외 true")
    void hasValidApiKey() {
        assertThat(provider(TaskType.TEXT, null).hasValidApiKey()).isFalse();
        assertThat(provider(TaskType.TEXT, "").hasValidApiKey()).isFalse();
        assertThat(provider(TaskType.TEXT, "  ").hasValidApiKey()).isFalse();
        assertThat(provider(TaskType.TEXT, "sk-abc").hasValidApiKey()).isTrue();
    }
}
