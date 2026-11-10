package com.example.ragagent.workflow;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.service.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 유지보수 수정안 만들기(§6.33 4b-1) — 결정적 치환 · 맥락/키워드 동행 · 불변식 · 큐레이션 수동 처리 · 한계 표시, 그리고 <b>코퍼스를 쓰지 않는다</b>.
 */
class MaintenanceWorkflowTest {

    private KeywordSearchRepository keywords;
    private AdminService admin;
    private LlmRouter router;
    private ChangeJobRepository jobs;
    private ChangeProposalRepository proposals;
    private MaintenanceWorkflow workflow;

    @BeforeEach
    void setUp() {
        keywords = mock(KeywordSearchRepository.class);
        admin = mock(AdminService.class);
        router = mock(LlmRouter.class);
        jobs = mock(ChangeJobRepository.class);
        proposals = mock(ChangeProposalRepository.class);
        when(admin.collectionFor(anyString())).thenReturn("latest");
        when(keywords.findMentions(eq("curated"), anyString(), anyInt()))
                .thenReturn(new KeywordSearchRepository.Mentions(List.of(), false, false));
        ResourceBundleMessageSource bundle = new ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        AppProperties props = mock(AppProperties.class);
        AppProperties.LlmConfig llm = mock(AppProperties.LlmConfig.class);
        when(llm.temperature()).thenReturn(0.2);
        when(llm.maxTokens()).thenReturn(12_000);
        when(props.llmSafe()).thenReturn(llm);
        workflow = new MaintenanceWorkflow(keywords, admin, router, jobs, proposals, bundle, props);
    }

    private static Document hit(String id) {
        return Document.builder().id(id).text("x").metadata(Map.of()).build();
    }

