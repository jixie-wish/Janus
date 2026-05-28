package com.wish.support;

import com.wish.models.context.Context;
import com.wish.models.context.Context.TokenUsageCounter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Wraps agent runs with before/after token usage snapshots and logging. */
@Slf4j
public final class AgentRunSupport {

    private AgentRunSupport() {}
    private static final ThreadLocal<String> CURRENT_CONVERSATION_ID = new ThreadLocal<>();

    public static String currentConversationId() {
        return CURRENT_CONVERSATION_ID.get();
    }

    public static String runWithTokenLogging(Context context, Supplier<String> action) {
        String previousConversationId = CURRENT_CONVERSATION_ID.get();
        CURRENT_CONVERSATION_ID.set(context.getConversation());
        TokenUsageCounter usageBefore = context.getTokenUsage();
        Map<String, TokenUsageCounter> detailsBefore = context.getTokenUsageDetails();
        try {
            return action.get();
        } finally {
            TokenUsageCounter usageAfter = context.getTokenUsage();
            Map<String, TokenUsageCounter> detailsAfter = context.getTokenUsageDetails();
            logTokenUsage(
                    usageAfter.subtract(usageBefore),
                    usageAfter,
                    deltaUsageDetails(detailsAfter, detailsBefore),
                    detailsAfter);
            if (previousConversationId == null) {
                CURRENT_CONVERSATION_ID.remove();
            } else {
                CURRENT_CONVERSATION_ID.set(previousConversationId);
            }
        }
    }

    private static Map<String, TokenUsageCounter> deltaUsageDetails(
            Map<String, TokenUsageCounter> after, Map<String, TokenUsageCounter> before) {
        Set<String> tags = new LinkedHashSet<>();
        tags.addAll(after.keySet());
        tags.addAll(before.keySet());
        Map<String, TokenUsageCounter> delta = new LinkedHashMap<>();
        for (String tag : tags) {
            TokenUsageCounter afterUsage = after.getOrDefault(tag, new TokenUsageCounter());
            TokenUsageCounter beforeUsage = before.getOrDefault(tag, new TokenUsageCounter());
            delta.put(tag, afterUsage.subtract(beforeUsage));
        }
        return delta;
    }

    private static void logTokenUsage(
            TokenUsageCounter thisCall,
            TokenUsageCounter cumulative,
            Map<String, TokenUsageCounter> thisCallByTag,
            Map<String, TokenUsageCounter> cumulativeByTag) {
        log.info(
                "Token this call (prompt/completion/total): {}/{}/{}",
                thisCall.getPromptTokens(),
                thisCall.getCompletionTokens(),
                thisCall.getTotalTokens());
        logTokenUsageByTag("Token this call", thisCallByTag);
        log.info(
                "Token cumulative (prompt/completion/total): {}/{}/{}",
                cumulative.getPromptTokens(),
                cumulative.getCompletionTokens(),
                cumulative.getTotalTokens());
        logTokenUsageByTag("Token cumulative", cumulativeByTag);
    }

    private static void logTokenUsageByTag(String label, Map<String, TokenUsageCounter> usageByTag) {
        if (usageByTag.isEmpty()) {
            return;
        }
        usageByTag.forEach((tag, usage) -> log.info(
                "{} [{}] (prompt/completion/total): {}/{}/{}",
                label,
                tag,
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens()));
    }
}
