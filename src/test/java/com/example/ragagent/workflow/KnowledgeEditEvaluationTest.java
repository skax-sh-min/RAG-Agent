package com.example.ragagent.workflow;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.OpenAiClients;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.llm.LlmPing;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingWire;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.ChunkDiff;
import com.example.ragagent.service.CrossEncoderReranker;
import com.example.ragagent.service.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §6.33 6c <b>자연어 지식 수정 사전 평가</b>(AGENT_MCP_DESIGN §10.12) — 실제 {@link KnowledgeEditWorkflow}(실제 번들 프롬프트 · 실제 응답
 * 읽기 · 인용 검증 · 가드 G3/G4/G10 · {@link AnchoredEdit})를 로컬 서버에 붙여 {@code agent-eval/edit-gold.json} 을 돌린다.
 *
 * <p>잰다: ① 지시 해석(유형·대상 — 지시만 주고 {@code interpret}) ② 대상 recall·precision(설명함 판정, <b>정답 의도</b>로 돌려 ①의 실수가 섞이지
 * 않게) ③ 가드 통과율(대상으로 고른 청크 중 수정안이 된 비율, "바꿀 곳 없음" 판단은 따로 센다) ④ 사람 수용률 — 수정안마다 Diff 를
 * {@code review.md} 로 내보낸다(라벨은 사람이 단다).
 *
 * <p>고정한 것: 후보 풀(골든셋의 청크 = 전수 조회가 낼 청크 + 방해 후보 2개, 본문은 {@code -Dedit-eval.db} 의 memory.db 복사본에서), 저장소(임시
 * SQLite). 맥락·키워드 재생성(⑧)은 끈다 — 인덱싱 프롬프트라 이 평가의 대상이 아니다. 리랭커는 {@code -Dedit-eval.rerank-url} 이 있으면
 * 실제 서버(앱과 같이 후보 순서·{@code add_note} 1순위를 정한다).
 *
 * <pre>
 * mvn test -Dtest=KnowledgeEditEvaluationTest -Dedit-eval.enabled=true -Dedit-eval.db=PATH\memory.db
 *     -Dedit-eval.base-url=http://127.0.0.1:1234/v1 -Dedit-eval.rerank-url=http://127.0.0.1:28095 -Dedit-eval.label=gemma-4-12b
 * </pre>
 * 선택: {@code -Dedit-eval.model}(없으면 서버의 첫 모델) · {@code -Dedit-eval.cases=id1,id2}(일부만). 결과: {@code target/edit-eval/<label>/}.
 */
