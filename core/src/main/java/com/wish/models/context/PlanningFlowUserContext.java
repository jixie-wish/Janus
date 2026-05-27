package com.wish.models.context;

import lombok.Getter;
import org.springframework.ai.chat.memory.ChatMemory;

@Getter
public class PlanningFlowUserContext extends BaseFlowUserContext {

    private final String planId;
    private boolean planInitialized;
    private boolean planFinalized;
    private BaseUserContext initializeContext;
    private BaseUserContext finalizeContext;

    public PlanningFlowUserContext(String conversation, ChatMemory chatMemory) {
        this(conversation, conversation + "_plan", chatMemory);
        this.initializeContext = new BaseUserContext(planningMemoryKey("create"), chatMemory);
        this.finalizeContext = new BaseUserContext(planningMemoryKey("finalize"), chatMemory);
    }

    public PlanningFlowUserContext(String conversation, String planId, ChatMemory chatMemory) {
        super(conversation, chatMemory);
        this.planId = planId;
    }

    public void initializePlan() {
        this.planInitialized = true;
    }

    public void finalizePlan() {
        this.planFinalized = true;
    }

    private String planningMemoryKey(String phase) {
        return conversation + "_planning_" + phase;
    }

    @Override
    public TokenUsageCounter getTokenUsage() {
        return new TokenUsageCounter()
                .add(super.getTokenUsage())
                .add(initializeContext.getTokenUsage())
                .add(finalizeContext.getTokenUsage());
    }
}
