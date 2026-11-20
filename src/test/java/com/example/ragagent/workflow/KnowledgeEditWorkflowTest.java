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
    @DisplayName("사용 중단 위치 — 대상 이름이 든 제목 아래, 없으면 대상이 나오는 블록 앞(위치 표시용 상위 제목 아래가 아니다)")
    void deprecationPositionPrefersTheHeadingThatNamesTheSubject() {
        // 상위 제목 아래 대상 이름이 든 하위 제목 — 6c 사전 평가 E4B P1 모양
        String named = "## 47. PUSample — 온디맨드 배치 호출\n\n### 47.1 개요 (키워드: callBatchNow)\n\n본문.";
        assertThat(insertedAfterLine(named, "callBatchNow")).isEqualTo("### 47.1 개요 (키워드: callBatchNow)");

        // 제목에는 대상이 없고 본문 문단에만 — 상위 제목(3.4·3.4.9) 아래가 아니라 그 문단 앞(E4B P7 모양)
        String guide = "### 3.4 어플리케이션 호출\n#### 3.4.9 연동거래\n\n연동거래는 여러 방식이 있다.\n\n"
                + "지연 호출은 reserveCallTimerService 로\n예약한다.\n\n다른 문단.";
        assertThat(from(guide, "reserveCallTimerService")).startsWith("지연 호출은 reserveCallTimerService");
        assertThat(from("한 줄.\n둘째 줄 reserveCallTimerService.", "reserveCallTimerService")).startsWith("한 줄.");

        // 제목 바로 밑(빈 줄 없이)의 문단이면 제목을 넘어 올라가지 않는다
        assertThat(from("## 6. 클래스: ProcessUnit\n메서드 reserveCallTimerService 를 쓴다.", "reserveCallTimerService"))
                .startsWith("메서드 reserveCallTimerService");

        // 코드 펜스 안에서만 나오면 펜스 여는 줄 앞 — 펜스 안 '# 주석' 은 제목이 아니다
        assertThat(from("## 예제\n\n설명.\n\n```java\n# callBatchNow 주석\nsvc.callBatchNow(x);\n```", "callBatchNow"))
                .startsWith("```java");

        // 단어 경계 — callBatchNowAll 제목은 callBatchNow 의 제목이 아니다
        assertThat(from("## callBatchNowAll\n\ncallBatchNow 는 따로 있다.", "callBatchNow")).startsWith("callBatchNow 는");

        // 대상이 청크에 없으면 예전처럼 첫 제목 아래(청크가 제목으로 시작할 때만), 아니면 맨 앞
        assertThat(insertedAfterLine("# A 기능\n\n본문.", "없는이름")).isEqualTo("# A 기능");
        assertThat(KnowledgeEditWorkflow.deprecationPosition("본문만.\n\n## 뒤 제목", "없는이름")).isZero();
    }

    private static String from(String text, String subject) {
        return text.substring(KnowledgeEditWorkflow.deprecationPosition(text, subject));
    }

    /** 위치가 어느 줄의 끝인지 — 그 줄을 돌려준다. */
    private static String insertedAfterLine(String text, String subject) {
        String head = text.substring(0, KnowledgeEditWorkflow.deprecationPosition(text, subject));
        return head.substring(head.lastIndexOf('\n') + 1);
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
    @DisplayName("내용 삭제 — 짧은 문장이라도 남으면 청크 삭제가 아니라 문장 수정이다")
    void shortRemainderStaysAnEdit() {
        String text = "## 기능\n\nB 는 이렇게 쓴다.\n\n주의!";
        mentions("c1", text);
        chunk("c1", text, "기능", 0);
        llm("{\"role\":\"describes\",\"quote\":\"B 는 이렇게 쓴다.\"}",
                "판정: 변경 필요\n이유: B 설명\n@@ 지우기\n처음: B 는 이렇게 쓴다.");

        workflow.propose(jobId, "B 에 대한 내용을 삭제해", new EditIntent("remove", "B", null, null, null, null), Locale.KOREAN);

        ChangeProposalRepository.Proposal p = stored().get(0);
        assertThat(p.kind()).isEqualTo(ChangeProposalRepository.KIND_EDIT);
        assertThat(p.afterText()).doesNotContain("B 는 이렇게 쓴다").contains("주의!").contains("## 기능");
    }

    @Test
    @DisplayName("내용 삭제 — 지운 뒤 제목·표지만 남으면 청크 삭제 수정안이 된다")
    void headingOnlyRemainderBecomesAChunkDelete() {
        String text = "## B 기능\n\nB 는 이렇게 쓴다.\n\n[페이지: 3]";
        mentions("c1", text);
        chunk("c1", text, "B 기능", 0);
        llm("{\"role\":\"describes\",\"quote\":\"B 는 이렇게 쓴다.\"}",
                "판정: 변경 필요\n이유: B 설명\n@@ 지우기\n처음: B 는 이렇게 쓴다.");

        workflow.propose(jobId, "B 에 대한 내용을 삭제해", new EditIntent("remove", "B", null, null, null, null), Locale.KOREAN);

        ChangeProposalRepository.Proposal p = stored().get(0);
        assertThat(p.kind()).isEqualTo(ChangeProposalRepository.KIND_DELETE_CHUNK);
        assertThat(p.afterText()).isEmpty();
        assertThat(p.rationale()).contains("남은 본문이 없어");
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
    @DisplayName("내용 삭제 — 청크가 상위 클래스 제목으로 시작해도 대상 메서드 섹션뿐이면 delete_chunk 가 된다")
    void removeWholeChunkUnderAClassHeading() {
        String text = "## 26. 클래스: FileUtils\n### 26.46 메서드: `readLines(java.io.File)`\n\n파일을 줄 단위로 읽는다.";
        mentions("c1", text);
        chunk("c1", text, "26.46 메서드: `readLines(java.io.File)`", 0);
        llm("{\"role\":\"describes\",\"quote\":\"파일을 줄 단위로 읽는다.\"}", "판정: 청크 전체 삭제\n이유: readLines 섹션");

        workflow.propose(jobId, "readLines 내용을 삭제해", new EditIntent("remove", "readLines", null, null, null, null), Locale.KOREAN);

        assertThat(stored()).singleElement().extracting(ChangeProposalRepository.Proposal::kind)
                .isEqualTo(ChangeProposalRepository.KIND_DELETE_CHUNK);
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

    // ── 6d 신고에서 시작 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("6d — 신고에서 시작하면 후보 찾기·판정 없이 그 청크에만 수정안을 만든다(대상 고정)")
    void reportedChunkIsTheOnlyTarget() {
        // 다른 청크도 대상을 언급하지만 신고 작업은 전수 조회·검색을 하지 않는다.
        mentions("c1", A_CHUNK, "c2", "# A 기능 예시\n\nA 기능을 쓰는 예.");
        chunk("c1", A_CHUNK, "A 기능", 0);
        llm("글: A 기능은 더 이상 사용되지 않습니다.");   // 판정 호출이 없으므로 첫 응답이 곧 문장 다듬기다
        long reportJob = jobs.createForReport(ChangeJobRepository.KIND_MAINTENANCE, "A 기능은 더 이상 사용되지 않습니다", "root", "c1");

        workflow.runForReport(reportJob, "A 기능은 더 이상 사용되지 않습니다",
                new EditIntent("deprecate", "A 기능", null, null, null, null), "c1", Locale.KOREAN);

        ChangeJobRepository.Job job = jobs.find(reportJob).orElseThrow();
        assertThat(job.status()).isEqualTo(ChangeJobRepository.DONE);
        assertThat(job.reportChunkId()).isEqualTo("c1");
        assertThat(job.summaryJson()).contains("\"candidates\":1").contains("\"judged\":0").contains("\"proposals\":1");
        List<ChangeProposalRepository.Proposal> ps = proposals.listByJob(reportJob);
        assertThat(ps).extracting(ChangeProposalRepository.Proposal::chunkId).containsExactly("c1");
        assertThat(ps.get(0).afterText()).contains("사용 중단");
        verify(keywords, never()).findMentions(eq("latest"), anyString(), anyInt());
        verify(hybrid, never()).search(any());
        verify(admin, never()).applyChunkEdit(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("6d — 신고 작업이 글자 바꾸기로 해석되면 그 청크에만 글자 치환을 한다")
    void reportedReplaceStaysOnTheChunk() {
        long reportJob = jobs.createForReport(ChangeJobRepository.KIND_MAINTENANCE, "8080 을 9090 으로", "root", "c1");

        workflow.runForReport(reportJob, "8080 을 9090 으로", new EditIntent("replace", null, null, null, "8080", "9090"), "c1", Locale.KOREAN);

        verify(maintenance).proposeAndStoreForChunk(reportJob, "8080 을 9090 으로", "8080", "9090", "c1");
        verify(maintenance, never()).proposeAndStore(any(Long.class), anyString(), anyString(), anyString(), any(Locale.class));
        assertThat(jobs.find(reportJob).orElseThrow().intentJson()).contains("\"literal\"");
    }

    @Test
    @DisplayName("6d — 사라진 청크·큐레이션 청크로는 시작하지 않고, 신고 작업에는 다른 청크를 더할 수 없다")
    void reportStartGuards() {
        EditIntent intent = new EditIntent("deprecate", "A 기능", null, null, null, null);
        assertThatThrownBy(() -> workflow.startFromReport("root", "gone", "A 기능 중단", intent, Locale.KOREAN))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> curatedMeta = new HashMap<>(Map.of(MetaKey.DOC_TYPE, "curated_qa", MetaKey.VERSION, "curated"));
        when(admin.getChunk("latest", "q1")).thenReturn(new AdminService.ChunkRow("q1", "Q", "Q", curatedMeta));
        assertThatThrownBy(() -> workflow.startFromReport("root", "q1", "A 기능 중단", intent, Locale.KOREAN))
                .isInstanceOf(IllegalArgumentException.class);

        long reportJob = jobs.createForReport(ChangeJobRepository.KIND_MAINTENANCE, "x", "root", "c1");
        jobs.setIntent(reportJob, intent.toJson("nl"));
        assertThatThrownBy(() -> workflow.proposeForChunks(reportJob, List.of("c2"), Locale.KOREAN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
