package com.example.ragagent.evaluation;

import com.example.ragagent.tool.catalog.CatalogQueryTool;
import com.example.ragagent.tool.change.DiffTool;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENT_MCP_DESIGN §7 Phase 3 <b>사전 평가</b> — 구현 전에 "Tool 선택을 네이티브 Tool Calling 으로 할지"를 정하는 측정이다. 결과와 판단은
 * {@code documents/AGENT_EVALUATION.md}.
 *
 * <p>앱 컨텍스트도 DB 도 쓰지 않는다. OpenAI 호환 서버({@code llama-server})에 앱이 보낼 모양 그대로 — 계획 프롬프트
 * ({@code agent-eval/plan-prompt.txt}) + 질문 + {@code tools} + {@code parallel_tool_calls=false} + 생각 끔({@code chat_template_kwargs})
 * — 요청하고 <b>첫 {@code tool_call}</b> 만 읽는다. Tool 정의는 1b 의 실제 {@code @Tool} 클래스에서 만든다(스키마를 손으로 쓰지 않는다).
 * 아직 없는 {@code analyzeImpact} · {@code requestDocumentChange} 는 설계서 §3.3 의 이름·설명·인자로 만든 스텁이다.
 *
 * <p>기본적으로 skip 이고 {@code -Dtool-eval.enabled=true} 로만 돈다.
 * <pre>
 * mvn test -Dtest=ToolCallingEvaluationTest -Dtool-eval.enabled=true \
 *     [-Dtool-eval.base-url=http://127.0.0.1:1234/v1] [-Dtool-eval.model=gemma-4-E2B] [-Dtool-eval.api-key=…] \
 *     [-Dtool-eval.label=e2b] [-Dtool-eval.repeat=1] [-Dtool-eval.thinking=off|server] [-Dtool-eval.out=target/agent-eval]
 * </pre>
 * {@code thinking=off}(기본)은 {@code chat_template_kwargs.enable_thinking=false} 를 싣는다 — 이 필드를 무시하는 서버(LM Studio)에서는
 * {@code server} 로 두고 지연이 부풀어 있음을 감안한다.
 *
 * <p>{@code -Dtool-eval.mode=json} 은 네이티브 Tool Calling 대신 <b>프롬프트 JSON</b> 방식이다(설계서 판단표 둘째 줄) — {@code tools} 를 싣지
 * 않고 도구 목록을 프롬프트에 적어 {@code {"tool":…,"arguments":{…}}} 한 줄을 받는다. {@code -Dtool-eval.json-format=true} 는 거기에
 * {@code response_format=json_object} 를 더한다.
 *
 * <p>지표(케이스마다): 고른 Tool(없으면 {@code NONE}), 정답 여부, 인자 유효(JSON 이고 필수 인자가 있고 모르는 인자가 없다), 형식 실패(본문에
 * {@code [TOOL_REQUEST]}/{@code <tool_call>} 이 보이거나 잘려서 Tool 호출이 없다), 지연.
 */
@EnabledIfSystemProperty(named = "tool-eval.enabled", matches = "true")
class ToolCallingEvaluationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOLD = "agent-eval/tool-gold.json";
    private static final String DOC_GOLD = "search-eval/nexcore-gold.json";
    private static final String PROMPT = "agent-eval/plan-prompt.txt";
    private static final String PROMPT_JSON = "agent-eval/plan-prompt-json.txt";
    /** 앱이 계획 호출에 실을 출력 예약의 후보 — 한 줄짜리 호출이라 작다. */
    private static final int MAX_TOKENS = 256;

    /** 아직 없는 Phase 4 Tool 의 스텁 — 설계서 §3.3 의 이름 · 설명 · 인자. */
    static class Phase4Stubs {
        @Tool(name = "analyzeImpact",
                description = "Analyze which documents are affected when something is changed or deleted. Starts an analysis job.")
        public String analyzeImpact(
                @ToolParam(description = "What is being changed or removed, e.g. a class or method name (required)") String subject,
                @ToolParam(required = false, description = "Short note about the planned change") String changeNote) {
            return "";
        }

        @Tool(name = "requestDocumentChange",
                description = "Request a change to document text (administrators only). Only proposes a change; nothing is written until an administrator approves.")
        public String requestDocumentChange(
                @ToolParam(description = "What should be changed (required)") String instruction,
                @ToolParam(required = false, description = "Exact text to replace, for literal replacement") String from,
                @ToolParam(required = false, description = "Replacement text, for literal replacement") String to) {
            return "";
        }
    }

    record Case(String id, String kind, String expected, String question) {}

    private final String baseUrl = System.getProperty("tool-eval.base-url", "http://127.0.0.1:1234/v1").replaceAll("/+$", "");
    private final String model = System.getProperty("tool-eval.model", "gemma-4-E2B");
    private final String apiKey = System.getProperty("tool-eval.api-key", "");
    private final String label = System.getProperty("tool-eval.label", "run");
    private final int repeat = Integer.getInteger("tool-eval.repeat", 1);
    private final boolean jsonMode = "json".equals(System.getProperty("tool-eval.mode", "native"));
    private final boolean jsonFormat = Boolean.getBoolean("tool-eval.json-format");
    private final boolean thinkingOff = !"server".equals(System.getProperty("tool-eval.thinking", "off"));
    private final Path out = Path.of(System.getProperty("tool-eval.out", "target/agent-eval"), label);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void reportToolSelection() throws Exception {
        List<ToolCallback> tools = Arrays.asList(ToolCallbacks.from(
                new KnowledgeSearchTool(null, Optional.empty(), null, null, null),
                new CatalogQueryTool(null, null), new Phase4Stubs()));
        // 카탈로그의 나머지 읽기 Tool(getChunkContext · getDocument · generateDiff)은 Q&A 에 보이지 않는다(설계서 §3.2).
        List<String> shown = List.of("searchKnowledge", "findRelatedDocuments", "queryCatalog", "analyzeImpact", "requestDocumentChange");
        tools = tools.stream().filter(t -> shown.contains(t.getToolDefinition().name())).toList();
        assertThat(tools).extracting(t -> t.getToolDefinition().name()).containsExactlyInAnyOrderElementsOf(shown);
        Map<String, JsonNode> schemas = new LinkedHashMap<>();
        for (ToolCallback t : tools) schemas.put(t.getToolDefinition().name(), JSON.readTree(t.getToolDefinition().inputSchema()));

        // -Dtool-eval.prompt=none 은 계획 프롬프트 없이 질문과 tools 만 보낸다 — 프롬프트의 기여를 가르는 대조군.
        String system = "none".equals(System.getProperty("tool-eval.prompt", "file")) ? ""
                : new String(new ClassPathResource(jsonMode ? PROMPT_JSON : PROMPT).getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (jsonMode) {
            // 도구 목록 = 이름 · 설명 · 인자(필수 표시). 스키마를 사람이 읽는 한 줄로 줄인다.
            StringBuilder list = new StringBuilder();
            for (ToolCallback t : tools) {
                JsonNode schema = schemas.get(t.getToolDefinition().name());
                List<String> params = new ArrayList<>();
                List<String> required = new ArrayList<>();
                schema.path("required").forEach(r -> required.add(r.asText()));
                for (String name : schema.path("properties").propertyNames()) params.add(name + (required.contains(name) ? "(필수)" : ""));
                list.append("- ").append(t.getToolDefinition().name()).append(": ").append(t.getToolDefinition().description())
                        .append(" 인자: ").append(String.join(", ", params)).append('\n');
            }
            system = system.replace("{tools}", list.toString().stripTrailing());
        }
        List<Case> cases = loadCases();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (int rep = 0; rep < repeat; rep++) {
            for (Case c : cases) {
                Map<String, Object> row = runOne(c, system, tools, schemas);
                row.put("rep", rep);
                rows.add(row);
                emit(row);
            }
        }
        print(rows, cases.size());
        assertThat(rows).isNotEmpty();
    }

    private List<Case> loadCases() throws IOException {
        List<Case> cases = new ArrayList<>();
        try (var in = new ClassPathResource(DOC_GOLD).getInputStream()) {
            for (JsonNode n : JSON.readTree(in).get("cases")) {
                cases.add(new Case(n.get("id").asText(), "doc", "searchKnowledge", n.get("question").asText()));
            }
        }
        try (var in = new ClassPathResource(GOLD).getInputStream()) {
            for (JsonNode n : JSON.readTree(in).get("cases")) {
                cases.add(new Case(n.get("id").asText(), n.get("kind").asText(), n.get("expected").asText(), n.get("question").asText()));
            }
        }
        return cases;
    }

    private Map<String, Object> runOne(Case c, String system, List<ToolCallback> tools, Map<String, JsonNode> schemas) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", system.isEmpty()
                ? List.of(Map.of("role", "user", "content", c.question()))
                : List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", c.question())));
        if (!jsonMode) {
            List<Map<String, Object>> defs = new ArrayList<>();
            for (ToolCallback t : tools) {
                defs.add(Map.of("type", "function", "function", Map.of(
                        "name", t.getToolDefinition().name(),
                        "description", t.getToolDefinition().description(),
                        "parameters", schemas.get(t.getToolDefinition().name()))));
            }
            body.put("tools", defs);
            body.put("parallel_tool_calls", false);
        } else if (jsonFormat) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        body.put("temperature", 0.2);
        body.put("max_tokens", MAX_TOKENS);
        body.put("stream", false);
        if (thinkingOff) body.put("chat_template_kwargs", Map.of("enable_thinking", false));

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json");
        if (!apiKey.isBlank()) rb.header("Authorization", "Bearer " + apiKey);
        long t0 = System.nanoTime();
        HttpResponse<String> res = http.send(rb.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        long ms = (System.nanoTime() - t0) / 1_000_000;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case", c.id());
        row.put("kind", c.kind());
        row.put("expected", c.expected());
        row.put("question", c.question());
        row.put("ms", ms);
        row.put("http", res.statusCode());
        String chosen = "NONE";
        boolean argsValid = true;
        boolean formatFailure = false;
        String finish = "";
        String content = "";
        String args = "";
        if (res.statusCode() / 100 == 2) {
            JsonNode choice = JSON.readTree(res.body()).path("choices").path(0);
            finish = choice.path("finish_reason").asText("");
            JsonNode msg = choice.path("message");
            content = msg.path("content").asText("");
            JsonNode calls = msg.path("tool_calls");
            if (jsonMode) {
                // 프롬프트 JSON: 본문에서 첫 { ~ 마지막 } 를 읽는다. 읽지 못하거나 모르는 도구 이름이면 형식 실패.
                int a = content.indexOf('{');
                int b = content.lastIndexOf('}');
                try {
                    JsonNode j = JSON.readTree(content.substring(a, b + 1));
                    chosen = j.path("tool").asText("NONE");
                    JsonNode argNode = j.path("arguments");
                    args = argNode.isMissingNode() ? "{}" : argNode.toString();
                    if ("NONE".equalsIgnoreCase(chosen) || chosen.isBlank()) chosen = "NONE";
                    else argsValid = validArgs(chosen, args, schemas);
                } catch (RuntimeException e) {   // JSON 이 아니거나 중괄호가 없다(StringIndexOutOfBounds 포함)
                    formatFailure = true;
                }
                if ("length".equals(finish)) formatFailure = true;
            } else if (calls.isArray() && !calls.isEmpty()) {
                JsonNode fn = calls.get(0).path("function");
                chosen = fn.path("name").asText("NONE");
                args = fn.path("arguments").asText("");
                argsValid = validArgs(chosen, args, schemas);
            } else {
                // 호출이 없다: 잘렸거나 본문에 호출 흔적이 있으면 형식 실패, 아니면 정상적인 "호출 안 함"
                // 모델이 Tool 호출을 구조화된 tool_calls 가 아니라 본문 글자로 썼다(작은 모델이 템플릿 형식을 틀릴 때): "이름{…}" · "이름(…)" · 표지 문자열
                formatFailure = "length".equals(finish) || content.contains("[TOOL_REQUEST]") || content.contains("<tool_call")
                        || content.contains("<|tool_call") || writesCallAsText(content, schemas.keySet());
                row.put("leakedCall", writesCallAsText(content, schemas.keySet()));
            }
            if (!schemas.containsKey(chosen) && !"NONE".equals(chosen)) {
                formatFailure = true;   // 모르는 Tool 이름
                argsValid = false;
            }
        } else {
            formatFailure = true;
            row.put("error", res.body().substring(0, Math.min(200, res.body().length())));
        }
        row.put("chosen", chosen);
        row.put("correct", chosen.equals(c.expected()));
        row.put("argsValid", argsValid);
        row.put("formatFailure", formatFailure);
        row.put("finish", finish);
        row.put("arguments", args);
        row.put("content", content.length() > 160 ? content.substring(0, 160) : content);
        return row;
    }

    /** 본문이 알려진 Tool 이름 바로 뒤에 {@code {} 나 {@code (} 를 붙여 호출을 글자로 쓴 모양인가. */
    static boolean writesCallAsText(String content, java.util.Set<String> names) {
        for (String n : names) {
            if (java.util.regex.Pattern.compile("(^|[^A-Za-z])" + java.util.regex.Pattern.quote(n) + "\\s*[({]").matcher(content).find()) return true;
        }
        return false;
    }

    /** 인자가 JSON 객체이고, 스키마의 필수 인자가 모두 있고, 스키마에 없는 인자가 없다. */
    private static boolean validArgs(String tool, String arguments, Map<String, JsonNode> schemas) {
        JsonNode schema = schemas.get(tool);
        if (schema == null) return false;
        try {
            JsonNode a = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            if (!a.isObject()) return false;
            for (JsonNode req : schema.path("required")) {
                if (!a.has(req.asText()) || a.get(req.asText()).isNull() || a.get(req.asText()).asText().isBlank()) return false;
            }
            for (String name : a.propertyNames()) {
                if (!schema.path("properties").has(name)) return false;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void print(List<Map<String, Object>> rows, int caseCount) {
        long n = rows.size();
        long correct = rows.stream().filter(r -> (boolean) r.get("correct")).count();
        long fmt = rows.stream().filter(r -> (boolean) r.get("formatFailure")).count();
        List<Map<String, Object>> called = rows.stream().filter(r -> !"NONE".equals(r.get("chosen"))).toList();
        long argsOk = called.stream().filter(r -> (boolean) r.get("argsValid")).count();
        // 잘못된 우회 = 문서 질문(searchKnowledge 가 정답)을 다른 Tool 로 보냄. NONE 은 우회가 아니다(RETRIEVAL 로 내려가 같은 결과).
        List<Map<String, Object>> docs = rows.stream().filter(r -> "doc".equals(r.get("kind"))).toList();
        long detour = docs.stream().filter(r -> !"searchKnowledge".equals(r.get("chosen")) && !"NONE".equals(r.get("chosen"))).count();
        long docNone = docs.stream().filter(r -> "NONE".equals(r.get("chosen"))).count();
        List<Long> latencies = rows.stream().map(r -> ((Number) r.get("ms")).longValue()).sorted().toList();

        System.out.println("=== Tool 선택 사전 평가 [" + label + "] 모델=" + model + " " + baseUrl + " 생각=" + (thinkingOff ? "끔" : "서버 기본")
                + " (" + caseCount + "건 × " + repeat + ") ===");
        Map<String, List<Map<String, Object>>> byKind = rows.stream().collect(
                Collectors.groupingBy(r -> (String) r.get("kind"), LinkedHashMap::new, Collectors.toList()));
        byKind.forEach((kind, list) -> {
            long ok = list.stream().filter(r -> (boolean) r.get("correct")).count();
            System.out.printf("  %-12s %3d/%-3d 정답%n", kind, ok, list.size());
        });
        System.out.printf("선택 정확도      %.3f (%d/%d)%n", (double) correct / n, correct, n);
        System.out.printf("잘못된 우회율    %.3f (문서 질문 %d건 중 다른 Tool %d건; Tool 없음 %d건은 RETRIEVAL 로 내려가 우회 아님)%n",
                docs.isEmpty() ? 0.0 : (double) detour / docs.size(), docs.size(), detour, docNone);
        System.out.printf("인자 유효율      %.3f (호출 %d건 중 %d건)%n", called.isEmpty() ? 1.0 : (double) argsOk / called.size(), called.size(), argsOk);
        System.out.printf("형식 실패율      %.3f (%d/%d)%n", (double) fmt / n, fmt, n);
        System.out.printf("지연 ms          p50=%d  p90=%d  max=%d%n", latencies.get(latencies.size() / 2),
                latencies.get(Math.min(latencies.size() - 1, (int) (latencies.size() * 0.9))), latencies.get(latencies.size() - 1));
        System.out.println("틀린 케이스:");
        rows.stream().filter(r -> !(boolean) r.get("correct")).forEach(r ->
                System.out.printf("  %-8s 기대=%-22s 선택=%-22s %s%n", r.get("case"), r.get("expected"), r.get("chosen"), r.get("question")));
    }

    private void emit(Map<String, Object> row) throws IOException {
        Files.createDirectories(out);
        Files.writeString(out.resolve("tool.jsonl"), JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
