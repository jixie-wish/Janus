package com.wish.service;

import com.wish.agent.ToolCallAgent;
import com.wish.models.context.ToolCallUserContext;
import com.wish.llm.LLMChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ToolCallService {

    private static final String MCP_TOOL_CALLBACK_PROVIDER = "mcpToolCallbackProvider";

    protected final Map<String, ChatModel> chatModels;
    protected final List<Object> mcpTools;
    protected final int maxSteps;
    protected final ChatMemory chatMemory;
    protected final Map<String, LLMChatClient> chatClients = new ConcurrentHashMap<>();
    protected final Map<String, ToolCallAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, ToolCallUserContext> sessions = new ConcurrentHashMap<>();

    public ToolCallService(
            ApplicationContext applicationContext,
            @Value("${janus.agent.max-steps:10}") int maxSteps) {
        this.chatModels = resolveChatModels(applicationContext);
        this.mcpTools = resolveMcpTools(applicationContext);
        this.maxSteps = maxSteps;
        this.chatMemory = MessageWindowChatMemory.builder().build();
        for (Map.Entry<String, ChatModel> entry : chatModels.entrySet()) {
            registerModel(entry.getKey(), entry.getValue());
        }
        logStartup();
    }

    protected void registerModel(String modelKey, ChatModel chatModel) {
        LLMChatClient client = createChatClient(chatModel);
        ToolCallAgent agent = createAgent(client);
        chatClients.put(modelKey, client);
        agents.put(modelKey, agent);
    }

    protected LLMChatClient createChatClient(ChatModel chatModel) {
        return new LLMChatClient(chatModel, Collections.emptyList());
    }

    protected ToolCallAgent createAgent(LLMChatClient chatClient) {
        return new ToolCallAgent(chatClient, maxSteps, mcpTools);
    }

    protected void logStartup() {
        log.info("Registered chat models: {}", chatModels.keySet());
        chatModels.forEach((alias, model) -> {
            if (model instanceof OpenAiChatModel openAi) {
                log.info("OpenAI chat model alias '{}' -> api model '{}'", alias, openAi.getOptions().getModel());
            }
        });
        if (!mcpTools.isEmpty()) {
            log.info("Loaded {} MCP tool(s)", mcpTools.size());
        }
    }

    public String run(String prompt, String model) {
        return run(prompt, model, null);
    }

    /**
     * @param conversationId optional; when set, reuses in-process {@link ToolCallUserContext} for the same id and model.
     *                       When null/blank, uses a one-off id (not cached).
     */
    public String run(String prompt, String model, String conversationId) {
        String modelKey = normalizeModelAlias(model);
        ToolCallAgent agent = agents.get(modelKey);
        if (agent == null) {
            String available = chatModels.isEmpty()
                    ? "(none — check API keys and spring-ai model starters)"
                    : String.join(", ", chatModels.keySet());
            throw new IllegalArgumentException(
                    "Unknown model '%s'. Available: %s".formatted(model, available));
        }

        String normalizedConversationId = normalizeConversationId(conversationId);
        boolean ephemeral = normalizedConversationId == null;
        String conversationKey = ephemeral ? UUID.randomUUID().toString() : normalizedConversationId;

        ToolCallUserContext context = resolveContext(modelKey, conversationKey, ephemeral);
        try {
            String result = agent.run(context, prompt);
            if (ephemeral) {
                return result;
            }
            return "conversation-id: %s%n%s".formatted(conversationKey, result);
        } finally {
            if (ephemeral) {
                chatMemory.clear(conversationKey);
            }
        }
    }

    protected ToolCallUserContext resolveContext(String modelKey, String conversationKey, boolean ephemeral) {
        if (ephemeral) {
            log.debug("Ephemeral conversation {}", conversationKey);
            return new ToolCallUserContext(conversationKey, chatMemory);
        }
        String sessionKey = sessionKey(modelKey, conversationKey);
        return sessions.computeIfAbsent(
                sessionKey,
                key -> {
                    log.info("New conversation context '{}' (model={})", conversationKey, modelKey);
                    return new ToolCallUserContext(conversationKey, chatMemory);
                });
    }

    public void clearSession(String conversationId, String model) {
        String modelKey = normalizeModelAlias(model);
        String normalized = normalizeConversationId(conversationId);
        if (normalized == null) {
            throw new IllegalArgumentException("conversation-id is required");
        }
        String key = sessionKey(modelKey, normalized);
        ToolCallUserContext removed = sessions.remove(key);
        chatMemory.clear(normalized);
        if (removed == null) {
            log.info("No session to clear for conversation '{}' (model={})", normalized, modelKey);
        } else {
            log.info("Cleared conversation context '{}' (model={})", normalized, modelKey);
        }
    }

    protected static String sessionKey(String modelKey, String conversationId) {
        return modelKey + ":" + conversationId;
    }

    protected static String normalizeConversationId(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        String trimmed = conversationId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    protected static Map<String, ChatModel> resolveChatModels(ApplicationContext context) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        context.getBeansOfType(ChatModel.class).forEach((beanName, chatModel) -> {
            String alias = aliasForBeanName(beanName);
            if (alias != null) {
                models.putIfAbsent(alias, chatModel);
            }
        });
        return Map.copyOf(models);
    }

    public Set<String> allModels() {
        return chatModels.keySet();
    }

    protected static String aliasForBeanName(String beanName) {
        String lower = beanName.toLowerCase(Locale.ROOT);
        if (lower.contains("sensenova") || lower.contains("openai")) {
            return "sensenova";
        }
        return null;
    }

    protected static String normalizeModelAlias(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    protected static List<Object> resolveMcpTools(ApplicationContext context) {
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
