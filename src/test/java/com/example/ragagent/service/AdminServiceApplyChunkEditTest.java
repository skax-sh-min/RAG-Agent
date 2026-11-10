package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.AppProperties.VectorStoreConfig;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.TextHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code applyChunkEdit}(§6.33 4b-2) — <b>임베딩을 먼저</b> 하고 제자리에서 바꾼다. 해시가 다르면 건드리지 않고(stale), 저장이 실패하면 FTS·재사용
 * 표시까지 아무것도 바뀌지 않으며, 허용된 메타데이터 둘 말고는 받지 않는다.
 */
class AdminServiceApplyChunkEditTest {

    private VectorStoreFacade vectorStore;
    private KeywordSearchRepository keywordRepo;
    private QuestionReuseService reuse;
    private AdminService svc;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStoreFacade.class);
        keywordRepo = mock(KeywordSearchRepository.class);
        reuse = mock(QuestionReuseService.class);
        AppProperties props = mock(AppProperties.class);
        when(props.vectorStoreSafe()).thenReturn(new VectorStoreConfig("sqlite-vec"));
        when(reuse.currentChunkHash("c1")).thenReturn("old-hash");
        svc = spy(new AdminService(Optional.empty(), mock(JdbcTemplate.class), props, new ObjectMapper(), vectorStore,
                keywordRepo, mock(KeywordExtractor.class), reuse, null));
    }

    private void chunk(String text, String docType) {
        Map<String, String> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, "d1");
        meta.put(MetaKey.VERSION, "latest");
        meta.put(MetaKey.FILENAME, "guide.pdf");
        meta.put(MetaKey.CHUNK_CONTEXT, "DB > PostgreSQL");
        meta.put(MetaKey.EXCERPT_KEYWORDS, "postgresql");
        meta.put("section", "3");
        if (docType != null) meta.put(MetaKey.DOC_TYPE, docType);
        doReturn(new AdminService.ChunkRow("c1", text, text, meta)).when(svc).getChunk("latest", "c1");
    }

    @Test
    @DisplayName("성공 — 새 본문·바뀐 맥락/키워드로 임베딩·저장하고 FTS 를 갈고 재사용 표시를 갱신한다")
    void appliesInPlace() {
        chunk("PostgreSQL 에 접속한다.", null);

        AdminService.ApplyOutcome out = svc.applyChunkEdit("latest", "c1", "Oracle 에 접속한다.",
                Map.of(MetaKey.CHUNK_CONTEXT, "DB > Oracle", MetaKey.EXCERPT_KEYWORDS, "oracle"),
                TextHash.sha256("PostgreSQL 에 접속한다."));

        assertThat(out).isEqualTo(AdminService.ApplyOutcome.APPLIED);
        ArgumentCaptor<List<Document>> added = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(eq("shared"), eq("latest"), added.capture());
        Document d = added.getValue().get(0);
        assertThat(d.getId()).isEqualTo("c1");                                    // 청크 id 유지
        assertThat(d.getText()).isEqualTo("Oracle 에 접속한다.");
        assertThat(d.getMetadata()).containsEntry(MetaKey.CHUNK_CONTEXT, "DB > Oracle")
                .containsEntry(MetaKey.EXCERPT_KEYWORDS, "oracle")
                .containsEntry("section", "3")                                    // 건드리지 않은 메타데이터는 그대로
                .containsKey(MetaKey.EDITED_AT);
        verify(keywordRepo).deleteBySpringDocIds(List.of("c1"));
        verify(keywordRepo).indexChunks(anyList());
        verify(reuse).invalidateChunkIfHashChanged("c1", "old-hash");
    }

    @Test
    @DisplayName("본문 해시가 다르면 stale — 벡터·FTS·재사용 표시 어느 것도 건드리지 않는다")
    void staleChangesNothing() {
        chunk("누군가 이미 고친 본문", null);

        AdminService.ApplyOutcome out = svc.applyChunkEdit("latest", "c1", "Oracle", Map.of(),
                TextHash.sha256("PostgreSQL 에 접속한다."));

        assertThat(out).isEqualTo(AdminService.ApplyOutcome.STALE);
        verify(vectorStore, never()).add(anyString(), anyString(), anyList());
        verify(keywordRepo, never()).indexChunks(anyList());
        verify(reuse, never()).invalidateChunkIfHashChanged(anyString(), anyString());
    }

    @Test
    @DisplayName("임베딩·저장이 실패하면 EMBED_FAILED — FTS 도 재사용 표시도 바뀌지 않는다(본문만 새것이 되는 일이 없다)")
    void embedFailureChangesNothing() {
        chunk("PostgreSQL 에 접속한다.", null);
        doThrow(new RuntimeException("embedding server down")).when(vectorStore).add(anyString(), anyString(), anyList());

        AdminService.ApplyOutcome out = svc.applyChunkEdit("latest", "c1", "Oracle 에 접속한다.", Map.of(),
                TextHash.sha256("PostgreSQL 에 접속한다."));

        assertThat(out).isEqualTo(AdminService.ApplyOutcome.EMBED_FAILED);
        verify(keywordRepo, never()).deleteBySpringDocIds(anyList());
        verify(keywordRepo, never()).indexChunks(anyList());
        verify(reuse, never()).invalidateChunkIfHashChanged(anyString(), anyString());
    }

    @Test
    @DisplayName("없는 청크는 NOT_FOUND, 큐레이션 청크는 CURATED — 어느 쪽도 쓰지 않는다")
    void missingAndCuratedAreRefused() {
        doReturn(null).when(svc).getChunk("latest", "c1");
        assertThat(svc.applyChunkEdit("latest", "c1", "x", Map.of(), "h")).isEqualTo(AdminService.ApplyOutcome.NOT_FOUND);

        chunk("Q&A", "curated_qa");
        assertThat(svc.applyChunkEdit("latest", "c1", "x", Map.of(), TextHash.sha256("Q&A"))).isEqualTo(AdminService.ApplyOutcome.CURATED);
        verify(vectorStore, never()).add(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("허용된 메타데이터(맥락·키워드) 말고는 받지 않는다 — doc_id 같은 키를 실어 보내도 무시")
    void onlyEditableMetaIsAccepted() {
        chunk("PostgreSQL 에 접속한다.", null);

        svc.applyChunkEdit("latest", "c1", "Oracle", Map.of(MetaKey.DOC_ID, "evil", MetaKey.FILENAME, "x.pdf", "tags", "t"),
                TextHash.sha256("PostgreSQL 에 접속한다."));

        ArgumentCaptor<List<Document>> added = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(anyString(), anyString(), added.capture());
        assertThat(added.getValue().get(0).getMetadata()).containsEntry(MetaKey.DOC_ID, "d1")
                .containsEntry(MetaKey.FILENAME, "guide.pdf").doesNotContainKey("tags");
    }
}
