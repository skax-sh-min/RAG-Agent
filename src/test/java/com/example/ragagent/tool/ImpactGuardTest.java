package com.example.ragagent.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactGuardTest {

    @Test
    void impactQuestionsPass() {
        // 사전 평가 세트의 영향도 질문 5건과 같은 모양
        for (String q : new String[]{
                "IBatchContext 클래스 이름을 바꾸면 어떤 문서가 영향을 받아?",
                "getProcDate 메소드를 삭제하면 어떤 문서를 고쳐야 해?",
                "KISBException 을 다른 예외로 교체하면 영향 범위를 분석해줘",
                "XA 트랜잭션 지원을 중단하면 문서 중 어디가 바뀌어야 해?",
                "NOTX 설명이 바뀌면 영향받는 문서를 찾아줘",
                "Which documents are affected if we drop XA?"}) {
            assertThat(ToolPlanService.asksAboutImpact(q)).as(q).isTrue();
        }
    }

    @Test
    void editRequestsAreNotImpactQuestions() {
        for (String q : new String[]{
                "문서에서 'PM' 을 모두 '프로세스 모듈' 로 바꿔줘",
                "배치 가이드의 getProcDate 를 getProcessDate 로 수정해줘",
                "EIMS 문서의 BigDecimal 설명에 주의사항 한 줄을 추가해줘",
                "PostgreSQL을 Oracle로 변경해",
                "Replace PM with process module in the docs"}) {
            assertThat(ToolPlanService.asksAboutImpact(q)).as(q).isFalse();
        }
        assertThat(ToolPlanService.asksAboutImpact(null)).isFalse();
    }

    @Test
    void bundlesCarryTheEditRequestRule() throws IOException {
        for (String f : new String[]{"messages.properties", "messages_ko.properties"}) {
            String line = Files.readAllLines(Path.of("src/main/resources", f), StandardCharsets.UTF_8).stream()
                    .filter(l -> l.startsWith("prompt.agent.plan.rule.analyzeImpact"))
                    .findFirst().orElseThrow();
            assertThat(line).as(f).containsAnyOf("is not analyzeImpact", "analyzeImpact 가 아니다");
        }
    }
}
