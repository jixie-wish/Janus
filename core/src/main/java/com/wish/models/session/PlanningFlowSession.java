package com.wish.models.session;

import com.wish.models.context.PlanningFlowUserContext;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/** {@link Session} for {@link PlanningFlowUserContext} prompt runs (shell planning flow). */
public class PlanningFlowSession extends Session<PlanningFlowUserContext> {

    private final PromptRunSummarizer summarizer;

    public PlanningFlowSession(String sessionId, ChatMemory chatMemory, ChatModel chatModel, String summarySystemPrompt) {
        super(sessionId, chatMemory, true);
        this.summarizer = new PromptRunSummarizer(chatModel, summarySystemPrompt);
    }

    @Override
    protected PlanningFlowUserContext createContext(String contextId) {
        return PlanningFlowUserContext.forSession(contextId, getSessionId(), getChatMemory());
    }

    @Override
    protected void initializeContext(PlanningFlowUserContext context, String request) {
        hydrateSessionHistory(context.getInitializeContext());
    }

    @Override
    protected void extractToSessionMemory(PlanningFlowUserContext context) {
        String request = context.getPromptRequest();
        if (request == null || request.isBlank()) {
            return;
        }
        PromptRunSummary summary = summarizer.summarize(request, List.of(), context.getRunResult());
        if (summary.agentResult() == null || summary.agentResult().isBlank()) {
            return;
        }
        String sessionKey = getSessionMemoryKey();
        getChatMemory().add(sessionKey, new UserMessage(summary.userRequest()));
        getChatMemory().add(sessionKey, new AssistantMessage(summary.agentResult()));
    }

    @Override
    protected void clearContextMemory(PlanningFlowUserContext context) {
        context.clearAllMemoryPartitions(getChatMemory());
    }
}
