package com.example.ragagent.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StandaloneOccurrenceTest {

    @Test
    void embeddedInLongerIdentifierIsNotAMention() {
        assertThat(ImpactAnalysisWorkflow.hasStandaloneOccurrence("요청 번호 req-80801234 형식", "8080")).isFalse();
        assertThat(ImpactAnalysisWorkflow.hasStandaloneOccurrence("MY_LOCAL_LLM_URL_2 변수", "LOCAL_LLM_URL")).isFalse();
    }

    @Test
    void koreanParticlesAndPunctuationAreBoundaries() {
        assertThat(ImpactAnalysisWorkflow.hasStandaloneOccurrence("8080포트를 연다", "8080")).isTrue();
        assertThat(ImpactAnalysisWorkflow.hasStandaloneOccurrence("http://localhost:8080/api", "8080")).isTrue();
        assertThat(ImpactAnalysisWorkflow.hasStandaloneOccurrence("local_llm_url=x", "LOCAL_LLM_URL")).isTrue();
    }

    @Test
    void laterStandaloneOccurrenceCounts() {
        assertThat(ImpactAnalysisWorkflow.hasStandaloneOccurrence("id80801 그리고 8080", "8080")).isTrue();
        assertThat(ImpactAnalysisWorkflow.hasStandaloneOccurrence("", "8080")).isFalse();
    }

    @Test
    void nonAsciiTermsHaveNoBoundaryRequirement() {
        assertThat(ImpactAnalysisWorkflow.hasStandaloneOccurrence("PROGRESSIVE모드를", "PROGRESSIVE 모드")).isFalse();
        assertThat(ImpactAnalysisWorkflow.hasStandaloneOccurrence("선택한 PROGRESSIVE 모드를", "PROGRESSIVE 모드")).isTrue();
    }
}
