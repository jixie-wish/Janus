package com.wish.models.session;

import com.wish.models.context.ToolCallUserContext;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

/** {@link Session} for {@link ToolCallUserContext} prompt runs (shell tool-call / janus / da / swe). */
public class ToolCallSession extends Session<ToolCallUserContext> {

    private final PromptRunSummarizer summarizer;

    public ToolCallSession(String sessionId, ChatMemory chatMemory, ChatModel chatModel, String summarySystemPrompt) {
        super(sessionId, chatMemory, true);
        this.summarizer = new PromptRunSummarizer(chatModel, summarySystemPrompt);
    }

    /** For tests without LLM summarization (heuristic + runResult fallback only). */
    ToolCallSession(String sessionId, ChatMemory chatMemory, String summarySystemPrompt) {
        this(sessionId, chatMemory, null, summarySystemPrompt);
    }

    @Override
    protected ToolCallUserContext createContext(String contextId) {
        return new ToolCallUserContext(contextId, getSessionId(), getChatMemory());
    }

    @Override
    protected void initializeContext(ToolCallUserContext context, String request) {
        // Optimization: hydration count excludes framing so summarize can distinguish prior session
        // history from messages generated in this prompt run.
        hydrateSessionHistory(context);
    }

    @Override
    protected void extractToSessionMemory(ToolCallUserContext context) {
        // Optimization: summarize only real user/tool/assistant turns from getPromptMessages(), not
        // per-step nextStepPrompt (kept out of ChatMemory by ToolCallAgent).
        String request = context.getPromptRequest();
        if (request == null || request.isBlank()) {
            return;
        }
        PromptRunSummary summary =
                summarizer.summarize(request, context.getPromptMessages(), context.getRunResult());
        if (summary.agentResult() == null || summary.agentResult().isBlank()) {
            return;
        }
        String sessionKey = getSessionMemoryKey();
        getChatMemory().add(sessionKey, new UserMessage(summary.userRequest()));
        getChatMemory().add(sessionKey, new AssistantMessage(summary.agentResult()));
    }
}
