package com.wish.agent;

import com.wish.llm.LLMChatClient;

public abstract class ReactAgent extends BaseAgent {

    protected ReactAgent(
            String name,
            String description,
            String systemPrompt,
            String nextStepPrompt,
            LLMChatClient llmChatClient,
            int maxSteps) {
        super(name, description, systemPrompt, nextStepPrompt, llmChatClient, maxSteps);
    }

    @Override
    public String step(String conversation) {
        boolean shouldAct = think(conversation);
        if (!shouldAct) {
            return "Thinking complete - no action needed";
        }
        return act(conversation);
    }

    public abstract boolean think(String conversation);

    public abstract String act(String conversation);
}
