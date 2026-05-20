package com.wish.agent;

import com.wish.llm.LLMChatClient;

public abstract class ReactAgent extends BaseAgent{
    public ReactAgent(String name, String description, String systemPrompt, String nextStepPrompt, LLMChatClient llmChatClient, int maxSteps) {
        super(name, description, systemPrompt, nextStepPrompt, llmChatClient, maxSteps);
    }

    @Override
    public String step(String conversation) {
        boolean should_act = this.think(conversation);
        if(!should_act){
            return "Thinking complete - no action needed";
        } else {
            return act(conversation);
        }
    }

    public abstract boolean think(String conversation);
    public abstract String act(String conversation);
}
