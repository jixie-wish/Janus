package com.wish.models.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.List;

/** Tool-call session state, including per-step fields for think/act. */
public class ToolCallUserContext extends BaseUserContext {

    @Setter
    @Getter
    private ChatResponse currentChatResponse;

    @Setter
    @Getter
    private Prompt currentChatPrompt;

    @Getter
    @Setter
    private List<AssistantMessage.ToolCall> currentToolCalls = List.of();

    public ToolCallUserContext(String conversationId, ChatMemory chatMemory) {
        super(conversationId, chatMemory);
    }

    public void clearStepState() {
        currentChatResponse = null;
        currentChatPrompt = null;
        currentToolCalls = Collections.emptyList();
    }
}
