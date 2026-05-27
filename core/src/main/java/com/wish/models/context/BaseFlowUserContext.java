package com.wish.models.context;

import com.wish.agent.BaseAgent;
import lombok.Getter;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Getter
public class BaseFlowUserContext extends Context{
    private final Map<BaseAgent, BaseUserContext> flowContext = new HashMap<>();

    public BaseFlowUserContext(String conversation, ChatMemory chatMemory) {
        super(conversation, chatMemory);
    }

    /** One {@link BaseUserContext} per executor agent (separate memory partition per agent). */
    public void setupExecutors(Set<BaseAgent> executors) {
        flowContext.clear();
        int i = 0;
        for (BaseAgent agent : executors) {
            String executorConv = conversation + "_executor_" + i;
            i++;
            flowContext.put(agent, agent.createUserContext(executorConv, chatMemory));
        }
    }

    public BaseUserContext getExecutorContext(BaseAgent agent) {
        return flowContext.get(agent);
    }

    public boolean hasExecutorContexts() {
        return !flowContext.isEmpty();
    }

    @Override
    public Triple<Long, Long, Long> getTokenUsage() {
        long promptToken = 0L;
        long responseToken = 0L;
        long totalToken = 0L;
        for (BaseUserContext context: flowContext.values()) {
            Triple<Long, Long, Long> contextUsage = context.getTokenUsage();
            promptToken += contextUsage.getLeft();
            responseToken += contextUsage.getMiddle();
            totalToken += contextUsage.getRight();
        }
        return Triple.of(promptToken, responseToken, totalToken);
    }
}
