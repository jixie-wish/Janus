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
        super(conversation, chatMemory);
        this.planId = conversation + "_plan";
        this.initializeContext = new BaseUserContext(planningMemoryKey("create"), chatMemory);
        this.finalizeContext = new BaseUserContext(planningMemoryKey("finalize"), chatMemory);
    }

    public static PlanningFlowUserContext forSession(String conversation, String sessionId, ChatMemory chatMemory) {
        PlanningFlowUserContext context = new PlanningFlowUserContext(conversation, sessionId, chatMemory);
        context.initializeContext = new BaseUserContext(context.planningMemoryKey("create"), sessionId, chatMemory);
        context.finalizeContext = new BaseUserContext(context.planningMemoryKey("finalize"), sessionId, chatMemory);
        return context;
    }

    private PlanningFlowUserContext(String conversation, String sessionId, ChatMemory chatMemory) {
        super(conversation, sessionId, chatMemory);
        this.planId = conversation + "_plan";
    }

    public void clearAllMemoryPartitions(ChatMemory memory) {
        memory.clear(conversation);
        memory.clear(planId);
        if (initializeContext != null) {
            memory.clear(initializeContext.getConversation());
        }
        if (finalizeContext != null) {
            memory.clear(finalizeContext.getConversation());
        }
        for (BaseUserContext executorContext : getFlowContext().values()) {
            memory.clear(executorContext.getConversation());
        }
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
