package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.springframework.stereotype.Service;

/**
 * Saves the completed turn to conversation memory.
 * Equivalent to finalize_node in agents.py.
 */
@Service
public class FinalizeService {

    private final MemoryService memoryService;

    public FinalizeService(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public AgentState execute(AgentState state) {
        if (state.answer() != null && !state.answer().isBlank()) {
            memoryService.addTurn(state.threadId(), state.question(), state.answer());
        }
        return state;
    }
}
