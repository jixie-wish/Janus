package com.wish.models.context;

import lombok.Getter;
import org.springframework.ai.chat.memory.ChatMemory;

@Getter
public class PlanningFlowUserContext extends BaseFlowUserContext {

    private final String planId;
    private boolean planInitialized;
    private boolean planFinalized;

    public PlanningFlowUserContext(String conversation, ChatMemory chatMemory) {
        this(conversation, conversation + "_plan", chatMemory);
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
}
