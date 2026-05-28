package com.wish.models.session;

import com.wish.agent.base.BaseAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Locale;

/**
 * Builds session memory for one prompt: original user request plus agent outcome.
 * Outcome is taken from final tool answers when possible, otherwise summarized with {@link ChatModel}.
 * <p>
 * Optimization: {@code promptMessages} should not contain ephemeral {@code nextStepPrompt} text
 * (see {@link com.wish.agent.base.ToolCallAgent#think}); otherwise summarize would leak internal
 * step guidance into long-lived session memory.
 */
@Slf4j
public class PromptRunSummarizer {

    private static final int MAX_TOOL_SNIPPET_CHARS = 800;
    private static final int MAX_RUN_RESULT_CHARS = 4_000;
    private static final int MAX_DIRECT_RESULT_CHARS = 16_000;

    private final ChatModel chatModel;
    private final String summarySystemPrompt;

    public PromptRunSummarizer(ChatModel chatModel, String summarySystemPrompt) {
        this.chatModel = chatModel;
        this.summarySystemPrompt =
                summarySystemPrompt == null || summarySystemPrompt.isBlank()
                        ? com.wish.agent.base.ToolCallAgent.DEFAULT_SESSION_SUMMARY_SYSTEM_PROMPT
                        : summarySystemPrompt;
    }

    /**
     * @param promptMessages persisted context messages for this run only (no ephemeral next-step guidance)
     */
    public PromptRunSummary summarize(String userRequest, List<Message> promptMessages, String runResult) {
        String request = userRequest == null ? "" : userRequest.trim();
        if (request.isEmpty()) {
            return new PromptRunSummary("", "");
        }

        String direct = extractDirectResult(promptMessages, runResult);
        if (direct != null && !direct.isBlank()) {
            return new PromptRunSummary(request, truncate(direct, MAX_DIRECT_RESULT_CHARS));
        }

        if (chatModel != null) {
            try {
                String summarized = summarizeWithModel(request, promptMessages, runResult);
                if (summarized != null && !summarized.isBlank()) {
                    return new PromptRunSummary(request, summarized);
                }
            } catch (Exception e) {
                log.warn("LLM prompt summarization failed, using fallback: {}", e.getMessage());
            }
        }

        String fallback = fallbackResult(promptMessages, runResult);
        return new PromptRunSummary(request, fallback);
    }

    private String extractDirectResult(List<Message> promptMessages, String runResult) {
        // Optimization: prefer deterministic extraction to avoid unnecessary summarizer LLM calls.
        String completion = findLastToolResponse(promptMessages, BaseAgent.CREATE_CHAT_COMPLETION_TOOL_NAME);
        if (completion != null && !completion.isBlank()) {
            return completion;
        }
        String assistant = findLastAssistantText(promptMessages);
        if (assistant != null && !assistant.isBlank()) {
            return assistant;
        }
        if (runResult != null && !runResult.isBlank() && runResult.length() <= MAX_RUN_RESULT_CHARS) {
            return runResult.trim();
        }
        return null;
    }

    private String summarizeWithModel(String userRequest, List<Message> promptMessages, String runResult) {
        String trace = formatTrace(promptMessages, runResult);
        String userContent =
                """
                User request:
                %s

                Agent trace:
                %s
                """
                        .formatted(userRequest, trace);

        ChatResponse response = chatModel.call(
                new Prompt(List.of(new SystemMessage(summarySystemPrompt), new UserMessage(userContent))));
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? null : text.trim();
    }

    private static String fallbackResult(List<Message> promptMessages, String runResult) {
        if (runResult != null && !runResult.isBlank()) {
            return truncate(runResult.trim(), MAX_RUN_RESULT_CHARS);
        }
        String assistant = findLastAssistantText(promptMessages);
        if (assistant != null && !assistant.isBlank()) {
            return assistant;
        }
        return "Task ended without a captured result.";
    }

    private static String formatTrace(List<Message> messages, String runResult) {
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (message instanceof UserMessage user) {
                appendLine(builder, "User", user.getText());
            } else if (message instanceof AssistantMessage assistant) {
                appendLine(builder, "Assistant", assistant.getText());
            } else if (message instanceof ToolResponseMessage toolMessage) {
                for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                    String name = response.name() == null ? "tool" : response.name();
                    if (BaseAgent.TERMINATE_TOOL_NAME.equals(name)) {
                        continue;
                    }
                    appendLine(builder, "Tool " + name, stringify(response.responseData()));
                }
            }
        }
        if (runResult != null && !runResult.isBlank()) {
            appendLine(builder, "Run steps", truncate(runResult.trim(), MAX_RUN_RESULT_CHARS));
        }
        return builder.isEmpty() ? "(empty trace)" : builder.toString();
    }

    private static void appendLine(StringBuilder builder, String role, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(role).append(": ").append(truncate(text.trim(), MAX_TOOL_SNIPPET_CHARS));
    }

    private static String findLastToolResponse(List<Message> messages, String toolName) {
        String normalized = toolName.toLowerCase(Locale.ROOT);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                if (response.name() != null
                        && normalized.equals(response.name().toLowerCase(Locale.ROOT))) {
                    String text = stringify(response.responseData());
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private static String findLastAssistantText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistant) {
                String text = assistant.getText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String stringify(Object data) {
        return data == null ? null : data.toString();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }
}