    private void chunk(String id, String docId, String text, String ctx, String kw) {
        Map<String, String> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, docId);
        meta.put(MetaKey.FILENAME, docId + ".pdf");
        if (ctx != null) meta.put(MetaKey.CHUNK_CONTEXT, ctx);
        if (kw != null) meta.put(MetaKey.EXCERPT_KEYWORDS, kw);
        when(admin.getChunk("latest", id)).thenReturn(new AdminService.ChunkRow(id, text, text, meta));
    }

    private void mentions(String... ids) {
        when(keywords.findMentions(eq("latest"), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(
                java.util.Arrays.stream(ids).map(MaintenanceWorkflowTest::hit).toList(), false, false));
    }

    @SuppressWarnings("unchecked")
    private List<ChangeProposalRepository.NewProposal> stored() {
        ArgumentCaptor<List<ChangeProposalRepository.NewProposal>> c = ArgumentCaptor.forClass(List.class);
        verify(proposals).insertAll(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("본문·맥락·키워드에 같은 치환이 걸리고, 되돌릴 해시와 pending 수정안이 남는다")
    void literalReplacementCoversBodyContextAndKeywords() {
        mentions("c1");
        chunk("c1", "d1", "PostgreSQL 에 접속한다. PostgreSQL 드라이버를 쓴다.", "DB 연동 > PostgreSQL", "postgresql, 접속");

        MaintenanceWorkflow.Report r = workflow.propose(7L, "PostgreSQL 을 Oracle 로", "PostgreSQL", "Oracle", Locale.KOREAN);

        assertThat(r.proposals()).isEqualTo(1);
        ChangeProposalRepository.NewProposal p = stored().get(0);
        assertThat(p.jobId()).isEqualTo(7L);
        assertThat(p.afterText()).isEqualTo("Oracle 에 접속한다. Oracle 드라이버를 쓴다.");
        assertThat(p.beforeText()).contains("PostgreSQL");
        assertThat(p.afterContext()).isEqualTo("DB 연동 > Oracle");
        assertThat(p.afterKeywords()).isEqualTo("postgresql, 접속");   // 키워드는 소문자라 글자 그대로 일치하지 않으면 그대로 둔다
        assertThat(p.beforeHash()).isEqualTo(MaintenanceWorkflow.sha256(p.beforeText())).hasSize(64);
        assertThat(p.kind()).isEqualTo(ChangeProposalRepository.KIND_LITERAL);
    }

    @Test
    @DisplayName("코퍼스를 쓰지 않는다 — 청크를 읽기만 하고 수정안 대기열에만 넣는다")
    void neverWritesTheCorpus() {
        mentions("c1");
        chunk("c1", "d1", "ABC 사용", null, null);

        workflow.propose(1L, "ABC → XYZ", "ABC", "XYZ", Locale.KOREAN);

        verify(admin, never()).updateChunk(any(), any(), any(), any());
        verify(admin, never()).reindexChunk(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(admin, never()).deleteChunk(any(), any());
    }

    @Test
    @DisplayName("이미지 마커 경로 안의 말을 바꾸면 마커가 바뀌어 버려진다 — 그림이 깨지지 않는다")
    void changingAMarkerPathIsDiscarded() {
        mentions("img", "ok");
        chunk("img", "d1", "그림 [이미지: images/ABC/p1.png]\nABC 설명", null, null);
        chunk("ok", "d4", "일반 ABC 설명", null, null);

        MaintenanceWorkflow.Report r = workflow.propose(1L, "x", "ABC", "DEF", Locale.KOREAN);

        assertThat(r.proposals()).isEqualTo(1);
        assertThat(r.discarded()).extracting(MaintenanceWorkflow.Discarded::chunkId, MaintenanceWorkflow.Discarded::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("img", "이미지 마커가 바뀜"));
        assertThat(stored()).extracting(ChangeProposalRepository.NewProposal::chunkId).containsExactly("ok");
    }

    @Test
    @DisplayName("불변식 검사 단위 — 페이지 마커 변경 · 새 펜스 결함은 위반, 원래 있던 펜스 결함은 탓하지 않는다")
    void invariantRules() {
        assertThat(MaintenanceWorkflow.invariantViolation("[페이지: 3]\nA", "[페이지: 4]\nA")).isEqualTo("페이지 마커가 바뀜");
        assertThat(MaintenanceWorkflow.invariantViolation("```\nA\n```", "```\nA\n")).isEqualTo("코드 펜스가 깨짐");
        assertThat(MaintenanceWorkflow.invariantViolation("```\nA", "```\nB")).isNull();   // 이미 열려 있던 펜스 — 이 수정이 만든 것이 아니다
        assertThat(MaintenanceWorkflow.invariantViolation("[페이지: 3]\nA", "[페이지: 3]\nB")).isNull();
    }

    @Test
    @DisplayName("대소문자만 다르게 걸린 청크는 건너뛰고 세며, 큐레이션 언급은 수동 처리로만 센다(수정안 없음)")
    void caseOnlyHitsAndCuratedAreCountedNotProposed() {
        mentions("c1", "c2");
        chunk("c1", "d1", "postgresql 소문자만", null, null);
        chunk("c2", "d2", "PostgreSQL 대문자", null, null);
        when(keywords.findMentions(eq("curated"), anyString(), anyInt())).thenReturn(
                new KeywordSearchRepository.Mentions(List.of(hit("q1"), hit("q2")), false, false));

        MaintenanceWorkflow.Report r = workflow.propose(1L, "x", "PostgreSQL", "Oracle", Locale.KOREAN);

        assertThat(r.proposals()).isEqualTo(1);
        assertThat(r.unchanged()).isEqualTo(1);
        assertThat(r.manualCurated()).isEqualTo(2);
        verify(admin, never()).getChunk(anyString(), eq("q1"));
    }

    @Test
    @DisplayName("3글자 미만은 수정안 없이 tooShort 로 밝히고, 상한을 넘으면 truncated 를 밝힌다")
    void limitsAreReported() {
        when(keywords.findMentions(eq("latest"), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(List.of(), false, true));
        assertThat(workflow.propose(1L, "x", "DB", "Oracle", Locale.KOREAN).tooShort()).isTrue();

        when(keywords.findMentions(eq("latest"), anyString(), anyInt())).thenReturn(
                new KeywordSearchRepository.Mentions(List.of(hit("c1")), true, false));
        chunk("c1", "d1", "ABC 사용", null, null);
        assertThat(workflow.propose(2L, "x", "ABC", "XYZ", Locale.KOREAN).truncated()).isTrue();
    }

    @Test
    @DisplayName("같은 말로 바꾸거나 너무 긴 말은 거절한다")
    void rejectsNoOpAndSentences() {
        assertThatThrownBy(() -> workflow.propose(1L, "x", "ABC", "ABC", Locale.KOREAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workflow.propose(1L, "x", "A".repeat(201), "B", Locale.KOREAN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("지시문만 받으면 LLM 이 from/to 를 뽑는다 — 읽지 못하면 추측으로 실행하지 않고 실패한다")
    void termsAreExtractedOrTheJobFails() {
        String[] pair = MaintenanceWorkflow.parseTerms("```json\n{\"from\": \"PostgreSQL\", \"to\": \"Oracle\"}\n```");
        assertThat(pair).containsExactly("PostgreSQL", "Oracle");
        assertThatThrownBy(() -> MaintenanceWorkflow.parseTerms("{\"from\": \"\", \"to\": \"\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaintenanceWorkflow.parseTerms("모르겠습니다")).isInstanceOf(IllegalArgumentException.class);

        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenReturn(new LlmRouter.LlmResult("{\"from\": \"ABC\", \"to\": \"XYZ\"}", 5, 5));
        mentions("c1");
        chunk("c1", "d1", "ABC 사용", null, null);
        MaintenanceWorkflow.Report r = workflow.propose(1L, "ABC 를 XYZ 로 바꿔", null, null, Locale.KOREAN);
        assertThat(r.from()).isEqualTo("ABC");
        assertThat(r.to()).isEqualTo("XYZ");
    }

    @Test
    @DisplayName("작업은 끝나면 done, 던지면 failed 로 저장된다 · 사용자당 동시 1건")
    void storesDoneOrFailedAndGuardsConcurrency() {
        mentions();
        workflow.proposeAndStore(7L, "x", "ABC", "XYZ", Locale.KOREAN);
        verify(jobs).finish(eq(7L), eq(ChangeJobRepository.DONE), any(), eq(null));

        workflow.proposeAndStore(8L, "x", "ABC", "ABC", Locale.KOREAN);
        verify(jobs).finish(eq(8L), eq(ChangeJobRepository.FAILED), eq(null), any());

        when(jobs.countRunning("admin")).thenReturn(1);
        assertThatThrownBy(() -> workflow.start("admin", "x", "ABC", "XYZ", Locale.KOREAN)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> workflow.start("admin2", " ", null, null, Locale.KOREAN)).isInstanceOf(IllegalArgumentException.class);
    }
}
