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
 * Per-conversation state for agent runs. Cached by shell services; not stored on the agent.
 */
@Getter
public class BaseUserContext extends Context{

    private int currentStep;

    @Setter
    private String lastThinkResult = "";


    public BaseUserContext(String conversationId, ChatMemory chatMemory) {
        super(conversationId, chatMemory);
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


}
