package com.wish.agent;

import com.wish.llm.LLMChatClient;
import com.wish.models.AgentState;
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
    protected final String name;

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
    protected int currentStep;

    @Getter
    @Setter
    protected String systemPrompt;

    @Getter
    @Setter
    protected String nextStepPrompt;

    @Getter
    protected final String originalNextStepPrompt;

    @Getter
    @Setter
    protected String lastThinkResult = "";

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
        this.currentStep = 0;
    }

    public void transitState(AgentState toState) {
        AgentState previous = this.agentState;
        this.agentState = toState;
        log.info("Transit agent state from {} to {}", previous, toState);
    }

    public String run(String conversation, String request) {
        if (!healthCheck()) {
            throw new IllegalStateException("Cannot run agent from state " + this.agentState);
        }

        transitState(AgentState.RUNNING);
        currentStep = 0;
        List<String> results = new ArrayList<>();

        try {
            // System prompt only on first turn for this conversation (continuing sessions keep prior memory)
            if (!chatClient.hasConversation(conversation)) {
                chatClient.addSystemMemory(systemPrompt, conversation);
            }
            if (request != null && !request.isBlank()) {
                chatClient.addUserMemory(request, conversation);
            }

            while (currentStep < maxSteps && agentState != AgentState.FINISHED) {
                currentStep++;
                log.info("Executing step {}/{}", currentStep, maxSteps);
                String stepResult = step(conversation);
                results.add("Step %d: %s".formatted(currentStep, stepResult));

                if (agentState == AgentState.FINISHED) {
                    break;
                }
                if (chatClient.isStuck(conversation, stuckThreshold)) {
                    handleStuck();
                }
            }

            if (currentStep >= maxSteps && agentState != AgentState.FINISHED) {
                results.add("Terminated: Reached max steps (%d)".formatted(maxSteps));
            }
            return results.isEmpty() ? "No steps executed" : String.join("\n", results);
        } catch (Exception e) {
            transitState(AgentState.ERROR);
            log.error("Agent run failed at step {}/{}", currentStep, maxSteps, e);
            throw new IllegalStateException("Agent run failed: " + e.getMessage(), e);
        } finally {
            cleanup();
            if (agentState == AgentState.ERROR) {
                log.warn("Agent finished with ERROR; resetting to IDLE for next run");
            }
            transitState(AgentState.IDLE);
            currentStep = 0;
        }
    }

    public abstract String step(String conversation);

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
        lastThinkResult = "";
    }
}
