package com.wish.agent.base;

import com.wish.llm.LLMChatClient;
import com.wish.models.AgentState;
import com.wish.models.context.BaseUserContext;
import org.springframework.ai.chat.memory.ChatMemory;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public abstract class BaseAgent {

    public static final String CREATE_CHAT_COMPLETION_TOOL_NAME = "create_chat_completion";
    public static final String TERMINATE_TOOL_NAME = "terminate";

    @Getter
    @Setter
    protected String name;

    @Getter
    protected final String description;

    @Getter
    protected final LLMChatClient chatClient;

    @Getter
    protected final int maxSteps;

    @Getter
    @Setter
    protected AgentState agentState;

    @Getter
    @Setter
    protected String systemPrompt;

    @Getter
    @Setter
    protected String nextStepPrompt;

    @Getter
    protected final String originalNextStepPrompt;

    protected final int stuckThreshold = 3;

    protected BaseAgent(
            String name,
            String description,
            String systemPrompt,
            String nextStepPrompt,
            LLMChatClient llmChatClient,
            int maxSteps) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.nextStepPrompt = nextStepPrompt;
        this.originalNextStepPrompt = nextStepPrompt;
        this.chatClient = llmChatClient;
        this.maxSteps = maxSteps;
        this.agentState = AgentState.IDLE;
    }

    public void transitState(AgentState toState) {
        AgentState previous = this.agentState;
        this.agentState = toState;
        log.info("Transit agent state from {} to {}", previous, toState);
    }

    public synchronized String run(BaseUserContext userContext, String request) {
        if (!healthCheck()) {
            throw new IllegalStateException("Cannot run agent from state " + this.agentState);
        }

        transitState(AgentState.RUNNING);
        userContext.beginRun();
        List<String> results = new ArrayList<>();

        try {
            if (!userContext.hasConversation()) {
                userContext.addSystemMemory(systemPrompt);
            }
            if (request != null && !request.isBlank()) {
                userContext.addUserMemory(request);
            }

            while (userContext.getCurrentStep() < maxSteps && agentState != AgentState.FINISHED) {
                int step = userContext.nextStep();
                log.info("Executing step {}/{}", step, maxSteps);
                String stepResult = step(userContext);
                results.add("Step %d: %s".formatted(step, stepResult));

                if (agentState == AgentState.FINISHED) {
                    break;
                }
                if (userContext.isStuck(stuckThreshold)) {
                    handleStuck();
                }
            }

            if (userContext.getCurrentStep() >= maxSteps && agentState != AgentState.FINISHED) {
                results.add("Terminated: Reached max steps (%d)".formatted(maxSteps));
            }
            return results.isEmpty() ? "No steps executed" : String.join("\n", results);
        } catch (Exception e) {
            transitState(AgentState.ERROR);
            log.error("Agent run failed at step {}/{}", userContext.getCurrentStep(), maxSteps, e);
            throw new IllegalStateException("Agent run failed: " + e.getMessage(), e);
        } finally {
            cleanup();
            if (agentState == AgentState.ERROR) {
                log.warn("Agent finished with ERROR; resetting to IDLE for next run");
            }
            transitState(AgentState.IDLE);
        }
    }

    public abstract String step(BaseUserContext userContext);

    /**
     * Create conversation state for this agent type. Flows call this in {@code setupExecutors}
     * instead of branching on concrete agent classes.
     */
    public BaseUserContext createUserContext(String conversationId, ChatMemory chatMemory) {
        return new BaseUserContext(conversationId, chatMemory);
    }

    public BaseUserContext createUserContext(String conversationId, String sessionId, ChatMemory chatMemory) {
        return new BaseUserContext(conversationId, sessionId, chatMemory);
    }

    protected void handleStuck() {
        String stuckPrompt = "Avoid repeating the same response. Try a different approach or call terminate.";
        nextStepPrompt = originalNextStepPrompt + "\n" + stuckPrompt;
        log.warn("Agent detected stuck state, appended recovery prompt");
    }

    public boolean healthCheck() {
        return agentState == AgentState.IDLE;
    }

    protected void cleanup() {
        nextStepPrompt = originalNextStepPrompt;
    }
}
