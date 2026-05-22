package com.wish.service;

import com.wish.agentsession.CachedAgentSession;
import com.wish.agentsession.ToolCallAgentSession;
import com.wish.agent.ToolCallAgent;
import com.wish.llm.LLMChatClient;
import lombok.extern.slf4j.Slf4j;
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
    private final Map<String, CachedAgentSession> sessions = new ConcurrentHashMap<>();

    public ToolCallService(
            ApplicationContext applicationContext,
            @Value("${janus.agent.max-steps:10}") int maxSteps) {
        this.chatModels = resolveChatModels(applicationContext);
        this.mcpTools = resolveMcpTools(applicationContext);
        this.maxSteps = maxSteps;
        logStartup();
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
     * @param conversationId optional; when set, reuses in-process memory for the same id and model.
     *                       When null/blank, uses a one-off id (not cached).
     */
    public String run(String prompt, String model, String conversationId) {
        String modelKey = normalizeModelAlias(model);
        ChatModel chatModel = chatModels.get(modelKey);
        if (chatModel == null) {
            String available = chatModels.isEmpty()
                    ? "(none — check API keys and spring-ai model starters)"
                    : String.join(", ", chatModels.keySet());
            throw new IllegalArgumentException(
                    "Unknown model '%s'. Available: %s".formatted(model, available));
        }

        String normalizedConversationId = normalizeConversationId(conversationId);
        boolean ephemeral = normalizedConversationId == null;
        String conversationKey = ephemeral ? UUID.randomUUID().toString() : normalizedConversationId;

        CachedAgentSession session;
        if (ephemeral) {
            session = createSession(chatModel);
            log.debug("Ephemeral conversation {}", conversationKey);
        } else {
            String sessionKey = sessionKey(modelKey, conversationKey);
            CachedAgentSession existing = sessions.get(sessionKey);
            if (existing != null) {
                session = existing;
                log.info("Reusing conversation session '{}' (model={})", conversationKey, modelKey);
            } else {
                session = createSession(chatModel);
                sessions.put(sessionKey, session);
                log.info("New conversation session '{}' (model={})", conversationKey, modelKey);
            }
        }

        String result = session.agent().run(conversationKey, prompt);
        if (ephemeral) {
            return result;
        }
        return "conversation-id: %s%n%s".formatted(conversationKey, result);
    }

    public void clearSession(String conversationId, String model) {
        String modelKey = normalizeModelAlias(model);
        String normalized = normalizeConversationId(conversationId);
        if (normalized == null) {
            throw new IllegalArgumentException("conversation-id is required");
        }
        CachedAgentSession removed = sessions.remove(sessionKey(modelKey, normalized));
        if (removed == null) {
            log.info("No session to clear for conversation '{}' (model={})", normalized, modelKey);
        } else {
            log.info("Cleared conversation session '{}' (model={})", normalized, modelKey);
        }
    }

    protected CachedAgentSession createSession(ChatModel chatModel) {
        LLMChatClient llmChatClient = new LLMChatClient(chatModel, Collections.emptyList());
        ToolCallAgent agent = new ToolCallAgent(llmChatClient, maxSteps, mcpTools);
        return new ToolCallAgentSession(llmChatClient, agent);
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
