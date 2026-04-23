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
        AgentState initial = AgentState.of(
                request.question(),
                request.version(),
                request.threadId(),
                memoryService.getHistory(request.threadId()));

        AgentState result = agentGraph.run(initial);

        return new ChatResponse(
                result.answer(),
                result.questionType(),
                result.sources()
        );
    }
}
