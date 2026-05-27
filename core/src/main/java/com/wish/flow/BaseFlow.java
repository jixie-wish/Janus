package com.wish.flow;

import com.wish.agent.base.BaseAgent;
import com.wish.llm.LLMChatClient;
import com.wish.models.context.BaseFlowUserContext;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class BaseFlow {

    private static final String DEFAULT = "default";

    @Getter
    private final Map<String, BaseAgent> agents = new HashMap<>();

    @Getter
    private final List<String> executorKeys = new ArrayList<>();

    private final String primaryAgentName;

    protected final LLMChatClient llmChatClient;

    public BaseFlow(LLMChatClient llmChatClient, Map<String, BaseAgent> agents, String primaryAgentName) {
        this.llmChatClient = llmChatClient;
        this.agents.putAll(agents);
        this.primaryAgentName = primaryAgentName;
        this.executorKeys.addAll(agents.keySet());
    }

    public BaseFlow(LLMChatClient llmChatClient, BaseAgent agent) {
        this.llmChatClient = llmChatClient;
        agents.put(DEFAULT, agent);
        primaryAgentName = DEFAULT;
        executorKeys.add(DEFAULT);
    }

    public BaseFlow(LLMChatClient llmChatClient, List<BaseAgent> agents) {
        this.llmChatClient = llmChatClient;
        for (int i = 0; i < agents.size(); i++) {
            String name = "agent_" + i;
            this.agents.put(name, agents.get(i));
            executorKeys.add(name);
        }
        this.primaryAgentName = "agent_0";
    }

    public BaseFlow(LLMChatClient llmChatClient, Map<String, BaseAgent> agents, String primaryAgentName, List<String> executorKeys) {
        this(llmChatClient, agents, primaryAgentName);
        if (executorKeys != null && !executorKeys.isEmpty()) {
            this.executorKeys.clear();
            this.executorKeys.addAll(executorKeys);
        }
    }

    public Optional<BaseAgent> getPrimaryAgent() {
        return Optional.ofNullable(agents.get(primaryAgentName));
    }

    public void addAgent(String key, BaseAgent baseAgent) {
        agents.put(key, baseAgent);
        if (!executorKeys.contains(key)) {
            executorKeys.add(key);
        }
    }

    /** Pick executor for a plan step. */
    protected BaseAgent getExecutor(String stepType) {
        if (stepType != null && !stepType.isBlank()) {
            if (agents.containsKey(stepType)) {
                return agents.get(stepType);
            }
            for (Map.Entry<String, BaseAgent> entry : agents.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(stepType)) {
                    return entry.getValue();
                }
            }
        }
        for (String key : executorKeys) {
            if (agents.containsKey(key)) {
                return agents.get(key);
            }
        }
        return getPrimaryAgent()
                .orElseThrow(() -> new IllegalStateException("No primary agent available"));
    }

    public abstract String execute(BaseFlowUserContext baseFlowUserContext, String input);
}
