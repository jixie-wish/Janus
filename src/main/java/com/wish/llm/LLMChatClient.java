package com.wish.llm;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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
    @Setter
    private final ChatClient chatClient;

    @Getter
    @Setter
    private final ChatModel chatModel;

    @Getter
    @Setter
    private final ChatMemory chatMemory;

    @Getter
    @Setter
    private final Advisor advisor;

    @Getter
    @Setter
    private final List<Object> defaultTools;

    @Getter
    @Setter
    private final List<Object> extraTools;

    public LLMChatClient(ChatModel chatModel, Advisor advisor, ChatMemory chatMemory, List<Object> defaultTools) {
        this.chatMemory = chatMemory;
        this.chatModel = chatModel;
        this.advisor = advisor;
        this.defaultTools = defaultTools;
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
        this.defaultTools = defaultTools;
        this.chatClient = ChatClient
                .builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(defaultTools)
                .build();
        this.extraTools = new ArrayList<>();
    }

    public void addTools(List<Object> tools) {
        this.extraTools.addAll(tools);
    }


    public boolean isStuck(String conversation, int duplicateThreshold) {
        List<Message> messages = this.chatMemory.get(conversation);
        if(messages.size() < 2) {
            return false;
        }
        Message lastMessage = messages.getLast();
        if(lastMessage.getText().isBlank()) {
            return false;
        }

        long duplicate = messages.stream().filter(
                message ->
                        message.getMessageType()== MessageType.ASSISTANT && message.getText().equals(lastMessage.getText()))
                .count();
        return duplicate > duplicateThreshold;
    }

    public void addUserMemory(String memory, String conversation) {
        UserMessage userMessage = new UserMessage(memory);
        addMemory(userMessage, conversation);
    }

    public void addSystemMemory(String memory, String conversation) {
        SystemMessage systemMessage = new SystemMessage(memory);
        addMemory(systemMessage, conversation);
    }

    public void addMemory(List<Message> messages, String conversation) {
        List<Message> history = chatMemory.get(conversation);
        history.addAll(messages);
        chatMemory.add(conversation, history);
    }

    public void addMemory(Message message, String conversation) {
        // Spring AI always set, not append memory
        List<Message> history = chatMemory.get(conversation);
        history.add(message);
        chatMemory.add(conversation, history);
    }

    public Pair<ChatResponse, Prompt> askWithTools(String conversation, List<String> newMessages, List<Object> tools) {
        List<Object> currentTools = new ArrayList<>();
        currentTools.addAll(extraTools);
        currentTools.addAll(tools);
        List<Message> messages = new ArrayList<>(chatMemory.get(conversation));

        for(String user : newMessages) {
            messages.add(new UserMessage(user));
        }
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(currentTools.toArray()))
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = chatModel.call(prompt);
        return Pair.of(response, prompt);
    }
}
