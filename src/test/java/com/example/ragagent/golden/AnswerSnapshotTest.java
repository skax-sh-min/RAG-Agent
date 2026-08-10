package com.example.ragagent.golden;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ChatRequest;
import com.example.ragagent.model.ChatResponse;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.service.AgentService;
import com.example.ragagent.service.ClassifierService;
import com.example.ragagent.service.ConversationSummarizerService;
import com.example.ragagent.service.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 골든셋 스냅샷 회귀 테스트.
 *
 * src/test/resources/golden/*.json 각 케이스에 대해:
 *  1) given 으로 AgentGraph 출력을 시뮬레이션
 *  2) AgentService.chat() 호출
 *  3) ChatResponse → AnswerShape 변환
 *  4) expected AnswerShape 와 비교
 *
 * 비교 대상은 응답 *내용* 이 아닌 *구조* (sections / counts / flags).
 * 프롬프트 변경에 강건. ChatResponse 매핑 또는 AnswerShape 정의 변경 시 회귀 감시.
 */
class AnswerSnapshotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN_DIR = Paths.get("src/test/resources/golden");

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    void answerShapeMatchesGolden(GoldenCase c) {
        AgentGraph agentGraph = mock(AgentGraph.class);
        MemoryService memory = mock(MemoryService.class);
        ClassifierService classifier = mock(ClassifierService.class);
        ConversationSummarizerService summarizer = mock(ConversationSummarizerService.class);

        when(memory.getHistory(any(), any())).thenReturn("");
        when(classifier.classifyOnly(any(), any())).thenReturn(c.given().questionType());
        when(agentGraph.run(any())).thenReturn(buildAgentState(c));

        AgentService service = new AgentService(agentGraph, memory, classifier, summarizer);

        ChatResponse resp = service.chat(ThreadContext.anonymous("t1"), new ChatRequest(
                c.question(), c.version(), "t1", RoutingMode.valueOf(c.routingMode())));

        AnswerShape actual = AnswerShape.of(resp);

        assertThat(actual)
                .as("Golden case [%s] shape mismatch", c.name())
                .isEqualTo(c.expected());
    }

    /** GoldenCase.given → 시뮬레이션 AgentState. */
    private static AgentState buildAgentState(GoldenCase c) {
        GoldenCase.Given g = c.given();

        // sources 더미
        List<SourceRef> sources = java.util.stream.IntStream.range(0, g.sourcesCount())
                .mapToObj(i -> new SourceRef("doc%d.pdf | v1 | p.%d".formatted(i, i + 1),
                              "preview-" + i, "chunk_" + i, "doc_" + i, i + 1))
                .toList();

        // imageRefs 더미
        List<String> imageRefs = java.util.stream.IntStream.range(0, g.imageRefsCount())
                .mapToObj(i -> "data/images/doc_" + i + "/img" + i + ".png")
                .toList();

        AgentState.Builder b = AgentState.of(c.question(), c.version(), "t1", "",
                                              RoutingMode.valueOf(c.routingMode()))
                .toBuilder()
                .questionType(g.questionType())
                .answer(g.answer())
                .usedProvider(g.usedProvider())
                .sources(sources)
                .imageRefs(imageRefs);

        // llm_call_count 시뮬레이션 — accumulateTokens 호출로 누적
        if (g.llmCallCount() > 0) {
            int perCallIn  = g.inputTokens()  / g.llmCallCount();
            int perCallOut = g.outputTokens() / g.llmCallCount();
            int residualIn  = g.inputTokens()  - perCallIn  * g.llmCallCount();
            int residualOut = g.outputTokens() - perCallOut * g.llmCallCount();
            for (int i = 0; i < g.llmCallCount(); i++) {
                b.accumulateTokens(
                        perCallIn  + (i == 0 ? residualIn  : 0),
                        perCallOut + (i == 0 ? residualOut : 0));
            }
        }

        if (g.premiumUpgraded() != null) b.premiumUpgraded(g.premiumUpgraded());

        return b.build();
    }

    /** golden/*.json 전부 로드. */
    static Stream<GoldenCase> goldenCases() throws IOException {
        try (Stream<Path> files = Files.list(GOLDEN_DIR)) {
            return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(AnswerSnapshotTest::load)
                    .toList()
                    .stream();
        }
    }

    private static GoldenCase load(Path p) {
        try {
            return MAPPER.readValue(p.toFile(), GoldenCase.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load golden case: " + p, e);
        }
    }
}
