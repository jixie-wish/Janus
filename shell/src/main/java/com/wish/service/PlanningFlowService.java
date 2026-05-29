package com.wish.service;

import com.wish.agent.base.BaseAgent;
import com.wish.agent.base.ToolCallAgent;
import com.wish.flow.PlanningFlow;
import com.wish.llm.LLMChatClient;
import com.wish.models.context.PlanningFlowUserContext;
import com.wish.models.session.PlanningFlowSession;
import com.wish.models.session.Session;
import com.wish.support.AgentRunSupport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.wish.service.ToolCallService.*;

@Slf4j
@Service
public class PlanningFlowService {

    private final Map<String, PlanningFlow> planningFlows = new ConcurrentHashMap<>();
    private final Map<String, PlanningFlowSession> sessions = new ConcurrentHashMap<>();
    private final JanusService janusService;
    private final DAService daService;
    private final SWEService sweService;
    private final ChatMemory chatMemory;

    public PlanningFlowService(JanusService janusService, DAService daService, SWEService sweService) {
        this.janusService = janusService;
        this.daService = daService;
        this.sweService = sweService;
        this.chatMemory = MessageWindowChatMemory.builder().build();
    }

    @PostConstruct
    public void initializeAgents() {
        Map<String, ToolCallAgent> janusAgents = janusService.agents;
        Map<String, ToolCallAgent> daAgents = daService.agents;
        Map<String, ToolCallAgent> sweAgents = sweService.agents;
        Map<String, LLMChatClient> chatClients = janusService.chatClients;
        for (String model : janusAgents.keySet()) {
            Map<String, BaseAgent> agents = new HashMap<>();
            agents.put("janus", janusAgents.get(model));
            agents.put("da", daAgents.get(model));
            agents.put("swe", sweAgents.get(model));
            PlanningFlow planningFlow = new PlanningFlow(chatClients.get(model), agents, "janus");
            planningFlows.put(model, planningFlow);
        }
        log.info("Registered planning flows for models: {}", planningFlows.keySet());
    }

    public String run(String prompt, String model) {
        return run(prompt, model, null);
    }

    /**
     * @param sessionId optional; when set, reuses in-process {@link PlanningFlowSession} for the same id and model.
     *                  When null/blank, uses a one-off context (not merged into session memory).
     */
    public String run(String prompt, String model, String sessionId) {
        String modelKey = normalizeModelAlias(model);
        PlanningFlow planningFlow = planningFlows.get(modelKey);
        if (planningFlow == null) {
            Set<String> available = planningFlows.isEmpty()
                    ? janusService.allModels()
                    : planningFlows.keySet();
            throw new IllegalArgumentException(
                    "Unknown model '%s'. Available: %s".formatted(model, String.join(", ", available)));
        }

        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null) {
            return runEphemeral(planningFlow, prompt);
        }
        return runPersistent(planningFlow, prompt, modelKey, normalizedSessionId);
    }

    private String runPersistent(PlanningFlow planningFlow, String prompt, String modelKey, String sessionId) {
        String cacheKey = sessionKey(modelKey, sessionId);
        PlanningFlowSession session = sessions.computeIfAbsent(
                cacheKey,
                key -> {
                    log.info("New planning-flow session '{}' (model={})", sessionId, modelKey);
                    ToolCallAgent janusAgent = janusService.agents.get(modelKey);
                    ChatModel chatModel = janusService.chatClients.get(modelKey).getChatModel();
                    String summaryPrompt = janusAgent == null
                            ? ToolCallAgent.DEFAULT_SESSION_SUMMARY_SYSTEM_PROMPT
                            : janusAgent.sessionSummarySystemPrompt();
                    return new PlanningFlowSession(sessionId, chatMemory, chatModel, summaryPrompt);
                });
        PlanningFlowUserContext context = session.beginPrompt(prompt);
        try {
            String result = AgentRunSupport.runWithTokenLogging(
                    context, () -> planningFlow.execute(context, prompt));
            session.endPrompt(result);
            return "conversation-id: %s%n%s".formatted(sessionId, result);
        } catch (RuntimeException e) {
            session.endPrompt();
            throw e;
        }
    }

    private String runEphemeral(PlanningFlow planningFlow, String prompt) {
        String contextId = "ephemeral:" + UUID.randomUUID();
        PlanningFlowUserContext planningFlowUserContext = new PlanningFlowUserContext(contextId, chatMemory);
        try {
            return AgentRunSupport.runWithTokenLogging(
                    planningFlowUserContext, () -> planningFlow.execute(planningFlowUserContext, prompt));
        } finally {
            planningFlowUserContext.clearAllMemoryPartitions(chatMemory);
            clearPlanningFlowBashSessions(planningFlow, contextId);
        }
    }

    public void clearSession(String sessionId, String model) {
        String modelKey = normalizeModelAlias(model);
        String normalized = normalizeSessionId(sessionId);
        if (normalized == null) {
            throw new IllegalArgumentException("conversation-id is required");
        }
        String key = sessionKey(modelKey, normalized);
        PlanningFlowSession removed = sessions.remove(key);
        PlanningFlow planningFlow = planningFlows.get(modelKey);
        clearPlanningFlowBashSessions(planningFlow, normalized);
        if (removed == null) {
            chatMemory.clear(Session.SESSION_KEY_PREFIX + normalized);
            log.info("No planning-flow session to clear for conversation '{}' (model={})", normalized, modelKey);
        } else {
            removed.clear();
            log.info("Cleared planning-flow session '{}' (model={})", normalized, modelKey);
        }
    }

    public Set<String> allModels() {
        return planningFlows.keySet();
    }

    private static void clearPlanningFlowBashSessions(PlanningFlow planningFlow, String scopeId) {
        if (planningFlow == null || scopeId == null || scopeId.isBlank()) {
            return;
        }
        for (BaseAgent agent : planningFlow.getAgents().values()) {
            if (agent instanceof ToolCallAgent toolCallAgent) {
                clearConversationBashSession(toolCallAgent, scopeId);
            }
        }
    }
}
