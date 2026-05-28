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

    /**
     * Optimization counter for drift control: consecutive assistant-only steps (no tool calls)
     * within one run. Used to inject stronger ephemeral reminders.
     */
    @Getter
    private int assistantOnlyStepCount;

    public ToolCallUserContext(String conversationId, ChatMemory chatMemory) {
        this(conversationId, null, chatMemory);
    }

    public ToolCallUserContext(String conversationId, String sessionId, ChatMemory chatMemory) {
        super(conversationId, sessionId, chatMemory);
    }

    public void clearStepState() {
        currentChatResponse = null;
        currentChatPrompt = null;
        currentToolCalls = Collections.emptyList();
    }

    public void incrementAssistantOnlyStepCount() {
        assistantOnlyStepCount++;
    }

    public void resetAssistantOnlyStepCount() {
        assistantOnlyStepCount = 0;
    }

    @Override
    public void beginRun() {
        super.beginRun();
        resetAssistantOnlyStepCount();
    }
}
