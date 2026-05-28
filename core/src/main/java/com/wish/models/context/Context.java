package com.wish.models.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class Context {
    public static final String DEFAULT_USAGE_TAG = "default";

    protected final String conversation;

    /** When set, tools (e.g. bash) scope to this id across prompt runs in the same session. */
    protected final String sessionId;

    protected final ChatMemory chatMemory;

    private final TokenUsageCounter tokenUsage;
    private final Map<String, TokenUsageCounter> tokenUsageByTag;

    /** Messages copied from session memory at prompt start; skipped on {@link com.wish.models.session.Session#endPrompt}. */
    @Getter
    @Setter
    private int sessionHydrationMessageCount;

    /** Original shell/API request for this prompt run. */
    @Getter
    @Setter
    private String promptRequest;

    /** {@code agent.run} return value (step outputs); optional hint for summarization. */
    @Getter
    @Setter
    private String runResult;

    public Context(String conversation, ChatMemory chatMemory) {
        this(conversation, null, chatMemory);
    }

    public Context(String conversation, String sessionId, ChatMemory chatMemory) {
        this.conversation = conversation;
        this.sessionId = sessionId;
        this.chatMemory = chatMemory;
        this.tokenUsage = new TokenUsageCounter();
        this.tokenUsageByTag = new LinkedHashMap<>();
    }

    public void onResponse(ChatResponse chatResponse) {
        onResponse(chatResponse, DEFAULT_USAGE_TAG);
    }

    public void onResponse(ChatResponse chatResponse, String usageTag) {
        Usage usage = chatResponse.getMetadata().getUsage();
        long prompt = usage.getPromptTokens();
        long completion = usage.getCompletionTokens();
        long total = usage.getTotalTokens();
        this.tokenUsage.add(prompt, completion, total);

        String normalizedTag = normalizeUsageTag(usageTag);
        tokenUsageByTag
                .computeIfAbsent(normalizedTag, key -> new TokenUsageCounter())
                .add(prompt, completion, total);
    }

    public TokenUsageCounter getTokenUsage() {
        return tokenUsage.copy();
    }

    public Map<String, TokenUsageCounter> getTokenUsageDetails() {
        Map<String, TokenUsageCounter> details = new LinkedHashMap<>();
        tokenUsageByTag.forEach((tag, usage) -> details.put(tag, usage.copy()));
        return Map.copyOf(details);
    }

    private static String normalizeUsageTag(String usageTag) {
        if (usageTag == null) {
            return DEFAULT_USAGE_TAG;
        }
        String trimmed = usageTag.trim();
        return trimmed.isEmpty() ? DEFAULT_USAGE_TAG : trimmed;
    }

    @Getter
    public static final class TokenUsageCounter {
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;

        public TokenUsageCounter add(long promptTokens, long completionTokens, long totalTokens) {
            this.promptTokens += promptTokens;
            this.completionTokens += completionTokens;
            this.totalTokens += totalTokens;
            return this;
        }

        public TokenUsageCounter add(TokenUsageCounter usage) {
            if (usage == null) {
                return this;
            }
            return add(usage.promptTokens, usage.completionTokens, usage.totalTokens);
        }

        public TokenUsageCounter subtract(TokenUsageCounter usage) {
            if (usage == null) {
                return copy();
            }
            return new TokenUsageCounter()
                    .add(
                            this.promptTokens - usage.promptTokens,
                            this.completionTokens - usage.completionTokens,
                            this.totalTokens - usage.totalTokens);
        }

        public TokenUsageCounter copy() {
            return new TokenUsageCounter().add(promptTokens, completionTokens, totalTokens);
        }
    }
}
