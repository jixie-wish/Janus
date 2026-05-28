package com.wish.models.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

/**
 * Per-prompt-run state for agent {@code run}. One instance per {@code Session#beginPrompt};
 * shell services cache {@link com.wish.models.session.Session}, not individual contexts.
 */
@Getter
public class BaseUserContext extends Context{

    private int currentStep;

    @Setter
    private String lastThinkResult = "";


    public BaseUserContext(String conversationId, ChatMemory chatMemory) {
        this(conversationId, null, chatMemory);
    }

    public BaseUserContext(String conversationId, String sessionId, ChatMemory chatMemory) {
        super(conversationId, sessionId, chatMemory);
        this.currentStep = 0;
    }

    public void beginRun() {
        currentStep = 0;
        lastThinkResult = "";
    }

    public int nextStep() {
        return ++currentStep;
    }

    public boolean isStuck(int duplicateThreshold) {
        List<Message> messages = chatMemory.get(conversation);
        if (messages.size() < 2) {
            return false;
        }

        final String lastAssistantText = getLastAssistantText();
        if (lastAssistantText.isBlank()) {
            return false;
        }

        long duplicate = messages.stream()
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT
                        && lastAssistantText.equals(message.getText()))
                .count();
        return duplicate > duplicateThreshold;
    }

    public String getLastAssistantText() {
        List<Message> messages = chatMemory.get(conversation);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistant) {
                String text = assistant.getText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    public void addUserMemory(String memory) {
        chatMemory.add(conversation, new UserMessage(memory));
    }

    public void addSystemMemory(String memory) {
        chatMemory.add(conversation, new SystemMessage(memory));
    }

    public boolean hasConversation() {
        List<Message> messages = chatMemory.get(conversation);
        return messages != null && !messages.isEmpty();
    }

    public void replaceMemory(List<Message> messages) {
        chatMemory.clear(conversation);
        if (messages != null && !messages.isEmpty()) {
            chatMemory.add(conversation, messages);
        }
    }

    public void addMemory(Message message) {
        chatMemory.add(conversation, message);
    }

    public List<Message> getAllMessages() {
        return chatMemory.get(conversation);
    }

    /**
     * Messages produced during this prompt run (excludes session hydration).
     * Used as input to session summarization; excludes ephemeral nextStepPrompt when
     * {@link com.wish.llm.LLMChatClient#askWithTools} is called with {@code persistNewMessages=false}.
     */
    public List<Message> getPromptMessages() {
        List<Message> messages = getAllMessages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int from = getSessionHydrationMessageCount();
        if (from >= messages.size()) {
            return List.of();
        }
        return List.copyOf(messages.subList(from, messages.size()));
    }
}

