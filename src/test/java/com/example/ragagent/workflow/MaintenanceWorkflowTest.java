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

    // ── 의미 변경 수정안(§6.33 4b) ─────────────────────────────────────────

    private void modelSays(String answer) {
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenReturn(new LlmRouter.LlmResult(answer, 10, 10));
    }

    private static String rewrite(String reason, String body) {
        return "판정: 변경 필요\n이유: " + reason + "\n---본문---\n" + body;
    }

    @Test
    @DisplayName("끄면(기본) LLM 을 부르지 않고 리터럴 수정안뿐이다")
    void semanticIsOffByDefault() {
        mentions("c1");
        chunk("c1", "d1", "PostgreSQL 에 org.postgresql.Driver 로 접속한다.", null, null);

        MaintenanceWorkflow.Report r = workflow.propose(1L, "x", "PostgreSQL", "Oracle", Locale.KOREAN);

        verify(router, never()).executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any());
        assertThat(r.semantic()).isFalse();
        assertThat(stored().get(0).kind()).isEqualTo(ChangeProposalRepository.KIND_LITERAL);
    }

    @Test
    @DisplayName("켜면 치환 결과를 LLM 이 한 번 더 고치고, 가드를 통과하면 semantic 수정안이 된다(before 는 원본, after 는 LLM 본문)")
    void semanticRewriteBecomesASemanticProposal() {
        mentions("c1");
        chunk("c1", "d1", "PostgreSQL 에 접속한다.\ndriver: org.postgresql.Driver\n끝.", "ctx PostgreSQL", null);
        modelSays(rewrite("드라이버 클래스", "Oracle 에 접속한다.\ndriver: oracle.jdbc.OracleDriver\n끝."));

        MaintenanceWorkflow.Report r = workflow.propose(1L, "x", "PostgreSQL", "Oracle", true, Locale.KOREAN);

        assertThat(r.semantic()).isTrue();
        assertThat(r.semanticTried()).isEqualTo(1);
        assertThat(r.semanticProposed()).isEqualTo(1);
        ChangeProposalRepository.NewProposal p = stored().get(0);
        assertThat(p.kind()).isEqualTo(ChangeProposalRepository.KIND_SEMANTIC);
        assertThat(p.beforeText()).contains("PostgreSQL");
        assertThat(p.afterText()).contains("oracle.jdbc.OracleDriver");
        assertThat(p.rationale()).contains("검토 필요").contains("드라이버 클래스");
        assertThat(p.afterContext()).isEqualTo("ctx Oracle");   // 맥락은 계속 글자 치환
    }

    @Test
    @DisplayName("다시 쓰기 프롬프트는 실제 번들로 채워진다 — 바꿀 말·새 말·출력 형식 구분선이 들어간다")
    void rewritePromptComesFromTheRealBundle() {
        mentions("c1");
        chunk("c1", "d1", "PostgreSQL 사용", null, null);
        ArgumentCaptor<java.util.function.Function<org.springframework.ai.chat.model.ChatModel, org.springframework.ai.chat.model.ChatResponse>> fn =
                ArgumentCaptor.forClass(java.util.function.Function.class);
        List<String> systems = new java.util.ArrayList<>();
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), fn.capture())).thenAnswer(inv -> {
            org.springframework.ai.chat.model.ChatModel model = mock(org.springframework.ai.chat.model.ChatModel.class);
            when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(c -> {
                systems.add(((org.springframework.ai.chat.messages.SystemMessage)
                        ((org.springframework.ai.chat.prompt.Prompt) c.getArgument(0)).getInstructions().get(0)).getText());
                return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("x"))));
            });
            ((java.util.function.Function<org.springframework.ai.chat.model.ChatModel, org.springframework.ai.chat.model.ChatResponse>) inv.getArgument(2)).apply(model);
            return new LlmRouter.LlmResult("판정: 변경 없음\n이유: 없음\n---본문---", 1, 1);
        });

        workflow.propose(1L, "x", "PostgreSQL", "Oracle", true, Locale.KOREAN);

        assertThat(systems).hasSize(1);
        assertThat(systems.get(0)).doesNotContain("{from}").doesNotContain("{to}")
                .contains("\"PostgreSQL\"").contains("\"Oracle\"").contains("---본문---").contains("판정:").contains("최소한");
    }

    @Test
    @DisplayName("'변경 없음'이면 리터럴 수정안을 그대로 두고 건너뜀 목록에도 올리지 않는다")
    void noChangeNeededKeepsTheLiteralProposal() {
        mentions("c1");
        chunk("c1", "d1", "PostgreSQL 사용", null, null);
        modelSays("판정: 변경 없음\n이유: 함께 바꿀 것 없음\n---본문---");

        MaintenanceWorkflow.Report r = workflow.propose(1L, "x", "PostgreSQL", "Oracle", true, Locale.KOREAN);

        assertThat(r.semanticTried()).isEqualTo(1);
        assertThat(r.semanticProposed()).isZero();
        assertThat(r.semanticSkipped()).isEmpty();
        assertThat(stored().get(0).kind()).isEqualTo(ChangeProposalRepository.KIND_LITERAL);
        assertThat(stored().get(0).afterText()).isEqualTo("Oracle 사용");
    }

    @Test
    @DisplayName("응답을 읽지 못하거나 호출이 실패하면 리터럴 수정안으로 남고 사유가 건너뜀 목록에 오른다 — 작업은 실패하지 않는다")
    void unreadableOrFailedRewritesFallBackToLiteral() {
        mentions("c1", "c2");
        chunk("c1", "d1", "PostgreSQL 하나", null, null);
        chunk("c2", "d1", "PostgreSQL 둘", null, null);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenReturn(new LlmRouter.LlmResult("잘 모르겠습니다", 1, 1))
                .thenThrow(new IllegalStateException("down"));

        MaintenanceWorkflow.Report r = workflow.propose(1L, "x", "PostgreSQL", "Oracle", true, Locale.KOREAN);

        assertThat(r.proposals()).isEqualTo(2);
        assertThat(r.semanticProposed()).isZero();
        assertThat(r.semanticSkipped()).extracting(MaintenanceWorkflow.Discarded::reason)
                .containsExactly("판정을 읽지 못함", "의미 변경 호출 실패");
        assertThat(stored()).extracting(ChangeProposalRepository.NewProposal::kind)
                .containsOnly(ChangeProposalRepository.KIND_LITERAL);
    }

    @Test
    @DisplayName("너무 긴 청크는 LLM 에 보내지 않고 건너뛴다")
    void tooLongChunksAreSkipped() {
        mentions("c1");
        chunk("c1", "d1", "PostgreSQL " + "가".repeat(MaintenanceWorkflow.MAX_SEMANTIC_CHARS), null, null);

        MaintenanceWorkflow.Report r = workflow.propose(1L, "x", "PostgreSQL", "Oracle", true, Locale.KOREAN);

        verify(router, never()).executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any());
        assertThat(r.semanticTried()).isZero();
        assertThat(r.semanticSkipped().get(0).reason()).contains("너무 길어");
        assertThat(r.proposals()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 작업의 의미 변경 호출은 상한까지 — 넘는 청크는 리터럴만")
    void theNumberOfSemanticCallsIsCapped() {
        String[] ids = new String[MaintenanceWorkflow.MAX_SEMANTIC + 2];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "c" + i;
            chunk(ids[i], "d1", "PostgreSQL " + i, null, null);
        }
        mentions(ids);
        modelSays("판정: 변경 없음\n이유: x\n---본문---");

        MaintenanceWorkflow.Report r = workflow.propose(1L, "x", "PostgreSQL", "Oracle", true, Locale.KOREAN);

        assertThat(r.semanticTried()).isEqualTo(MaintenanceWorkflow.MAX_SEMANTIC);
        assertThat(r.semanticSkipped()).hasSize(2).allSatisfy(d -> assertThat(d.reason()).contains("상한"));
        assertThat(r.proposals()).isEqualTo(ids.length);
    }

    @Test
    @DisplayName("가드 — 바꿀 말이 되살아남 · 새 말이 줄어듦 · 같은 본문 · 빈 본문")
    void guardRejectsReintroducedOrDroppedTerms() {
        String lit = "Oracle 에 접속한다.\ndriver: org.postgresql.Driver\n끝.";
        assertThat(MaintenanceWorkflow.validateRewrite(lit, "", "PostgreSQL", "Oracle")).isEqualTo("본문이 비었다");
        assertThat(MaintenanceWorkflow.validateRewrite(lit, lit, "PostgreSQL", "Oracle")).isEqualTo("치환 결과와 같다");
        assertThat(MaintenanceWorkflow.validateRewrite(lit, "PostgreSQL 에 접속한다.\ndriver: x\n끝.", "PostgreSQL", "Oracle"))
                .isEqualTo("바꿀 말이 되살아났다");
        assertThat(MaintenanceWorkflow.validateRewrite(lit, "DB 에 접속한다.\ndriver: x.jdbc.Driver\n끝.", "PostgreSQL", "Oracle"))
                .isEqualTo("새 말이 줄었다");
        assertThat(MaintenanceWorkflow.validateRewrite(lit, "Oracle 에 접속한다.\ndriver: oracle.jdbc.OracleDriver\n끝.", "PostgreSQL", "Oracle")).isNull();
    }

    @Test
    @DisplayName("가드 — 이미지·페이지 마커·코드 펜스를 어기거나 길이가 크게 달라지거나 너무 많은 줄을 고치면 버린다")
    void guardRejectsStructuralDamageAndOverReach() {
        String lit = "[페이지: 2]\nOracle 설명\n![x](a)\n[이미지: images/a.png]\n```java\nint a;\n```\n끝 문장입니다 하나 둘 셋";
        String ok = lit.replace("int a;", "long a;");
        assertThat(MaintenanceWorkflow.validateRewrite(lit, ok, "PostgreSQL", "Oracle")).isNull();
        assertThat(MaintenanceWorkflow.validateRewrite(lit, ok.replace("[페이지: 2]", "[페이지: 3]"), "PostgreSQL", "Oracle")).isEqualTo("페이지 마커가 바뀜");
        assertThat(MaintenanceWorkflow.validateRewrite(lit, ok.replace("images/a.png", "images/b.png"), "PostgreSQL", "Oracle")).isEqualTo("이미지 마커가 바뀜");
        assertThat(MaintenanceWorkflow.validateRewrite(lit, ok.replace("```java\nlong a;\n```", "```java\nlong a;"), "PostgreSQL", "Oracle")).isEqualTo("코드 펜스가 깨짐");
        // 마커를 지운 요약은 마커 검사가 먼저 잡고, 마커가 없는 본문의 요약·덧붙임은 길이가 잡는다.
        assertThat(MaintenanceWorkflow.validateRewrite(lit, "Oracle", "PostgreSQL", "Oracle")).isEqualTo("이미지 마커가 바뀜");
        String plain = "Oracle 설명 문장입니다 그리고 길게 이어집니다. ".repeat(10);
        assertThat(MaintenanceWorkflow.validateRewrite(plain, "Oracle ".repeat(10), "PostgreSQL", "Oracle")).startsWith("길이가 크게 달라졌다");   // 요약
        assertThat(MaintenanceWorkflow.validateRewrite(plain, plain + "덧붙인 설명 문장입니다. ".repeat(20), "PostgreSQL", "Oracle"))
                .startsWith("길이가 크게 달라졌다");   // 덧붙임
        String lines = String.join("\n", java.util.Collections.nCopies(10, "Oracle 한 줄입니다 번호 "));
        String rewritten = String.join("\n", java.util.Collections.nCopies(10, "Oracle 다시 쓴 줄입니다 번호"));
        assertThat(MaintenanceWorkflow.validateRewrite(lines, rewritten, "PostgreSQL", "Oracle")).startsWith("너무 많은 줄을 고쳤다");
    }

    @Test
    @DisplayName("응답 해석 — 판정·이유·구분선 형식을 읽고, 형식이 깨지면 건너뛴다")
    void interpretRewriteFormats() {
        String lit = "Oracle 에 접속한다.\ndriver: org.postgresql.Driver\n끝.";
        MaintenanceWorkflow.Rewrite ok = MaintenanceWorkflow.interpretRewrite(
                rewrite("드라이버", "Oracle 에 접속한다.\ndriver: oracle.jdbc.OracleDriver\n끝."), lit, "PostgreSQL", "Oracle");
        assertThat(ok.text()).contains("oracle.jdbc.OracleDriver");
        assertThat(ok.reason()).isEqualTo("드라이버");

        assertThat(MaintenanceWorkflow.interpretRewrite("판정: 변경 없음\n이유: x", lit, "PostgreSQL", "Oracle").skipped()).isNull();
        assertThat(MaintenanceWorkflow.interpretRewrite("판정: 변경 필요\n이유: x\n본문만 있음", lit, "PostgreSQL", "Oracle").skipped())
                .isEqualTo("본문 구분선이 없음");
        assertThat(MaintenanceWorkflow.interpretRewrite("판정: 글쎄\n---본문---\nx", lit, "PostgreSQL", "Oracle").skipped()).isEqualTo("판정을 읽지 못함");
        assertThat(MaintenanceWorkflow.interpretRewrite(null, lit, "PostgreSQL", "Oracle").skipped()).isEqualTo("응답 없음");
        assertThat(MaintenanceWorkflow.interpretRewrite(rewrite("r", "Oracle"), lit, "PostgreSQL", "Oracle").skipped()).startsWith("가드 위반");
    }

    @Test
    @DisplayName("가드 — 함께 바뀌는 식별자의 치환은 통과하고, 무관한 구절을 지운 것은 버린다(실측에서 모델이 지운 것)")
    void guardRejectsDeletionsButAllowsSubstitutions() {
        String lit = "입력된 예외의 원인이 SQLException인 경우 PostgreSQL DBMS Lock에러여부를 반환한다.\ndriver: org.postgresql.Driver\n끝.";
        // 치환 — 사라진 낱말과 생긴 낱말이 같은 수
        assertThat(MaintenanceWorkflow.validateRewrite(lit,
                lit.replace("org.postgresql.Driver", "oracle.jdbc.OracleDriver"), "Oracle2", "PostgreSQL")).isNull();
        // 삭제 — 무관한 구절을 통째로 지움(길이·줄 수 가드는 통과한다)
        String deleted = lit.replace("SQLException인 경우 ", "");
        assertThat(MaintenanceWorkflow.validateRewrite(lit, deleted, "Oracle2", "PostgreSQL")).startsWith("내용을 지웠다");
        // 한 낱말 정도의 재배열·조사 변화는 허용(슬랙)
        assertThat(MaintenanceWorkflow.validateRewrite(lit, lit.replace("반환한다.", "반환."), "Oracle2", "PostgreSQL")).isNull();
    }

    @Test
    @DisplayName("낱말 다중집합 차이 — 순서·줄바꿈은 세지 않고 개수는 센다")
    void tokenChurnIsAMultisetDifference() {
        assertThat(MaintenanceWorkflow.tokenChurn("a b c", "c\nb a")).containsExactly(0, 0);
        assertThat(MaintenanceWorkflow.tokenChurn("a a b", "a b")).containsExactly(1, 0);
        assertThat(MaintenanceWorkflow.tokenChurn("a b", "a x y")).containsExactly(1, 2);
    }
}