@EnabledIfSystemProperty(named = "edit-eval.enabled", matches = "true")
class KnowledgeEditEvaluationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;

    /** 한 번의 LLM 호출 — 판정 호출을 청크에 되짚는 데 쓴다. */
    private record Call(String system, String user, String response, long millis) {}

    @Test
    void reportKnowledgeEditQuality() throws Exception {
        String baseUrl = System.getProperty("edit-eval.base-url", "http://127.0.0.1:1234/v1");
        String modelId = System.getProperty("edit-eval.model", "");
        if (modelId.isBlank()) modelId = LlmPing.listModelIds(baseUrl, "local", 5, 10).get(0);
        String rerankUrl = System.getProperty("edit-eval.rerank-url", "");
        String dbPath = System.getProperty("edit-eval.db");
        if (dbPath == null || dbPath.isBlank()) throw new IllegalStateException("-Dedit-eval.db=<memory.db 복사본> 이 필요합니다");
        String label = System.getProperty("edit-eval.label", "run");
        String only = System.getProperty("edit-eval.cases", "");
        Path out = Path.of("target", "edit-eval", label);
        Files.createDirectories(out);

        // ── LLM — 앱의 체인이 하는 일(표시를 걷고 생각 끔)만 흉내 낸다 ──
        OpenAiChatModel raw = OpenAiClients.chatModel(OpenAiClients.clientOptions(baseUrl, "local", 5, 300, 0),
                OpenAiChatOptions.builder().model(modelId).build());
        ThinkingWire off = new ThinkingWire(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)), null, ThinkingWire.Sent.OFF);
        String model = modelId;
        List<Call> calls = new ArrayList<>();
        ChatModel chat = new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                OpenAiChatOptions o = ThinkingControl.applyTo((OpenAiChatOptions) prompt.getOptions(), off);
                return raw.call(new Prompt(prompt.getInstructions(), o.mutate().model(model).build()));
            }
        };
        LlmRouter router = mock(LlmRouter.class);
        when(router.localTier1Concurrency()).thenReturn(Optional.empty());
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any())).thenAnswer(inv -> {
            Function<ChatModel, org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            List<Message> captured = new ArrayList<>();
            ChatModel recording = new ChatModel() {
                @Override
                public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                    captured.addAll(prompt.getInstructions());
                    return chat.call(prompt);
                }
            };
            long t0 = System.nanoTime();
            org.springframework.ai.chat.model.ChatResponse r = fn.apply(recording);
            String text = r.getResult().getOutput().getText();
            calls.add(new Call(captured.get(0).getText(), captured.size() > 1 ? captured.get(1).getText() : "", text,
                    (System.nanoTime() - t0) / 1_000_000));
            return new LlmRouter.LlmResult(text, 0, 0);
        });

        ResourceBundleMessageSource bundle = new ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        AppProperties props = mock(AppProperties.class);
        AppProperties.LlmConfig llm = mock(AppProperties.LlmConfig.class);
        when(llm.temperature()).thenReturn(0.2);      // application.properties 출하값
        when(llm.maxTokens()).thenReturn(12_000);
        when(props.llmSafe()).thenReturn(llm);
        Optional<CrossEncoderReranker> reranker = rerankUrl.isBlank() ? Optional.empty() : Optional.of(new CrossEncoderReranker(
                new AppProperties("./data", 2, 800, 100, 100, 10, 0.0, true, 0, false, true, false, 3,
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, new AppProperties.RerankerConfig(rerankUrl, null, null, 60, null))));

        JsonNode gold;
        try (var in = new ClassPathResource("agent-eval/edit-gold.json").getInputStream()) {
            gold = JSON.readTree(in);
        }
        String judgeMarker = bundle.getMessage("prompt.edit.judge", null, Locale.KOREAN).substring(0, 20);
        String intentMarker = bundle.getMessage("prompt.edit.intent", null, Locale.KOREAN).substring(0, 20);

        StringBuilder summary = new StringBuilder();
        StringBuilder review = new StringBuilder("# 6c 수정안 검토 — " + label + " (" + modelId + ")\n\n"
                + "각 수정안을 읽고 `수용` 칸에 O(고치지 않고 승인할 만함) / X 를 적는다. 자동 점검은 참고용이다.\n\n");
        List<Map<String, Object>> rawRows = new ArrayList<>();
        Map<String, int[]> perType = new LinkedHashMap<>();   // type → [interpTypeOk, interpSubjOk, n, gD, jD, hit, targets, proposals, noChange, guardDrop, autoOk]

        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // ── ① 해석 — 유형 넷 사례 + 해석 전용 사례 ──
            List<JsonNode> interpCases = new ArrayList<>();
            gold.get("cases").forEach(interpCases::add);
            gold.get("interpretOnly").forEach(interpCases::add);
            int interpN = 0, interpTypeOk = 0, interpSubjOk = 0, interpRejected = 0;
            long interpMs = 0;
            summary.append("## ① 지시 해석\n\n| 사례 | 기대 | 모델 | 유형 | 대상 | 비고 |\n|---|---|---|---|---|---|\n");
            KnowledgeEditWorkflow wf0 = workflow(mock(KeywordSearchRepository.class), mock(HybridSearchService.class), Optional.empty(),
                    mock(AdminService.class), router, bundle, props, tmp.resolve("interp.db"));
            long t0 = System.currentTimeMillis();
            int interpTotal = 0;
            for (JsonNode c : interpCases) {
                if (only.isBlank() || List.of(only.split(",")).contains(c.get("id").asString())) interpTotal++;
            }
            for (JsonNode c : interpCases) {
                if (!only.isBlank() && !List.of(only.split(",")).contains(c.get("id").asString())) continue;
                JsonNode exp = c.get("expect");
                String expType = exp.get("type").asString();
                calls.clear();
                String note = "";
                EditIntent got = null;
                try {
                    got = wf0.interpret(c.get("instruction").asString(), Locale.KOREAN);
                } catch (IllegalArgumentException e) {
                    note = e.getMessage();
                    interpRejected++;
                }
                Call call = calls.stream().filter(x -> x.system().startsWith(intentMarker)).findFirst().orElse(null);
                if (call != null) interpMs += call.millis();
                EditIntent parsed = got;
                if (parsed == null && call != null) {
                    try { parsed = EditIntent.parse(call.response()); } catch (IllegalArgumentException ignored) { }
                }
                String gotType = parsed == null ? "(읽지 못함)" : parsed.type();
                boolean typeOk = expType.equals(gotType);
                boolean subjOk = subjectOk(exp, parsed);
                // unclear 를 기대한 지시는 거절(예외)이 정답이다 — 거절되지 않고 어떤 유형으로 실행되면 틀린 것
                if ("unclear".equals(expType)) { typeOk = got == null; subjOk = typeOk; }
                // 맞는 유형·대상인데 근거 검사(지시문에 없는 말)로 거절됐으면 실행되지 않는다 — 틀린 것으로 센다
                if (got == null && !"unclear".equals(expType)) { typeOk = false; subjOk = false; }
                interpN++;
                if (typeOk) interpTypeOk++;
                if (subjOk) interpSubjOk++;
                int[] pt = perType.computeIfAbsent(expType, k -> new int[11]);
                if (typeOk) pt[0]++;
                if (subjOk) pt[1]++;
                pt[2]++;
                summary.append(String.format("| %s | %s %s | %s | %s | %s | %s |%n", c.get("id").asString(), expType,
                        exp.path("subject").asString(exp.path("from").asString("")),
                        parsed == null ? "(읽지 못함)" : esc(parsed.type() + " " + nz(parsed.subject()) + nz(parsed.from())
                                + (parsed.content() == null ? "" : " / " + parsed.content())
                                + (parsed.replacement() == null ? "" : " / 대안 " + parsed.replacement())
                                + (parsed.to() == null ? "" : " → " + parsed.to())),
                        typeOk ? "O" : "X", subjOk ? "O" : "X", esc(note)));
                progress(t0, "해석 %d/%d %s → %s (유형 %s · 대상 %s)", interpN, interpTotal, c.get("id").asString(), gotType,
                        typeOk ? "O" : "X", subjOk ? "O" : "X");
            }
            summary.insert(0, String.format("# 6c 사전 평가 — %s%n%n모델 `%s` · 서버 %s · 리랭커 %s · 생각 끔 · 온도 0.2%n%n"
                            + "**해석** %d건: 유형 %d (%.1f%%) · 대상까지 %d (%.1f%%) · 거절 %d · 호출당 %d ms%n%n",
                    label, modelId, baseUrl, rerankUrl.isBlank() ? "없음" : rerankUrl, interpN, interpTypeOk, pct(interpTypeOk, interpN),
                    interpSubjOk, pct(interpSubjOk, interpN), interpRejected, interpMs / Math.max(1, interpN)));

            // ── ②③④ 정답 의도로 수정안 만들기 ──
            summary.append("\n## ②③ 대상 판정과 가드\n\n| 사례 | 정답 D | 판정 D | 맞음 | recall | precision | 대상 | 수정안 | 바꿀 곳 없음 | 버림(가드 등) |\n"
                    + "|---|---|---|---|---|---|---|---|---|---|\n");
            int totGD = 0, totJD = 0, totHit = 0, totTargets = 0, totProps = 0, totNoChange = 0, totDropped = 0, totFallback = 0;
            long judgeMs = 0, editMs = 0;
            int judgeCalls = 0, editCalls = 0;
            List<String> discardLines = new ArrayList<>();
            List<String> judgeMisses = new ArrayList<>();
            int pidx = 0;
            int caseTotal = 0, caseNo = 0;
            for (JsonNode c : gold.get("cases")) {
                if (only.isBlank() || List.of(only.split(",")).contains(c.get("id").asString())) caseTotal++;
            }
            for (JsonNode c : gold.get("cases")) {
                String id = c.get("id").asString();
                if (!only.isBlank() && !List.of(only.split(",")).contains(id)) continue;
                caseNo++;
                progress(t0, "사례 %d/%d %s 시작 (풀 %d청크)", caseNo, caseTotal, id, c.get("pool").size());
                JsonNode exp = c.get("expect");
                EditIntent intent = new EditIntent(exp.get("type").asString(), exp.get("subject").asString(),
                        exp.path("content").asString(null), exp.path("replacement").asString(null), null, null);
                String instruction = c.get("instruction").asString();

                // 풀 — 본문은 DB 복사본에서
                Map<String, String> labels = new LinkedHashMap<>();
                Map<String, Document> docs = new LinkedHashMap<>();
                Map<String, AdminService.ChunkRow> rows = new HashMap<>();
                for (JsonNode p : c.get("pool")) {
                    String cid = p.get("chunkId").asString();
                    labels.put(cid, p.get("label").asString());
                    Object[] chunk = load(db, cid);
                    @SuppressWarnings("unchecked") Map<String, String> meta = (Map<String, String>) chunk[1];
                    docs.put(cid, Document.builder().id(cid).text((String) chunk[0]).metadata(new HashMap<>(meta)).build());
                    rows.put(cid, new AdminService.ChunkRow(cid, (String) chunk[0], (String) chunk[0], meta));
                }
                String subject = intent.subject();
                List<Document> literal = docs.values().stream()
                        .filter(d -> d.getText().toLowerCase(Locale.ROOT).contains(subject.toLowerCase(Locale.ROOT))).toList();
                List<Document> semantic = docs.values().stream().filter(d -> !literal.contains(d)).toList();

                KeywordSearchRepository keywords = mock(KeywordSearchRepository.class);
                when(keywords.findMentions(eq("curated"), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(List.of(), false, false));
                when(keywords.findMentions(eq("latest"), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(literal, false, false));
                HybridSearchService hybrid = mock(HybridSearchService.class);
                when(hybrid.withStoredText(any())).thenAnswer(inv -> inv.getArgument(0));
                when(hybrid.search(any())).thenReturn(new HybridSearchService.SearchResult(semantic, Map.of()));
                AdminService admin = mock(AdminService.class);
                when(admin.collectionFor(anyString())).thenReturn("latest");
                when(admin.getChunk(eq("latest"), anyString())).thenAnswer(inv -> rows.get((String) inv.getArgument(1)));

                Path dbFile = tmp.resolve(id + ".db");
                KnowledgeEditWorkflow wf = workflow(keywords, hybrid, reranker, admin, router, bundle, props, dbFile);
                var jdbc = SqliteTestDatabase.open(dbFile);
                ChangeJobRepository jobs = new ChangeJobRepository(jdbc);
                ChangeProposalRepository proposals = new ChangeProposalRepository(jdbc);
                long jobId = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, instruction, "eval");
                calls.clear();
                KnowledgeEditWorkflow.Report report = wf.propose(jobId, instruction, intent, Locale.KOREAN);

                // 판정 되짚기 — 판정 호출의 사용자 메시지에 실린 청크로
                Map<String, String> judged = new LinkedHashMap<>();
                for (String cid : labels.keySet()) judged.put(cid, KnowledgeEditWorkflow.ROLE_UNRELATED);   // 묻지 않은 청크 = 무관(단어 경계 밖)
                for (Call call : calls) {
                    if (call.system().startsWith(judgeMarker)) {
                        judgeCalls++;
                        judgeMs += call.millis();
                        for (Document d : docs.values()) {
                            String head = d.getText().substring(0, Math.min(300, d.getText().length()));
                            if (call.user().contains(head)) {
                                judged.put(d.getId(), KnowledgeEditWorkflow.parseJudgement(call.response(), d.getText(), d, null).role());
                                break;
                            }
                        }
                    } else if (!call.system().startsWith(intentMarker)) {
                        editCalls++;
                        editMs += call.millis();
                    }
                }
                int gD = 0, jD = 0, hit = 0;
                for (var e : labels.entrySet()) {
                    String gl = e.getValue();
                    if ("A".equals(gl)) continue;
                    boolean isD = "D".equals(gl);
                    boolean saidD = KnowledgeEditWorkflow.ROLE_DESCRIBES.equals(judged.get(e.getKey()));
                    if (isD) gD++;
                    if (saidD) jD++;
                    if (isD && saidD) hit++;
                    if (isD != saidD) judgeMisses.add(String.format("%s %s 정답 %s · 판정 %s (%s)", id, e.getKey().substring(0, 8), gl,
                            judged.get(e.getKey()), rows.get(e.getKey()).metadata().getOrDefault(MetaKey.HEADING, "")));
                }
                List<ChangeProposalRepository.Proposal> made = proposals.listByJob(jobId);
                int noChange = 0, dropped = 0;
                for (KnowledgeEditWorkflow.Discarded d : report.discarded()) {
                    if (d.reason().startsWith("모델이 바꿀 곳이 없다")) noChange++;
                    else dropped++;
                    discardLines.add(String.format("%s %s [정답 %s] %s", id, d.chunkId().substring(0, 8), labels.get(d.chunkId()), d.reason()));
                }
                int targets = made.size() + report.discarded().size();
                totGD += gD; totJD += jD; totHit += hit; totTargets += targets; totProps += made.size();
                totNoChange += noChange; totDropped += dropped; totFallback += report.fallbacks();
                int[] pt = perType.get(intent.type());
                pt[3] += gD; pt[4] += jD; pt[5] += hit; pt[6] += targets; pt[7] += made.size(); pt[8] += noChange; pt[9] += dropped;
                summary.append(String.format("| %s | %d | %d | %d | %s | %s | %d | %d | %d | %d |%n", id, gD, jD, hit,
                        ratio(hit, gD), ratio(hit, jD), targets, made.size(), noChange, dropped));
                progress(t0, "사례 %d/%d %s 끝 — LLM 호출 %d · 판정 D %d/정답 %d · 대상 %d · 수정안 %d · 버림 %d",
                        caseNo, caseTotal, id, calls.size(), jD, gD, targets, made.size(), noChange + dropped);

                // ④ 검토 파일
                for (ChangeProposalRepository.Proposal p : made) {
                    pidx++;
                    String auto = autoCheck(intent, p);
                    if (auto.startsWith("OK")) pt[10]++;
                    ChunkDiff.Result diff = ChunkDiff.compare(p.beforeText(), p.afterText());
                    review.append(String.format("## P%d · %s · %s%n%n- 지시: %s%n- 청크 `%s` (%s) · 정답 라벨 **%s** · 종류 `%s`%n- 근거: %s%n"
                                    + "- 자동 점검: %s%n- 수용: ☐%n%n```diff%n%s%n```%n%n",
                            pidx, id, intent.type(), instruction, p.chunkId(), rows.get(p.chunkId()).metadata().getOrDefault(MetaKey.HEADING, ""),
                            labels.get(p.chunkId()), p.kind(), p.rationale(), auto,
                            ChangeProposalRepository.KIND_DELETE_CHUNK.equals(p.kind()) ? "(청크 전체 삭제)\n" + p.beforeText()
                                    : UnifiedDiffFormatter.format(diff).text()));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("p", "P" + pidx); row.put("case", id); row.put("chunk", p.chunkId()); row.put("label", labels.get(p.chunkId()));
                    row.put("kind", p.kind()); row.put("auto", auto); row.put("before", p.beforeText()); row.put("after", p.afterText());
                    rawRows.add(row);
                }
            }
            summary.append(String.format("%n**판정**: 정답 설명함 %d · 판정 설명함 %d · 맞음 %d → recall %s · precision %s (애매 라벨 제외)%n",
                    totGD, totJD, totHit, ratio(totHit, totGD), ratio(totHit, totJD)));
            summary.append(String.format("**가드**: 대상 %d → 수정안 %d · 바꿀 곳 없음 %d · 버림 %d → 통과율(바꿀 곳 없음 제외) %s · 다듬은 문장 대체 %d%n",
                    totTargets, totProps, totNoChange, totDropped, ratio(totProps, totTargets - totNoChange), totFallback));
            summary.append(String.format("**지연**: 판정 %d회 평균 %d ms · 편집/다듬기 %d회 평균 %d ms%n",
                    judgeCalls, judgeMs / Math.max(1, judgeCalls), editCalls, editMs / Math.max(1, editCalls)));

            summary.append("\n## 유형별\n\n| 유형 | 해석 유형 | 해석 대상 | recall | precision | 가드 통과 | 자동 점검 통과 |\n|---|---|---|---|---|---|---|\n");
            for (var e : perType.entrySet()) {
                int[] v = e.getValue();
                summary.append(String.format("| %s | %d/%d | %d/%d | %s | %s | %s | %s |%n", e.getKey(), v[0], v[2], v[1], v[2],
                        ratio(v[5], v[3]), ratio(v[5], v[4]), ratio(v[7], v[6] - v[8]), ratio(v[10], v[7])));
            }
            summary.append("\n## 판정이 정답과 다른 청크\n\n");
            judgeMisses.forEach(m -> summary.append("- ").append(esc(m)).append('\n'));
            summary.append("\n## 대상이었지만 수정안이 되지 않은 청크\n\n");
            discardLines.forEach(m -> summary.append("- ").append(esc(m)).append('\n'));
        }
        Files.writeString(out.resolve("summary.md"), summary, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("review.md"), review, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("proposals.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rawRows), StandardCharsets.UTF_8);
        System.out.println(summary);
        System.out.println("결과: " + out.toAbsolutePath());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private static KnowledgeEditWorkflow workflow(KeywordSearchRepository keywords, HybridSearchService hybrid,
                                                  Optional<CrossEncoderReranker> reranker, AdminService admin, LlmRouter router,
                                                  ResourceBundleMessageSource bundle, AppProperties props, Path dbFile) throws Exception {
        var jdbc = SqliteTestDatabase.open(dbFile);
        return new KnowledgeEditWorkflow(keywords, hybrid, reranker, admin, router, new ChangeJobRepository(jdbc),
                new ChangeProposalRepository(jdbc), mock(MaintenanceWorkflow.class), bundle, props, Duration.ofMillis(1), Duration.ofMillis(1));
    }

    /** 청크 본문과 메타데이터(문자열로) — {@code vec_document_chunks}. */
    private static Object[] load(Connection db, String id) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("SELECT content, metadata FROM vec_document_chunks WHERE spring_doc_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("DB 복사본에 청크가 없습니다(골든셋과 코퍼스가 다름): " + id);
                Map<String, String> meta = new HashMap<>();
                JSON.readTree(rs.getString(2)).properties().forEach(e -> meta.put(e.getKey(),
                        e.getValue().isString() ? e.getValue().asString() : e.getValue().toString()));
                meta.putIfAbsent(MetaKey.VERSION, "latest");
                return new Object[]{rs.getString(1), meta};
            }
        }
    }

    /** 진행 한 줄 — 결과 파일은 끝에 한꺼번에 쓰므로, 긴 런에서 어디까지 왔는지는 이것으로만 안다. */
    private static void progress(long t0, String fmt, Object... args) {
        long s = (System.currentTimeMillis() - t0) / 1000;
        System.out.printf("[edit-eval %02d:%02d:%02d] %s%n", s / 3600, s / 60 % 60, s % 60, String.format(fmt, args));
        System.out.flush();
    }

    private static boolean subjectOk(JsonNode exp, EditIntent got) {
        if (got == null || !exp.has("subject") && !exp.has("from")) return false;
        if ("replace".equals(exp.get("type").asString())) {
            return EditIntent.norm(exp.get("from").asString()).equals(EditIntent.norm(got.from()))
                    && EditIntent.norm(exp.get("to").asString()).equals(EditIntent.norm(got.to()));
        }
        String s = EditIntent.norm(got.subject());
        if (s.equals(EditIntent.norm(exp.get("subject").asString()))) return true;
        for (JsonNode alt : exp.path("subjectAlternates")) if (s.equals(EditIntent.norm(alt.asString()))) return true;
        return false;
    }

    /** 기계적으로 볼 수 있는 것만 — 사람 라벨을 대신하지 않는다. */
    private static String autoCheck(EditIntent intent, ChangeProposalRepository.Proposal p) {
        String after = p.afterText() == null ? "" : p.afterText();
        String before = p.beforeText();
        return switch (intent.type()) {
            case EditIntent.DEPRECATE -> after.contains("**사용 중단**") ? "OK 안내 블록 있음" : "확인 필요 — 안내 블록 없음";
            case EditIntent.ADD_NOTE -> after.contains("**주의**") ? "OK 주의 블록 있음" : "확인 필요 — 주의 블록 없음";
            case EditIntent.REMOVE -> {
                if (ChangeProposalRepository.KIND_DELETE_CHUNK.equals(p.kind())) yield "OK 청크 삭제(제목에 대상)";
                boolean still = ImpactAnalysisWorkflow.hasStandaloneOccurrence(after, intent.subject());
                int removed = before.length() - after.length();
                yield (still ? "확인 필요 — 대상이 아직 남음" : "OK 대상이 사라짐") + String.format(" · 지운 글자 %d (원문의 %.0f%%)",
                        removed, 100.0 * removed / Math.max(1, before.length()));
            }
            case EditIntent.REVISE -> after.contains(intent.content()) ? "OK 새 값이 들어감" : "확인 필요 — 새 값 \"" + intent.content() + "\" 이 없음";
            default -> "-";
        };
    }

    private static String ratio(int a, int b) {
        return b == 0 ? "-" : String.format("%.2f", a / (double) b);
    }

    private static double pct(int a, int b) {
        return b == 0 ? 0 : 100.0 * a / b;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
