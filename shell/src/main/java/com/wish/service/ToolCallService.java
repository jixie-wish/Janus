package com.wish.service;

import com.wish.agent.ToolCallAgent;
import com.wish.llm.LLMChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ToolCallService {

    private static final String MCP_TOOL_CALLBACK_PROVIDER = "mcpToolCallbackProvider";

    private final Map<String, ChatModel> chatModels;
    private final List<Object> extraTools;
    private final int maxSteps;

    public ToolCallService(
            ApplicationContext applicationContext,
            @Value("${janus.agent.max-steps:10}") int maxSteps) {
        this.chatModels = resolveChatModels(applicationContext);
        this.extraTools = resolveExtraTools(applicationContext);
        this.maxSteps = maxSteps;
        log.info("Registered chat models: {}", chatModels.keySet());
        if (!extraTools.isEmpty()) {
            log.info("Loaded {} extra tool(s) (e.g. MCP)", extraTools.size());
        }
    }

    public String run(String prompt, String model) {
        String modelKey = normalizeModelAlias(model);
        ChatModel chatModel = chatModels.get(modelKey);
        if (chatModel == null) {
            String available = chatModels.isEmpty()
                    ? "(none — check API keys and spring-ai model starters)"
                    : String.join(", ", chatModels.keySet());
            throw new IllegalArgumentException(
                    "Unknown model '%s'. Available: %s".formatted(model, available));
        }

        LLMChatClient llmChatClient = new LLMChatClient(chatModel, Collections.emptyList());
        ToolCallAgent agent = new ToolCallAgent(llmChatClient, maxSteps, extraTools);
        String conversationId = UUID.randomUUID().toString();
        return agent.run(conversationId, prompt);
    }

    private static Map<String, ChatModel> resolveChatModels(ApplicationContext context) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        context.getBeansOfType(ChatModel.class).forEach((beanName, chatModel) -> {
            String alias = aliasForBeanName(beanName);
            if (alias != null) {
                models.putIfAbsent(alias, chatModel);
            }
        });
        return Map.copyOf(models);
    }

    /**
     * Maps Spring bean names from spring-ai starters to CLI aliases.
     */
    private static String aliasForBeanName(String beanName) {
        String lower = beanName.toLowerCase(Locale.ROOT);
        if (lower.contains("sensenova") || lower.contains("openai")) {
            return "sensenova";
        }
        return null;
    }

    private static String normalizeModelAlias(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    private static List<Object> resolveExtraTools(ApplicationContext context) {
        if (!context.containsBean(MCP_TOOL_CALLBACK_PROVIDER)) {
            log.debug("Bean '{}' not found; MCP tools disabled", MCP_TOOL_CALLBACK_PROVIDER);
            return List.of();
        }
        ToolCallbackProvider provider = context.getBean(MCP_TOOL_CALLBACK_PROVIDER, ToolCallbackProvider.class);
        ToolCallback[] callbacks = provider.getToolCallbacks();
        if (callbacks == null || callbacks.length == 0) {
            return List.of();
        }
        List<Object> tools = new ArrayList<>(callbacks.length);
        Collections.addAll(tools, callbacks);
        return List.copyOf(tools);
    }
}
