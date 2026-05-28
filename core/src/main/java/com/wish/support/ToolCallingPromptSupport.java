package com.wish.support;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring AI {@link org.springframework.ai.model.tool.DefaultToolCallingManager} only copies
 * {@code toolContext} from the prompt when the map is non-empty. Community tools such as
 * {@code FileSystemTools.read(..., ToolContext)} fail validation otherwise.
 */
public final class ToolCallingPromptSupport {

    public static final Map<String, Object> DEFAULT_TOOL_CONTEXT = Map.of("janus", "tool-context");

    private ToolCallingPromptSupport() {}

    /**
     * Returns a prompt suitable for {@link org.springframework.ai.model.tool.ToolCallingManager#executeToolCalls}.
     * Rebuilds options so {@code toolContext} is always non-empty and {@code internalToolExecutionEnabled} is false.
     */
    public static Prompt forToolExecution(Prompt source) {
        if (source == null) {
            return null;
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        Map<String, Object> context = Map.of();
        if (source.getOptions() instanceof ToolCallingChatOptions options) {
            if (options.getToolCallbacks() != null) {
                callbacks.addAll(options.getToolCallbacks());
            }
            Map<String, Object> existing = options.getToolContext();
            if (existing != null && !existing.isEmpty()) {
                context = existing;
            }
        }
        if (context.isEmpty()) {
            context = DEFAULT_TOOL_CONTEXT;
        }
        ToolCallingChatOptions executionOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .toolContext(context)
                .internalToolExecutionEnabled(false)
                .build();
        return new Prompt(source.getInstructions(), executionOptions);
    }
}
