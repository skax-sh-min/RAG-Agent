package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.RoutingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — FinalizeService no-op passthrough (EDIT.md #1)
 *
 * Turn persistence lives in AgentService/StreamingAgentService now (needs elapsed time
 * unavailable at this node) — this node is a deliberate identity function.
 */
class FinalizeServiceTest {

    private final FinalizeService service = new FinalizeService();

    @Test
    @DisplayName("execute() 는 입력 state 를 그대로 반환한다 (no-op)")
    void execute_returnsStateUnchanged() {
        AgentState state = AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST)
                .toBuilder().answer("답변").build();

        AgentState result = service.execute(state);

        assertThat(result).isSameAs(state);
    }
}
