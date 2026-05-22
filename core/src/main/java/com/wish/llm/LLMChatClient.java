package com.wish.llm;

import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;

import java.util.ArrayList;
import java.util.List;

public class LLMChatClient {

    @Getter
    private final ChatClient chatClient;

    @Getter
    private final ChatModel chatModel;

    @Getter
    private final ChatMemory chatMemory;

    @Getter
    private final Advisor advisor;

    @Getter
    private final List<Object> defaultTools;

    private final List<Object> extraTools;

    public LLMChatClient(ChatModel chatModel, Advisor advisor, ChatMemory chatMemory, List<Object> defaultTools) {
        this.chatMemory = chatMemory;
        this.chatModel = chatModel;
        this.advisor = advisor;
        this.defaultTools = List.copyOf(defaultTools);
        this.chatClient = ChatClient
                .builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        this.extraTools = new ArrayList<>();
    }

    public LLMChatClient(ChatModel chatModel, List<Object> defaultTools) {
        this.chatMemory = MessageWindowChatMemory.builder().build();
        this.chatModel = chatModel;
        this.advisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        this.defaultTools = List.copyOf(defaultTools);
        var builder = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());
        if (!this.defaultTools.isEmpty()) {
            builder.defaultTools(this.defaultTools);
        }
        this.chatClient = builder.build();
        this.extraTools = new ArrayList<>();
    }

    public void addTools(List<Object> tools) {
        this.extraTools.addAll(tools);
    }

    public boolean isStuck(String conversation, int duplicateThreshold) {
        List<Message> messages = this.chatMemory.get(conversation);
        if (messages.size() < 2) {
            return false;
        }

        final String lastAssistantText = getLastAssistantText(conversation);
        if (lastAssistantText.isBlank()) {
            return false;
        }

        long duplicate = messages.stream()
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT
                        && lastAssistantText.equals(message.getText()))
                .count();
        return duplicate > duplicateThreshold;
    }

    public String getLastAssistantText(String conversation) {
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

    public void addUserMemory(String memory, String conversation) {
        addMemory(new UserMessage(memory), conversation);
    }

    public void addSystemMemory(String memory, String conversation) {
        addMemory(new SystemMessage(memory), conversation);
    }

    /** Whether this conversation key already has messages in memory (continuing session). */
    public boolean hasConversation(String conversation) {
        List<Message> messages = chatMemory.get(conversation);
        return messages != null && !messages.isEmpty();
    }

    /**
     * Replaces the entire conversation memory.
     * <p>
     * {@link ChatMemory#add(String, List)} appends to existing messages (see
     * {@link org.springframework.ai.chat.memory.MessageWindowChatMemory#add}).
     * Use this when persisting a full snapshot such as {@code ToolExecutionResult#conversationHistory()}.
     */
    public void replaceMemory(List<Message> messages, String conversation) {
        chatMemory.clear(conversation);
        if (messages != null && !messages.isEmpty()) {
            chatMemory.add(conversation, messages);
        }
    }

    /**
     * Appends a single message to the conversation memory.
     */
    public void addMemory(Message message, String conversation) {
        chatMemory.add(conversation, message);
    }

    public Pair<ChatResponse, Prompt> askWithTools(String conversation, List<String> newMessages, List<Object> tools) {
        List<Object> currentTools = new ArrayList<>();
        currentTools.addAll(defaultTools);
        currentTools.addAll(extraTools);
        currentTools.addAll(tools);

        List<Message> messages = new ArrayList<>(chatMemory.get(conversation));
        for (String user : newMessages) {
            if (user == null || user.isBlank()) {
                continue;
            }
            UserMessage userMessage = new UserMessage(user);
            messages.add(userMessage);
            chatMemory.add(conversation, userMessage);
        }

        Prompt prompt;
        if (currentTools.isEmpty()) {
            prompt = new Prompt(messages);
        } else {
            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .toolCallbacks(ToolCallbacks.from(currentTools.toArray()))
                    .internalToolExecutionEnabled(false)
                    .build();
            prompt = new Prompt(messages, options);
        }
        ChatResponse response = chatModel.call(prompt);
        return Pair.of(response, prompt);
    }
}
