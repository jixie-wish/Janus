package com.wish.models.session;

import com.wish.models.context.BaseUserContext;
import com.wish.models.context.Context;
import lombok.Getter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-process scope for multiple prompt runs ({@link #beginPrompt} / {@link #endPrompt}).
 * Each prompt uses a dedicated {@link Context} (isolated {@link ChatMemory} partition).
 * When {@link #persistent}, {@link #endPrompt} merges context messages into session memory.
 */
@Getter
public abstract class Session<T extends Context> {

    public static final String SESSION_KEY_PREFIX = "session:";
    public static final String CONTEXT_KEY_PREFIX = "context:";

    /** Inserted after hydrated session summaries so the model treats them as background, not the current task. */
    public static final String SESSION_HISTORY_FRAMING_PROMPT = """
            The messages above are summarized prior turns in this session (background only).
            The current task is provided in a later user message in this conversation.
            """;

    private final String sessionId;
    private final ChatMemory chatMemory;
    private final boolean persistent;

    private String currentContextId;
    private T currentContext;
    private String currentPromptRequest;
    private final Map<String, T> historyContext = new LinkedHashMap<>();

    protected Session(String sessionId, ChatMemory chatMemory, boolean persistent) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        this.sessionId = sessionId.trim();
        this.chatMemory = chatMemory;
        this.persistent = persistent;
    }

    public String getSessionMemoryKey() {
        return SESSION_KEY_PREFIX + sessionId;
    }

    public synchronized T beginPrompt(String request) {
        if (currentContext != null) {
            throw new IllegalStateException("Prompt already in progress for session " + sessionId);
        }
        String contextId = CONTEXT_KEY_PREFIX + sessionId + ":" + UUID.randomUUID();
        currentContextId = contextId;
        T context = createContext(contextId);
        currentContext = context;
        currentPromptRequest = request == null ? "" : request.trim();
        context.setPromptRequest(currentPromptRequest);
        historyContext.put(contextId, context);
        initializeContext(context, request);
        return context;
    }

    public synchronized void endPrompt() {
        endPrompt(null);
    }

    public synchronized void endPrompt(String runResult) {
        if (currentContext == null) {
            return;
        }
        T context = currentContext;
        if (runResult != null) {
            // Optimization: provide step-level run output as a summarization hint,
            // especially when the final answer was not emitted via create_chat_completion.
            context.setRunResult(runResult);
        }
        try {
            if (persistent) {
                extractToSessionMemory(context);
            }
        } finally {
            // Optimization: keep only session memory across prompts; context partition is one-run scope.
            clearContextMemory(context);
            currentContext = null;
            currentContextId = null;
            currentPromptRequest = null;
        }
    }

    public synchronized void clear() {
        chatMemory.clear(getSessionMemoryKey());
        for (T context : historyContext.values()) {
            clearContextMemory(context);
        }
        historyContext.clear();
        currentContext = null;
        currentContextId = null;
    }

    protected List<Message> getSessionMessages() {
        List<Message> messages = chatMemory.get(getSessionMemoryKey());
        return messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * Copies session memory into {@code target} and appends {@link #SESSION_HISTORY_FRAMING_PROMPT}.
     *
     * @return number of hydrated session messages (excludes framing); 0 if session memory was empty
     */
    protected int hydrateSessionHistory(BaseUserContext target) {
        List<Message> sessionMessages = getSessionMessages();
        if (sessionMessages.isEmpty()) {
            return 0;
        }
        int hydrated = 0;
        for (Message message : sessionMessages) {
            target.addMemory(message);
            hydrated++;
        }
        target.addMemory(new SystemMessage(SESSION_HISTORY_FRAMING_PROMPT));
        target.setSessionHydrationMessageCount(hydrated);
        return hydrated;
    }

    protected void clearContextMemory(T context) {
        chatMemory.clear(context.getConversation());
    }

    protected abstract T createContext(String contextId);

    protected abstract void initializeContext(T context, String request);

    protected abstract void extractToSessionMemory(T context);
}
