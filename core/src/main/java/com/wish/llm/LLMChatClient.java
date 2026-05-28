package com.wish.llm;

import com.wish.models.context.BaseUserContext;
import com.wish.models.context.Context;
import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    public List<Object> getExtraTools() {
        return List.copyOf(extraTools);
    }

    public Pair<ChatResponse, Prompt> askWithTools(BaseUserContext userContext, List<String> newMessages, List<Object> tools) {
        return askWithTools(userContext, newMessages, tools, true);
    }

    /**
     * @param persistNewMessages when false, {@code newMessages} are included in this request's {@link Prompt}
     *                           but not stored in {@link ChatMemory}. Optimization: per-step guidance (e.g.
     *                           {@code nextStepPrompt}) must not be persisted as user turns—otherwise the next
     *                           prompt's {@link com.wish.models.session.PromptRunSummarizer} and session memory
     *                           inherit internal instructions and multi-turn context degrades.
     */
    public Pair<ChatResponse, Prompt> askWithTools(
            BaseUserContext userContext, List<String> newMessages, List<Object> tools, boolean persistNewMessages) {
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
            if (persistNewMessages) {
                userContext.addMemory(userMessage);
            }
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
        userContext.onResponse(response, resolveUsageTag(response));
        return Pair.of(response, prompt);
    }

    private static String resolveUsageTag(ChatResponse response) {
        AssistantMessage assistantMessage = response.getResult() != null ? response.getResult().getOutput() : null;
        if (assistantMessage == null || assistantMessage.getToolCalls() == null || assistantMessage.getToolCalls().isEmpty()) {
            return Context.DEFAULT_USAGE_TAG;
        }
        Set<String> toolNames = new LinkedHashSet<>();
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            if (toolCall.name() != null && !toolCall.name().isBlank()) {
                toolNames.add(toolCall.name());
            }
        }
        if (toolNames.isEmpty()) {
            return Context.DEFAULT_USAGE_TAG;
        }
        return "tools:" + String.join(",", toolNames);
    }
}
