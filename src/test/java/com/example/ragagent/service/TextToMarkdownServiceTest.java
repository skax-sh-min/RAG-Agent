package com.example.ragagent.service;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TextToMarkdownService} 단위 테스트 — LLM은 mock, 구조화/폴백 동작만 검증.
 */
class TextToMarkdownServiceTest {

    private LlmRouter llmRouter;
    private TextToMarkdownService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        service = new TextToMarkdownService(llmRouter);
    }

    @Test
    @DisplayName("정상: LLM이 반환한 구조화 마크다운을 이어붙여 반환")
    void convert_returnsStructuredMarkdown() {
        when(llmRouter.executeWithTracking(any(), any(), any())).thenReturn("## 제목\n- 항목");

        String md = service.convert("항목 나열 텍스트", "doc1");

        assertThat(md).contains("## 제목").contains("- 항목");
    }

    @Test
    @DisplayName("빈/공백 입력은 LLM 호출 없이 그대로 반환")
    void convert_blankPassThrough() {
        assertThat(service.convert("", "doc1")).isEmpty();
        assertThat(service.convert("   ", "doc1")).isEqualTo("   ");
        assertThat(service.convert(null, "doc1")).isNull();
    }

    @Test
    @DisplayName("LLM 소진(Exhausted): 원본 텍스트를 그대로 유지(인덱싱 계속)")
    void convert_llmExhausted_keepsOriginal() {
        when(llmRouter.executeWithTracking(any(), any(), any()))
                .thenThrow(new LlmProviderExhaustedException("no provider"));

        String original = "구조화 대상 원본 텍스트";
        assertThat(service.convert(original, "doc1")).isEqualTo(original);
    }

    @Test
    @DisplayName("블록 단위 일반 오류: 해당 블록 원본 유지(폴백)")
    void convert_perBlockError_keepsBlock() {
        when(llmRouter.executeWithTracking(any(), any(), any()))
                .thenThrow(new RuntimeException("model timeout"));

        String original = "한 블록 텍스트";
        // fallback keeps the block content (block-split may add a trailing newline — irrelevant to indexing)
        assertThat(service.convert(original, "doc1").strip()).isEqualTo(original);
    }

    @Test
    @DisplayName("진행 콜백이 블록 완료마다 호출된다")
    void convert_invokesProgressCallback() {
        when(llmRouter.executeWithTracking(any(), any(), any())).thenReturn("## ok");
        AtomicInteger last = new AtomicInteger(-1);

        service.convert("짧은 텍스트", "doc1", (done, total) -> last.set(done));

        assertThat(last.get()).isGreaterThanOrEqualTo(1);
    }
}
