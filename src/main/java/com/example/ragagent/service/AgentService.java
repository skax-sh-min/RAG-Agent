package com.example.ragagent.service;

import com.example.ragagent.agent.AgentGraph;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.model.ChatRequest;
import com.example.ragagent.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * Entry point for the agent pipeline. Builds initial AgentState,
 * injects conversation history, runs the graph, and returns ChatResponse.
 *
 * Equivalent to run_agent() in agents.py.
 */
@Service
public class AgentService {

    private final AgentGraph agentGraph;
    private final MemoryService memoryService;

    public AgentService(AgentGraph agentGraph, MemoryService memoryService) {
        this.agentGraph = agentGraph;
        this.memoryService = memoryService;
    }

    public ChatResponse chat(ChatRequest request) {
        AgentState state = new AgentState();
        state.setQuestion(request.question());
        state.setVersion(request.version());
        state.setThreadId(request.threadId());
        state.setConversationHistory(memoryService.getHistory(request.threadId()));

        agentGraph.run(state);

        return new ChatResponse(
                state.getAnswer(),
                state.getQuestionType(),
                state.getSources()
        );
    }
}
