package com.example.ragagent.workflow;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.OpenAiClients;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingWire;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.service.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §6.33 4a <b>영향도 판정 실모델 실측</b> — 실제 {@link ImpactAnalysisWorkflow#analyze}(실제 번들 프롬프트 · 실제 JSON 파싱 · 인용 검증)를
 * 로컬 서버에 붙여, 라벨이 붙은 청크 세트로 <b>① 영향 있음 회수율</b>(심은 언급) · <b>② 영향 없음 오탐률</b>(지나가는 언급) ·
 * <b>③ 인용 검증이 걸러낸 수</b>(영향 있다고 했지만 인용이 원문에 없음) · <b>④ 읽지 못한 응답 수</b>를 잰다.
 *
 * <p>전수 조회({@code findMentions}) 자체는 SQL·부분 문자열 검사라 결정적이므로 여기서는 모의로 두고 <b>판정 단계</b>만 잰다.
 * 서버·모델: {@code -Dimpact-eval.base-url}(기본 {@code http://127.0.0.1:1234/v1}), {@code -Dimpact-eval.model}(기본 {@code gemma-4-E2B}),
 * {@code -Dimpact-eval.repeat}(기본 1 — 같은 세트 반복 횟수). 기본 skip.
 */
@EnabledIfSystemProperty(named = "impact-eval.enabled", matches = "true")
class ImpactJudgementEvaluationTest {

    /** 한 사례 — 대상(subject), 변경 설명, 청크 본문, 영향 있음이 정답인가. */
    private record Case(String id, String subject, String note, String text, boolean impacted) {}

    private static final List<Case> CASES = List.of(
            // 포트 8080 → 9090
            new Case("port-1", "8080", "기본 포트를 9090으로 변경", "애플리케이션 서버는 기본적으로 8080 포트에서 HTTP 요청을 받는다. 방화벽에서 8080 포트를 열어 두어야 하며, 리버스 프록시는 이 포트로 요청을 전달한다.", true),
            new Case("port-2", "8080", "기본 포트를 9090으로 변경", "헬스 체크 URL 은 http://localhost:8080/api/v1/health 이다. 설치 직후 이 주소로 응답이 오는지 확인한다.", true),
            new Case("port-3", "8080", "기본 포트를 9090으로 변경", "docker-compose 의 ports 항목에 \"8080:8080\" 을 적어 컨테이너 포트를 호스트에 연결한다.", true),
            new Case("port-d1", "8080", "기본 포트를 9090으로 변경", "표 3-2 의 합계는 매출 8,080만 원이며 전년 대비 3% 증가했다. 분기별 상세 내역은 부록을 참고한다.", false),
            new Case("port-d2", "8080", "기본 포트를 9090으로 변경", "참고로 예제 로그의 요청 번호는 req-80801234 형식이다. 이 번호는 감사 로그 검색에만 쓰인다.", false),
            // 환경변수 LOCAL_LLM_URL 이름 변경
            new Case("env-1", "LOCAL_LLM_URL", "환경변수 이름을 LLM_BASE_URL 로 변경", "로컬 LLM 서버 주소는 LOCAL_LLM_URL 환경변수로 지정한다. 값이 비어 있으면 로컬 프로바이더는 등록되지 않는다.", true),
            new Case("env-2", "LOCAL_LLM_URL", "환경변수 이름을 LLM_BASE_URL 로 변경", ".env 예시:\nLOCAL_LLM_URL=http://localhost:1234/v1\nEMBED_BASE_URL=http://localhost:28091/v1\n운영 서버에서는 LOCAL_LLM_URL 을 사내 추론 서버 주소로 바꾼다.", true),
            new Case("env-3", "LOCAL_LLM_URL", "환경변수 이름을 LLM_BASE_URL 로 변경", "장애 대응 절차: 채팅이 응답하지 않으면 먼저 LOCAL_LLM_URL 이 가리키는 서버가 살아 있는지 curl 로 확인한다.", true),
            new Case("env-d1", "LOCAL_LLM_URL", "환경변수 이름을 LLM_BASE_URL 로 변경", "릴리스 노트: 이번 버전에서 문서 내보내기에 DOCX 형식이 추가되었고, 관리자 화면의 청크 목록 정렬이 안정화되었다.", false),
            // 기능 제거: 'PROGRESSIVE 모드'
            new Case("mode-1", "PROGRESSIVE 모드", "PROGRESSIVE 모드를 제거", "라우팅 모드에는 COST_FIRST 와 PROGRESSIVE 모드가 있다. PROGRESSIVE 모드는 로컬 모델로 먼저 답하고 근거가 부족하면 상위 모델로 올린다.", true),
            new Case("mode-2", "PROGRESSIVE 모드", "PROGRESSIVE 모드를 제거", "사용자 설정 화면에서 기본 라우팅을 고를 수 있다. PROGRESSIVE 모드를 선택하면 답변 옆에 '업그레이드됨' 표시가 붙는다.", true),
            new Case("mode-d1", "PROGRESSIVE 모드", "PROGRESSIVE 모드를 제거", "문서 업로드는 PDF, DOCX, PPTX, TXT, MD 형식을 지원한다. 파일 크기 상한은 설정 화면에서 바꿀 수 있다.", false),
            new Case("mode-d2", "PROGRESSIVE 모드", "PROGRESSIVE 모드를 제거", "용어집: PROGRESSIVE 는 영어로 '점진적'이라는 뜻의 형용사이며, 이 문서의 다른 어디에서도 시스템 기능을 가리키지 않는다.", false)
    );

    @Test
    void reportJudgementQuality() {
        String baseUrl = System.getProperty("impact-eval.base-url", "http://127.0.0.1:1234/v1");
        String modelId = System.getProperty("impact-eval.model", "gemma-4-E2B");
        int repeat = Integer.getInteger("impact-eval.repeat", 1);

        OpenAiChatModel raw = OpenAiClients.chatModel(
                OpenAiClients.clientOptions(baseUrl, "local", 5, 120, 0),
                OpenAiChatOptions.builder().model(modelId).build());
        ThinkingWire off = new ThinkingWire(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)), null, ThinkingWire.Sent.OFF);
        ChatModel model = new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                OpenAiChatOptions o = ThinkingControl.applyTo((OpenAiChatOptions) prompt.getOptions(), off);
                return raw.call(new Prompt(prompt.getInstructions(), o.mutate().model(modelId).build()));
            }
        };
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any())).thenAnswer(inv -> {
            Function<ChatModel, org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            org.springframework.ai.chat.model.ChatResponse r = fn.apply(model);
            return new LlmRouter.LlmResult(r.getResult().getOutput().getText(), 0, 0);
        });
        when(router.localTier1Concurrency()).thenReturn(java.util.Optional.empty());

        ResourceBundleMessageSource bundle = new ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        AppProperties props = mock(AppProperties.class);
        AppProperties.LlmConfig llm = mock(AppProperties.LlmConfig.class);
        when(llm.temperature()).thenReturn(0.2);
        when(llm.maxTokens()).thenReturn(12_000);
        when(props.llmSafe()).thenReturn(llm);

        int plantedTotal = 0, plantedVerified = 0, plantedUnverified = 0, plantedMissed = 0;
        int decoyTotal = 0, decoyFalsePositive = 0, errors = 0;
        List<String> notes = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int r = 0; r < repeat; r++) {
            for (Case c : CASES) {
                KeywordSearchRepository keywords = mock(KeywordSearchRepository.class);
                HybridSearchService hybrid = mock(HybridSearchService.class);
                Document doc = new Document(c.id(), c.text(), Map.of(MetaKey.DOC_ID, "d-" + c.id(), MetaKey.FILENAME, c.id() + ".md"));
                when(keywords.findMentions(anyString(), anyString(), anyInt()))
                        .thenReturn(new KeywordSearchRepository.Mentions(List.of(doc), false, false));
                when(hybrid.withStoredText(any())).thenReturn(List.of(doc));
                ImpactAnalysisWorkflow wf = new ImpactAnalysisWorkflow(keywords, hybrid, router,
                        mock(ChangeJobRepository.class), bundle, props,
                        java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(1));
                ImpactAnalysisWorkflow.Finding f = wf.analyze(c.subject(), c.note(), Locale.KOREAN).findings().get(0);
                String v = f.verdict();
                if (ImpactAnalysisWorkflow.VERDICT_ERROR.equals(v)) errors++;
                if (c.impacted()) {
                    plantedTotal++;
                    if (ImpactAnalysisWorkflow.VERDICT_IMPACTED.equals(v)) plantedVerified++;
                    else if (ImpactAnalysisWorkflow.VERDICT_UNVERIFIED.equals(v)) plantedUnverified++;
                    else plantedMissed++;
                    if (!ImpactAnalysisWorkflow.VERDICT_IMPACTED.equals(v))
                        notes.add("MISS " + c.id() + " verdict=" + v + " reason=" + f.reason() + " quote=" + f.quote());
                } else {
                    decoyTotal++;
                    if (ImpactAnalysisWorkflow.VERDICT_IMPACTED.equals(v) || ImpactAnalysisWorkflow.VERDICT_UNVERIFIED.equals(v)) {
                        decoyFalsePositive++;
                        notes.add("FALSE-POS " + c.id() + " verdict=" + v + " reason=" + f.reason());
                    }
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        StringBuilder sb = new StringBuilder("\n== 영향도 판정 실측 (" + modelId + ", 반복 " + repeat + ") ==\n");
        sb.append(String.format("심은 언급 %d건: 영향 있음(인용 확인) %d · 근거 미확인 %d · 놓침/오류 %d  → 회수율 %.3f%n",
                plantedTotal, plantedVerified, plantedUnverified, plantedMissed, plantedVerified / (double) plantedTotal));
        sb.append(String.format("지나가는 언급 %d건: 영향 있다고 판정 %d  → 오탐률 %.3f%n",
                decoyTotal, decoyFalsePositive, decoyFalsePositive / (double) decoyTotal));
        sb.append(String.format("응답을 읽지 못한/호출 실패 %d · 호출당 평균 %d ms%n", errors, ms / Math.max(1, plantedTotal + decoyTotal)));
        notes.forEach(n -> sb.append(n).append('\n'));
        System.out.println(sb);
    }
}
