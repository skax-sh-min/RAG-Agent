package com.example.ragagent.workflow;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.TextHash;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
 * 자연어 지식 수정 워크플로(AGENT_MCP_DESIGN §10.6) — 실제 스키마·실제 메시지 번들 위에서, LLM 은 정해진 순서의 응답으로 둔다. 코퍼스는 쓰지 않고,
 * 유형별 대상 선정·위치·가드(G3·G4·G10)·청크 삭제 승격·리포트가 설계대로인지 본다.
 */
class KnowledgeEditWorkflowTest {

    @TempDir Path tmp;

    private KeywordSearchRepository keywords;
    private HybridSearchService hybrid;
    private AdminService admin;
    private LlmRouter router;
    private ChangeJobRepository jobs;
    private ChangeProposalRepository proposals;
    private MaintenanceWorkflow maintenance;
    private KnowledgeEditWorkflow workflow;
    private long jobId;

    @BeforeEach
    void setUp() throws Exception {
        var jdbc = SqliteTestDatabase.open(tmp.resolve("t.db"));
        jobs = new ChangeJobRepository(jdbc);
        proposals = new ChangeProposalRepository(jdbc);
        keywords = mock(KeywordSearchRepository.class);
        hybrid = mock(HybridSearchService.class);
        admin = mock(AdminService.class);
        router = mock(LlmRouter.class);
        maintenance = mock(MaintenanceWorkflow.class);
        when(admin.collectionFor(anyString())).thenReturn("latest");
        when(keywords.findMentions(eq("curated"), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(List.of(), false, false));
        when(hybrid.withStoredText(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybrid.search(any())).thenReturn(new HybridSearchService.SearchResult(List.of(), Map.of()));
        when(router.localTier1Concurrency()).thenReturn(Optional.empty());
        ResourceBundleMessageSource bundle = new ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        AppProperties props = mock(AppProperties.class);
        AppProperties.LlmConfig llm = mock(AppProperties.LlmConfig.class);
        when(llm.temperature()).thenReturn(0.2);
        when(llm.maxTokens()).thenReturn(12_000);
        when(props.llmSafe()).thenReturn(llm);
        workflow = new KnowledgeEditWorkflow(keywords, hybrid, Optional.empty(), admin, router, jobs, proposals, maintenance,
                bundle, props, Duration.ofMillis(1), Duration.ofMillis(1));
        jobId = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, "지시", "root");
    }

    private void chunk(String id, String text, String heading, int index) {
        Map<String, String> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, "d1");
        meta.put(MetaKey.FILENAME, "guide.pdf");
        meta.put(MetaKey.VERSION, "latest");
        meta.put(MetaKey.CHUNK_INDEX, String.valueOf(index));
        if (heading != null) meta.put(MetaKey.HEADING, heading);
        when(admin.getChunk("latest", id)).thenReturn(new AdminService.ChunkRow(id, text, text, meta));
    }

