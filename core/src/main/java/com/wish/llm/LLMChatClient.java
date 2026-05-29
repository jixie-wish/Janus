package com.wish.llm;

import com.wish.models.context.BaseUserContext;
import com.wish.models.context.Context;
import com.wish.support.ToolCallingPromptSupport;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    /** Adds tools whose names are not already registered on this client (default + extra). */
    public void addTools(List<Object> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        Set<String> registered = new LinkedHashSet<>();
        registered.addAll(collectToolNames(defaultTools));
        registered.addAll(collectToolNames(extraTools));
        for (Object tool : tools) {
            if (tool == null) {
                continue;
            }
            String name = resolveToolName(tool);
            if (name != null && registered.contains(name)) {
                continue;
            }
            extraTools.add(tool);
            if (name != null) {
                registered.add(name);
            }
        }
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
            List<ToolCallback> callbacks = new ArrayList<>();
            List<Object> methodTools = new ArrayList<>();
            for (Object tool : currentTools) {
                if (tool == null) {
                    continue;
                }
                if (tool instanceof ToolCallback callback) {
                    callbacks.add(callback);
                    continue;
                }
                if (tool instanceof ToolCallbackProvider provider) {
                    ToolCallback[] provided = provider.getToolCallbacks();
                    if (provided != null && provided.length > 0) {
                        Collections.addAll(callbacks, provided);
                    }
                    continue;
                }
                methodTools.add(tool);
            }
            List<Object> dedupedMethodTools = dedupeMethodToolsByName(methodTools);
            if (!dedupedMethodTools.isEmpty()) {
                Collections.addAll(callbacks, ToolCallbacks.from(dedupedMethodTools.toArray()));
            }
            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .toolCallbacks(callbacks.toArray(ToolCallback[]::new))
                    .toolContext(ToolCallingPromptSupport.DEFAULT_TOOL_CONTEXT)
                    .internalToolExecutionEnabled(false)
                    .build();
            prompt = new Prompt(messages, options);
        }
        ChatResponse response = chatModel.call(prompt);
        userContext.onResponse(response, resolveUsageTag(response));
        return Pair.of(response, prompt);
    }

    private static List<Object> dedupeMethodToolsByName(List<Object> methodTools) {
        LinkedHashMap<String, Object> byName = new LinkedHashMap<>();
        List<Object> unnamed = new ArrayList<>();
        for (Object tool : methodTools) {
            String name = resolveToolName(tool);
            if (name == null || name.isBlank()) {
                unnamed.add(tool);
                continue;
            }
            byName.putIfAbsent(name, tool);
        }
        List<Object> deduped = new ArrayList<>(byName.values());
        deduped.addAll(unnamed);
        return deduped;
    }

    private static Set<String> collectToolNames(List<Object> tools) {
        Set<String> names = new LinkedHashSet<>();
        if (tools == null) {
            return names;
        }
        for (Object tool : tools) {
            String name = resolveToolName(tool);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    static String resolveToolName(Object tool) {
        if (tool instanceof ToolCallback callback) {
            return callback.getToolDefinition().name();
        }
        if (tool instanceof ToolCallbackProvider provider) {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            if (callbacks != null && callbacks.length == 1) {
                return callbacks[0].getToolDefinition().name();
            }
        }
        for (Method method : tool.getClass().getMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null && !annotation.name().isBlank()) {
                return annotation.name();
            }
        }
        return null;
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
