package com.wish.llm;

import com.wish.models.context.BaseUserContext;
import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;

import java.util.ArrayList;
import java.util.List;

/** Stateless LLM facade: conversation state lives in {@link BaseUserContext}. */
public class LLMChatClient {

    @Getter
    private final ChatModel chatModel;

    @Getter
    private final List<Object> defaultTools;

    private final List<Object> extraTools;

    public LLMChatClient(ChatModel chatModel, List<Object> defaultTools) {
        this.chatModel = chatModel;
        this.defaultTools = List.copyOf(defaultTools);
        this.extraTools = new ArrayList<>();
    }

    public void addTools(List<Object> tools) {
        this.extraTools.addAll(tools);
    }

    public Pair<ChatResponse, Prompt> askWithTools(BaseUserContext userContext, List<String> newMessages, List<Object> tools) {
        List<Object> currentTools = new ArrayList<>();
        currentTools.addAll(defaultTools);
        currentTools.addAll(extraTools);
        currentTools.addAll(tools);

        List<Message> messages = new ArrayList<>(userContext.getAllMessages());
        for (String user : newMessages) {
            if (user == null || user.isBlank()) {
                continue;
            }
            UserMessage userMessage = new UserMessage(user);
            messages.add(userMessage);
            userContext.addMemory(userMessage);
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
        userContext.onResponse(response);
        return Pair.of(response, prompt);
    }
}
