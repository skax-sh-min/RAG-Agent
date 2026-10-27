package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.SentPrompt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG 답변 프롬프트의 <b>바이트 단위 골든</b> — 실제 한국어 번들로 조립한 시스템 + 사용자 메시지 전체를 파일과 대조한다.
 *
 * <p>§6.31(이력 제외)의 완료 기준 "꺼진 상태에서 프롬프트가 지금과 바이트 단위로 같다"를 지킨다: 골든은 그 기능이 들어오기
 * <b>전의</b> 코드로 떴다. 사례는 사다리의 갈래마다 하나 — 다 들어감 · 문서와 이력이 함께 줄어듦 · 후속 질문의 칸 5 ·
 * 검증 실패 재시도 · 창 모름. 다른 사람의 의도된 변경(프롬프트 문구 · 사다리 규칙)으로 바뀌면
 * {@code -Danswer-prompt-golden.update=true} 로 다시 뜨고, 차이를 직접 읽은 뒤 커밋할 것.
 */
@ResourceLock("global-state")
class AnswerPromptGoldenTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden/answer-prompt.txt");
    private static final String PASS = "{\"sufficient\":true,\"grounded\":true}";

    private static ResourceBundleMessageSource realMessageSource() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    private static Document doc(int n, int chars) {
        String head = "문서" + n + " 배포 절차 설명. ";
        return new Document("d" + n, head + "가".repeat(Math.max(0, chars - head.length())), Map.of());
    }

    private static List<Document> docs(int count, int chars) {
        List<Document> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add(doc(i, chars));
        return list;
    }

    private static String turn(String question, int answerChars) {
        return "Q: " + question + "\nA: " + "나".repeat(answerChars);
    }

    private static AgentState base(List<Document> docs, String history) {
        return AgentState.of("배포 절차는 어떻게 되나요?", "v1", "t1", "u1", history, RoutingMode.COST_FIRST,
                        false, Locale.KOREAN)
                .toBuilder().retrievedDocs(docs).build();
    }

    /** 사례 하나를 돌려 답변 프롬프트(마지막 시도)와 안내 문구를 적는다. */
    private static String run(String name, Integer window, AgentState state) {
        LlmRouter router = mock(LlmRouter.class);
        when(router.findProviderName(any(), any())).thenReturn("lm");
        ProviderContextWindows windows = new ProviderContextWindows();
        if (window != null) windows.record("lm", window, ProviderContextWindows.Source.CONFIGURED);
        AppProperties props = mock(AppProperties.class);
        when(props.maxRetryCount()).thenReturn(2);
        when(props.searchTopKSafe()).thenReturn(10);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 8000, 1, true));
        List<String> prompts = new ArrayList<>();
        when(router.executeGatedWithUsage(eq(TaskType.TEXT), any(), any())).thenAnswer(inv -> {
            Function<ChatModel, ChatResponse> fn = inv.getArgument(2);
            boolean[] answer = {false};
            ChatModel probe = mock(ChatModel.class);
            when(probe.call(any(Prompt.class))).thenAnswer(call -> {
                Prompt prompt = call.getArgument(0);
                String text = prompt.getInstructions().stream()
                        .map(org.springframework.ai.chat.messages.Message::getText)
                        .reduce("", (a, b) -> a + "\n" + b);
                answer[0] = text.contains("[검색된 문서]");
                if (answer[0]) prompts.add(text);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("답변"))));
            });
            fn.apply(probe);
            return new LlmRouter.LlmResult(answer[0] ? "답변" : PASS, 0, 0);
        });
        AgentState result = new AnswerService(router, props, realMessageSource(), windows).execute(state);
        return "=== " + name + " ===\n" + prompts.getLast() + "\n--- note: " + result.budgetNote() + "\n\n";
    }

    private static String all() {
        String recent = "[Conversation Summary]\n앞에서 배포 스크립트와 포트 설정을 이야기했다.\n\n[Recent]\n"
                + turn("배포 스크립트는 어디 있어?", 300) + "\n\n" + turn("포트는 몇 번이야?", 200);
        StringBuilder sb = new StringBuilder();
        sb.append(run("fits", 20_000, base(docs(3, 300), recent)
                .toBuilder().retrievalWarnings(List.of("⚠️ OCR 문서 포함")).build()));
        String long4 = turn("첫째 질문", 600) + "\n\n" + turn("둘째 질문", 600) + "\n\n"
                + turn("셋째 질문", 600) + "\n\n" + turn("넷째 질문", 600);
        sb.append(run("trimmed", 12_288, base(docs(15, 900), long4)));
        sb.append(run("condensed", 12_288, base(docs(10, 800), turn("그 설정은?", 6_000)).toBuilder()
                .question("그건 어디서 바꿔?").searchQuestion("배포 포트 설정은 어디서 바꾸나").build()));
        List<String> sent = docs(10, 800).stream().map(Document::getId).toList();
        sb.append(run("retry", 16_384, base(docs(11, 800), turn("직전 질문", 400)).toBuilder()
                .retryCount(1).incrementRetrievalRetry().evalReason("포트 값이 문서에 없다")
                .lastSent(new SentPrompt(sent, SentPrompt.HistoryCut.NONE, List.of("d1"))).build()));
        sb.append(run("unknown-window", null, base(docs(4, 500), recent)));
        return sb.toString();
    }

    @Test
    @DisplayName("RAG 답변 프롬프트가 골든과 바이트 단위로 같다(§6.31 이력 제외가 꺼진 상태 = 기능 이전과 같다)")
    void answerPromptMatchesTheGolden() throws IOException {
        String actual = all();
        if (Boolean.getBoolean("answer-prompt-golden.update")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
        }
        assertThat(GOLDEN).as("골든이 없다 — -Danswer-prompt-golden.update=true 로 뜰 것").exists();
        assertThat(actual).isEqualTo(Files.readString(GOLDEN, StandardCharsets.UTF_8));
    }
}
