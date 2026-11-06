package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.DocRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code reassembledText} — 내보내기와 같은 재조립 · 후처리를 거친 전문(렌더 없이). Tool 계층 {@code getDocument} 의 재료다. */
class DocumentExportServiceReassembledTextTest {

    private AdminService admin;
    private MarkdownCorrectionService correction;
    private DocRegistry registry;
    private AppProperties props;
    private DocumentExportService service;

    @BeforeEach
    void setUp() {
        admin = mock(AdminService.class);
        correction = mock(MarkdownCorrectionService.class);
        registry = mock(DocRegistry.class);
        props = mock(AppProperties.class);
        when(props.chunkOverlapSafe()).thenReturn(100);
        when(admin.collectionFor("latest")).thenReturn("latest");
        when(registry.findByDocId(anyString())).thenReturn(Optional.empty());
        when(correction.postProcess(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        service = new DocumentExportService(admin, correction, registry, props);
    }

    private static AdminService.ChunkRow chunk(String id, String filename, String text) {
        return new AdminService.ChunkRow(id, text, text, Map.of("doc_id", "d1", "filename", filename));
    }

    @Test
    @DisplayName("청크를 문서 순서로 이어 붙인 텍스트를 돌려주고 렌더는 하지 않는다")
    void reassemblesChunksInOrder() {
        when(admin.getAllChunks("latest", "d1")).thenReturn(List.of(
                chunk("c0", "guide.md", "# 제목\n첫 문단입니다."), chunk("c1", "guide.md", "## 둘째\n둘째 문단입니다.")));

        String text = service.reassembledText("d1", "latest");

        assertThat(text).contains("# 제목", "첫 문단입니다.", "## 둘째", "둘째 문단입니다.");
        assertThat(text.indexOf("첫 문단")).isLessThan(text.indexOf("둘째 문단"));
        verify(correction).postProcess(anyString(), eq(false));
    }

    @Test
    @DisplayName("pptx 문서는 후처리에 pptx 로 알린다 (문서에 기록된 overlap 이 있는 경로도 지난다)")
    void pptxFlagAndStoredOverlap() {
        when(admin.getAllChunks("latest", "d1")).thenReturn(List.of(chunk("c0", "Slides.PPTX", "슬라이드 본문")));
        when(registry.findByDocId("d1")).thenReturn(Optional.of(new DocRegistry.DocRegistryEntry(
                "sha", "latest", "2026-11-06", 1, List.of(), List.of(), 50)));

        service.reassembledText("d1", "latest");

        verify(correction).postProcess(anyString(), eq(true));
    }

    @Test
    @DisplayName("색인된 청크가 없으면 IllegalArgumentException")
    void noChunksIsAnError() {
        when(admin.getAllChunks("latest", "ghost")).thenReturn(List.of());
        assertThatThrownBy(() -> service.reassembledText("ghost", "latest")).isInstanceOf(IllegalArgumentException.class);
    }
}