    private void mentions(String... idsAndTexts) {
        List<Document> docs = new java.util.ArrayList<>();
        for (int i = 0; i < idsAndTexts.length; i += 2) {
            Map<String, Object> meta = new HashMap<>(Map.of(MetaKey.DOC_ID, "d1", MetaKey.FILENAME, "guide.pdf",
                    MetaKey.CHUNK_INDEX, String.valueOf(i / 2), MetaKey.HEADING, "h" + i));
            docs.add(Document.builder().id(idsAndTexts[i]).text(idsAndTexts[i + 1]).metadata(meta).build());
        }
        when(keywords.findMentions(eq("latest"), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(docs, false, false));
    }

    /** LLM 응답을 호출 순서대로. */
    private void llm(String... answers) {
        LlmRouter.LlmResult[] rest = Arrays.stream(answers).skip(1).map(a -> new LlmRouter.LlmResult(a, 1, 1)).toArray(LlmRouter.LlmResult[]::new);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenReturn(new LlmRouter.LlmResult(answers[0], 1, 1), rest);
    }

    private List<ChangeProposalRepository.Proposal> stored() {
        return proposals.listByJob(jobId);
    }

    private static final String A_CHUNK = "# A 기능\n\nA 기능은 요청을 30초 동안 기다린다.";

    @Test
    @DisplayName("사용 중단 — 섹션 제목 아래에 다듬은 안내를 넣고 원문은 지우지 않는다. 코퍼스는 쓰지 않는다")
    void deprecateInsertsAPolishedNoticeUnderTheHeading() {
        mentions("c1", A_CHUNK);
        chunk("c1", A_CHUNK, "A 기능", 0);
        llm("{\"role\":\"describes\",\"quote\":\"A 기능은 요청을 30초 동안 기다린다.\"}",
                "글: A 기능은 더 이상 사용되지 않습니다.");

        KnowledgeEditWorkflow.Report r = workflow.propose(jobId, "A 기능은 더 이상 사용되지 않습니다",
                new EditIntent("deprecate", "A 기능", null, null, null, null), Locale.KOREAN);

        assertThat(r.proposals()).isEqualTo(1);
        assertThat(r.fallbacks()).isZero();
        ChangeProposalRepository.Proposal p = stored().get(0);
        assertThat(p.kind()).isEqualTo(ChangeProposalRepository.KIND_EDIT);
        assertThat(p.afterText()).isEqualTo("# A 기능\n\n> **사용 중단** — A 기능은 더 이상 사용되지 않습니다.\n\nA 기능은 요청을 30초 동안 기다린다.");
        assertThat(p.targetRole()).isEqualTo("describes");
        assertThat(p.evidenceQuote()).isEqualTo("A 기능은 요청을 30초 동안 기다린다.");
        assertThat(p.beforeHash()).isEqualTo(TextHash.sha256(A_CHUNK));
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("다듬은 문장이 뜻을 뒤집으면(부정 표현 수가 다르면) 지시문 기준 문장을 그대로 넣는다(G10)")
    void polishedSentenceThatFlipsMeaningFallsBack() {
        mentions("c1", A_CHUNK);
        chunk("c1", A_CHUNK, "A 기능", 0);
        llm("{\"role\":\"describes\",\"quote\":\"A 기능은 요청을 30초 동안 기다린다.\"}",
                "글: A 기능은 계속 쓸 수 있습니다.");

        KnowledgeEditWorkflow.Report r = workflow.propose(jobId, "A 기능은 더 이상 사용되지 않습니다",
                new EditIntent("deprecate", "A 기능", null, "B 기능", null, null), Locale.KOREAN);

        assertThat(r.fallbacks()).isEqualTo(1);
        assertThat(stored().get(0).afterText()).contains("> **사용 중단** — A 기능은(는) 더 이상 사용되지 않습니다. 대신 B 기능을(를) 사용하세요.");
        assertThat(stored().get(0).rationale()).contains("지시문 문장을 그대로");
    }

    @Test
    @DisplayName("언급만 하는 청크는 고치지 않고 리포트에 남긴다 · 같은 섹션의 둘째 청크에는 안내를 넣지 않는다")
    void mentionsAreListedAndOneNoticePerSection() {
        String second = "A 기능은 재시도를 하지 않는다.";
        String table = "| 기능 | 상태 |\n|---|---|\n| A 기능 | 운영 |";
        List<Document> docs = List.of(
                Document.builder().id("c1").text(A_CHUNK).metadata(new HashMap<>(Map.of(MetaKey.DOC_ID, "d1", MetaKey.FILENAME, "guide.pdf", MetaKey.HEADING, "A 기능", MetaKey.CHUNK_INDEX, "3"))).build(),
                Document.builder().id("c2").text(second).metadata(new HashMap<>(Map.of(MetaKey.DOC_ID, "d1", MetaKey.FILENAME, "guide.pdf", MetaKey.HEADING, "A 기능", MetaKey.CHUNK_INDEX, "4"))).build(),
                Document.builder().id("c3").text(table).metadata(new HashMap<>(Map.of(MetaKey.DOC_ID, "d1", MetaKey.FILENAME, "guide.pdf", MetaKey.HEADING, "목록", MetaKey.CHUNK_INDEX, "9"))).build());
        when(keywords.findMentions(eq("latest"), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(docs, false, false));
        chunk("c1", A_CHUNK, "A 기능", 3);
        llm("{\"role\":\"describes\",\"quote\":\"A 기능은 요청을 30초 동안 기다린다.\"}",
                "{\"role\":\"describes\",\"quote\":\"A 기능은 재시도를 하지 않는다.\"}",
                "{\"role\":\"mentions\",\"quote\":\"| A 기능 | 운영 |\"}",
                "글: A 기능은 더 이상 사용되지 않습니다.");

        KnowledgeEditWorkflow.Report r = workflow.propose(jobId, "A 기능은 더 이상 사용되지 않습니다",
                new EditIntent("deprecate", "A 기능", null, null, null, null), Locale.KOREAN);

        assertThat(stored()).extracting(ChangeProposalRepository.Proposal::chunkId).containsExactly("c1");
        assertThat(r.mentionsOnly()).extracting(KnowledgeEditWorkflow.Judged::chunkId).containsExactly("c3");
        assertThat(r.judged()).isEqualTo(3);
    }

    @Test
    @DisplayName("주의 사항 — 1위 청크에 다듬은 문장을 모델이 고른 자리 뒤에 넣고, 나머지 설명함 청크는 추가 적용 후보로")
    void addNoteGoesToTheTopChunkAndListsTheRest() {
        String b1 = "# B 기능\n\nB 는 init() 뒤에 호출한다.\n\n다른 문단.";
        String b2 = "B 는 응답을 캐시한다.";
        mentions("c1", b1, "c2", b2);
        chunk("c1", b1, "B 기능", 0);
        llm("{\"role\":\"describes\",\"quote\":\"B 는 init() 뒤에 호출한다.\"}",
                "{\"role\":\"describes\",\"quote\":\"B 는 응답을 캐시한다.\"}",
                "뒤: B 는 init() 뒤에 호출한다.\n글: B 를 호출하기 전에 init() 이 끝났는지 확인해야 합니다.");

        KnowledgeEditWorkflow.Report r = workflow.propose(jobId, "B 기능과 관련해 호출 전에 init() 이 끝났는지 확인해야 한다는 주의 사항을 추가해",
                new EditIntent("add_note", "B", "호출 전에 init() 이 끝났는지 확인해야 한다", null, null, null), Locale.KOREAN);

        assertThat(stored()).hasSize(1);
        assertThat(stored().get(0).afterText()).isEqualTo(
                "# B 기능\n\nB 는 init() 뒤에 호출한다.\n\n> **주의** — B 를 호출하기 전에 init() 이 끝났는지 확인해야 합니다.\n\n다른 문단.");
        assertThat(r.extraCandidates()).extracting(KnowledgeEditWorkflow.Judged::chunkId).containsExactly("c2");

        // 화면에서 추가 적용 후보를 고르면 같은 의도로 수정안을 더한다
        jobs.setIntent(jobId, new EditIntent("add_note", "B", "호출 전에 init() 이 끝났는지 확인해야 한다", null, null, null).toJson("nl"));
        chunk("c2", b2, null, 1);
        llm("뒤: B 는 응답을 캐시한다.\n글: B 를 호출하기 전에 init() 이 끝났는지 확인해야 합니다.");
        assertThat(workflow.proposeForChunks(jobId, List.of("c2", "c1"), Locale.KOREAN)).isEqualTo(1);   // c1 은 이미 있다
        assertThat(stored()).extracting(ChangeProposalRepository.Proposal::chunkId).containsExactlyInAnyOrder("c1", "c2");
    }

    @Test
    @DisplayName("내용 삭제 — 대상과 관계없는 구간을 지우려는 작업은 G3 이 버리고 대상 문장만 지운다")
    void removeDropsUnrelatedDeletes() {
        String text = "# 기능들\n\nB 는 이렇게 쓴다.\n\nC 는 따로 쓴다. 그리고 오래 걸린다.";
        mentions("c1", text);
        chunk("c1", text, "기능들", 0);
        llm("{\"role\":\"describes\",\"quote\":\"B 는 이렇게 쓴다.\"}",
                "판정: 변경 필요\n이유: B 설명\n@@ 지우기\n처음: B 는 이렇게 쓴다.\n@@ 지우기\n처음: C 는 따로 쓴다.\n끝: 오래 걸린다.");

        KnowledgeEditWorkflow.Report r = workflow.propose(jobId, "B 에 대한 내용을 삭제해",
                new EditIntent("remove", "B", null, null, null, null), Locale.KOREAN);

        assertThat(r.proposals()).isEqualTo(1);
        String after = stored().get(0).afterText();
        assertThat(after).doesNotContain("B 는 이렇게 쓴다").contains("C 는 따로 쓴다. 그리고 오래 걸린다.");
        assertThat(stored().get(0).rationale()).contains("일부 작업을 버림");
    }

    @Test
    @DisplayName("내용 삭제 — 청크 전체 삭제 판정은 섹션 제목에 대상이 있을 때만 delete_chunk 가 된다")
    void removeWholeChunkNeedsTheSubjectInTheHeading() {
        String text = "## B 기능\n\nB 는 이렇게 쓴다.";
        mentions("c1", text);
        chunk("c1", text, "B 기능", 0);
        llm("{\"role\":\"describes\",\"quote\":\"B 는 이렇게 쓴다.\"}", "판정: 청크 전체 삭제\n이유: B 섹션");

        workflow.propose(jobId, "B 기능에 대한 내용을 삭제해", new EditIntent("remove", "B 기능", null, null, null, null), Locale.KOREAN);

        ChangeProposalRepository.Proposal p = stored().get(0);
        assertThat(p.kind()).isEqualTo(ChangeProposalRepository.KIND_DELETE_CHUNK);
        assertThat(p.afterText()).isEmpty();
    }

    @Test
    @DisplayName("설명 수정 — 지시문에 없는 숫자를 넣은 바꾸기는 G4 가 버린다, 지시문의 숫자는 통과")
    void reviseRejectsInventedNumbers() {
        mentions("c1", A_CHUNK);
        chunk("c1", A_CHUNK, "A 기능", 0);
        llm("{\"role\":\"describes\",\"quote\":\"A 기능은 요청을 30초 동안 기다린다.\"}",
                "판정: 변경 필요\n이유: 값\n@@ 바꾸기\n원문: A 기능은 요청을 30초 동안 기다린다.\n새글: A 기능은 요청을 90초 동안 기다린다.");

        KnowledgeEditWorkflow.Report bad = workflow.propose(jobId, "A 의 기본 타임아웃은 30초가 아니라 60초다",
                new EditIntent("revise", "A 기능", "60초다", null, null, null), Locale.KOREAN);
        assertThat(bad.proposals()).isZero();
        assertThat(bad.discarded().get(0).reason()).contains("90");

        llm("{\"role\":\"describes\",\"quote\":\"A 기능은 요청을 30초 동안 기다린다.\"}",
                "판정: 변경 필요\n이유: 값\n@@ 바꾸기\n원문: A 기능은 요청을 30초 동안 기다린다.\n새글: A 기능은 요청을 60초 동안 기다린다.");
        KnowledgeEditWorkflow.Report ok = workflow.propose(jobId, "A 의 기본 타임아웃은 30초가 아니라 60초다",
                new EditIntent("revise", "A 기능", "60초다", null, null, null), Locale.KOREAN);
        assertThat(ok.proposals()).isEqualTo(1);
        assertThat(stored().get(0).afterText()).contains("60초 동안");
    }

    @Test
    @DisplayName("판정 인용이 원문에 없으면 설명함으로 인정하지 않는다(수정안 없음, 언급 목록으로)")
    void unverifiedQuoteIsNotATarget() {
        mentions("c1", A_CHUNK);
        chunk("c1", A_CHUNK, "A 기능", 0);
        llm("{\"role\":\"describes\",\"quote\":\"A 기능은 폐기 예정이다.\"}");

        KnowledgeEditWorkflow.Report r = workflow.propose(jobId, "A 기능은 더 이상 사용되지 않습니다",
                new EditIntent("deprecate", "A 기능", null, null, null, null), Locale.KOREAN);

        assertThat(r.proposals()).isZero();
        assertThat(r.mentionsOnly()).extracting(KnowledgeEditWorkflow.Judged::chunkId).containsExactly("c1");
    }

    @Test
    @DisplayName("해석 — 지시문에 없는 대상을 내면 거절한다(추측으로 실행하지 않는다), 있으면 통과")
    void interpretationMustBeGroundedInTheInstruction() {
        llm("{\"type\":\"deprecate\",\"subject\":\"B 기능\"}");
        assertThatThrownBy(() -> workflow.interpret("A 기능은 더 이상 사용되지 않습니다", Locale.KOREAN))
                .hasMessageContaining("지시문에 없습니다");

        llm("{\"type\":\"deprecate\",\"subject\":\"A 기능\",\"content\":\"\"}");
        EditIntent intent = workflow.interpret("A 기능은 더 이상 사용되지 않습니다", Locale.KOREAN);
        assertThat(intent.type()).isEqualTo("deprecate");
        assertThat(intent.subject()).isEqualTo("A 기능");
    }

    @Test
    @DisplayName("글자 바꾸기로 해석되면 기존 글자 치환 워크플로로 넘긴다")
    void replaceDelegatesToTheLiteralWorkflow() {
        workflow.run(jobId, "PostgreSQL 을 Oracle 로 바꿔", new EditIntent("replace", null, null, null, "PostgreSQL", "Oracle"), Locale.KOREAN);

        verify(maintenance).proposeAndStore(jobId, "PostgreSQL 을 Oracle 로 바꿔", "PostgreSQL", "Oracle", Locale.KOREAN);
        assertThat(jobs.find(jobId).orElseThrow().intentJson()).contains("\"literal\"");
    }

    @Test
    @DisplayName("작업 몸체 — 끝나면 done 과 nl 리포트, 의도가 저장된다")
    void runStoresTheReportAndIntent() {
        mentions("c1", A_CHUNK);
        chunk("c1", A_CHUNK, "A 기능", 0);
        llm("{\"role\":\"describes\",\"quote\":\"A 기능은 요청을 30초 동안 기다린다.\"}", "글: A 기능은 더 이상 사용되지 않습니다.");

        workflow.run(jobId, "A 기능은 더 이상 사용되지 않습니다", new EditIntent("deprecate", "A 기능", null, null, null, null), Locale.KOREAN);

        ChangeJobRepository.Job job = jobs.find(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(ChangeJobRepository.DONE);
        assertThat(job.intentJson()).contains("\"nl\"").contains("deprecate");
        assertThat(job.summaryJson()).contains("\"mode\":\"nl\"").contains("\"proposals\":1");
    }
}
