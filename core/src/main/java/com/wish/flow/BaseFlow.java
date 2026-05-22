package com.wish.flow;

import com.wish.agent.BaseAgent;
import com.wish.llm.LLMChatClient;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class BaseFlow {
    private static final String DEFAULT = "default";
    @Getter
    private Map<String, BaseAgent> agents = new HashMap<>();
    private final String primaryAgentName;

    protected LLMChatClient llmChatClient;

    public BaseFlow(LLMChatClient llmChatClient, Map<String, BaseAgent> agents, String primaryAgentName) {
        this.llmChatClient = llmChatClient;
        this.agents = agents;
        this.primaryAgentName = primaryAgentName;
    }

    public BaseFlow(LLMChatClient llmChatClient, BaseAgent agent) {
        this.llmChatClient = llmChatClient;
        agents = new HashMap<>();
        agents.put(DEFAULT, agent);
        primaryAgentName = DEFAULT;
    }

    public BaseFlow(LLMChatClient llmChatClient, List<BaseAgent> agents) {
        this.llmChatClient = llmChatClient;
        this.agents = new HashMap<>();
        for(int i = 0; i < agents.size(); i++){
            String name = "agent_" + i;
            this.agents.put(name, agents.get(i));
        }
        this.primaryAgentName = "agent_0";
    }

    public Optional<BaseAgent> getPrimaryAgent() {
        return Optional.ofNullable(this.agents.get(this.primaryAgentName));
    }

    public void addAgent(String key, BaseAgent baseAgent) {
        this.agents.put(key, baseAgent);
    }

    public abstract String execute(String conversation, String input);
}
