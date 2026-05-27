package com.wish.models.context;

import com.wish.agent.BaseAgent;
import lombok.Getter;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Getter
public class BaseFlowUserContext {

    private final String conversation;
    private final Map<BaseAgent, BaseUserContext> flowContext = new HashMap<>();
    private final ChatMemory chatMemory;

    public BaseFlowUserContext(String conversation, ChatMemory chatMemory) {
        this.conversation = conversation;
        this.chatMemory = chatMemory;
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
}
