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

    protected final int STUCK_THRESHOLD = 3;

    public BaseAgent(
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
        AgentState previous = this.getAgentState();
        this.setAgentState(toState);
        log.info("Transit agent state from {} to {}", previous, toState);
    }

    public String run(String conversation, String request) {
        if(!healthCheck()){
            throw new RuntimeException("Cannot run agent from state " + this.agentState);
        }

        if(!request.isBlank()) {
            this.chatClient.addUserMemory(request, conversation);
        }
        this.chatClient.addSystemMemory(systemPrompt, conversation);

        List<String> results = new ArrayList<>();
        while(this.currentStep < this.maxSteps && this.agentState != AgentState.FINISHED) {
            this.currentStep += 1;
            log.info("Executing step {}/{}", this.currentStep, this.maxSteps);
            String stepResult = this.step(conversation);
            if(chatClient.isStuck(conversation, STUCK_THRESHOLD)) {
                handle_stuck();
            }
            results.add(String.format("Step %d: %s", currentStep, stepResult));
        }
        if(currentStep >= maxSteps) {
            currentStep = 0;
            agentState = AgentState.IDLE;
            results.add(String.format("Terminated: Reached max steps %d", maxSteps));
        }

        cleanup();
        if(results.isEmpty()) {
            return "No steps executed";
        }
        return String.join("\n", results);
    }

    public abstract String step(String conversation);

    public void handle_stuck() {
        String stuckPrompt = "";
        this.nextStepPrompt += "\n";
        this.nextStepPrompt += stuckPrompt;
        log.warn("Agent detected stuck state. Added prompt {}", stuckPrompt);
    }

    public boolean healthCheck() {
        return this.agentState == AgentState.IDLE;
    }

    private void cleanup() {
        this.nextStepPrompt = originalNextStepPrompt;
    }
}
