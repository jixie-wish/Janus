package com.wish.models.context;

import lombok.Getter;
import org.apache.commons.lang3.tuple.Triple;
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
    public Triple<Long, Long, Long> getTokenUsage() {
        long promptToken = 0L;
        long responseToken = 0L;
        long totalToken = 0L;

        Triple<Long, Long, Long> flowToken = super.getTokenUsage();
        Triple<Long, Long, Long> initialize = initializeContext.getTokenUsage();
        Triple<Long, Long, Long> finalize = finalizeContext.getTokenUsage();
        promptToken += flowToken.getLeft();
        promptToken += initialize.getLeft();
        promptToken += finalize.getLeft();

        responseToken += flowToken.getMiddle();
        responseToken += initialize.getMiddle();
        responseToken += finalize.getMiddle();

        totalToken += flowToken.getRight();
        totalToken += initialize.getRight();
        totalToken += finalize.getRight();

        return Triple.of(promptToken, responseToken, totalToken);
    }
}
