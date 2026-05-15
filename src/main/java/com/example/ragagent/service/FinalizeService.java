package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.springframework.stereotype.Service;

/**
 * Last node of the agent graph — turn persistence has been moved to
 * AgentService and StreamingAgentService so elapsed time is available.
 * Equivalent to finalize_node in agents.py.
 */
@Service
public class FinalizeService {

    public AgentState execute(AgentState state) {
        return state;
    }
}
