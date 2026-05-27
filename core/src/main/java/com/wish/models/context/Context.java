package com.wish.models.context;

import lombok.Getter;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

@Getter
public class Context {
    protected final String conversation;

    protected final ChatMemory chatMemory;

    private long promptToken;
    private long responseToken;
    private long totalToken;

    public Context(String conversation, ChatMemory chatMemory) {
        this.conversation = conversation;
        this.chatMemory = chatMemory;
        this.promptToken = 0L;
        this.responseToken = 0L;
        this.totalToken = 0L;
    }

    public void onResponse(ChatResponse chatResponse) {
        Usage usage = chatResponse.getMetadata().getUsage();
        this.promptToken += usage.getPromptTokens();
        this.responseToken += usage.getCompletionTokens();
        this.totalToken += usage.getTotalTokens();
    }

    public Triple<Long, Long, Long> getTokenUsage() {
        return Triple.of(this.promptToken, this.responseToken, this.totalToken);
    }
}
