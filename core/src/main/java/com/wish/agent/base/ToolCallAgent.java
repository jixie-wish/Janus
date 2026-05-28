package com.wish.agent.base;

import com.wish.llm.LLMChatClient;
import com.wish.models.context.BaseUserContext;
import com.wish.models.context.ToolCallUserContext;
import org.springframework.ai.chat.memory.ChatMemory;
import com.wish.models.AgentState;
import com.wish.tools.CreateChatCompletionTool;
import com.wish.tools.TerminateTool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class ToolCallAgent extends ReactAgent {

    private static final String NAME = "toolcall";
    private static final String DESCRIPTION = "an agent that can execute tool calls";
    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant. Use the provided tools when they apply. "
                    + "Match the user's language. Do not repeat yourself across turns.";
    private static final String NEXT_STEP_PROMPT = """
            Decide the single best action for this turn:
            - If the user request is already fully answered via create_chat_completion in the conversation, \
            call terminate(status="success") only. Do not output assistant text.
            - Otherwise, put the complete answer in create_chat_completion(response=...) this turn. \
            Do not put the answer only in assistant message text. Call terminate in a later turn, not now.
            - Do not send a follow-up summary as assistant text after answering.
            """;

    private static final String CREATE_CHAT_COMPLETION_REMINDER = """
            You already drafted an answer in a previous step. Do not output assistant text again.
            Call create_chat_completion(response=...) with the full user-visible answer this turn, \
            then call terminate in the next turn.
            """;

    /** Default session-memory summarizer instructions when a subclass does not override. */
    public static final String DEFAULT_SESSION_SUMMARY_SYSTEM_PROMPT = """
            You summarize one completed agent task for long-term session memory.
            Output 2-5 concise sentences: what was requested, what was done, and the outcome.
            Use the same language as the user's request. Do not include raw logs or stack traces.
            """;

    private static final List<Object> DEFAULT_BUILTIN_TOOLS =
            List.of(new CreateChatCompletionTool(), new TerminateTool());

    private final List<Object> mcpTools = new java.util.ArrayList<>();
    private final ToolCallingManager toolCallingManager;
    /** Optimization: if true, enforce "answer via create_chat_completion then terminate" to reduce drift. */
    private final boolean createChatCompletionContract;

    private static final String SESSION_SUMMARY_SYSTEM_PROMPT = """
            You summarize one completed chat turn for long-term session memory.
            Use the same language as the user's request. Structure the summary clearly:
            - User question or intent
            - Final answer delivered to the user
            - Any follow-up the user may care about
            Keep it concise. Do not include raw logs or stack traces.
            """;

    private final String sessionSummarySystemPrompt;

    public ToolCallAgent(LLMChatClient llmChatClient, int maxSteps) {
        this(llmChatClient, maxSteps, List.of());
    }

    public ToolCallAgent(LLMChatClient llmChatClient, int maxSteps, List<Object> mcpTools) {
        this(
                NAME,
                DESCRIPTION,
                SYSTEM_PROMPT,
                NEXT_STEP_PROMPT,
                SESSION_SUMMARY_SYSTEM_PROMPT,
                llmChatClient,
                maxSteps,
                mcpTools,
                DEFAULT_BUILTIN_TOOLS);
    }

    protected ToolCallAgent(
            String name,
            String description,
            String systemPrompt,
            String nextStepPrompt,
            String sessionSummarySystemPrompt,
            LLMChatClient llmChatClient,
            int maxSteps,
            List<Object> mcpTools,
            List<Object> builtinTools) {
        super(name, description, systemPrompt, nextStepPrompt, llmChatClient, maxSteps);
        this.sessionSummarySystemPrompt =
                sessionSummarySystemPrompt == null || sessionSummarySystemPrompt.isBlank()
                        ? DEFAULT_SESSION_SUMMARY_SYSTEM_PROMPT
                        : sessionSummarySystemPrompt;
        if (mcpTools != null && !mcpTools.isEmpty()) {
            this.mcpTools.addAll(mcpTools);
        }
        chatClient.addTools(builtinTools);
        if (!this.mcpTools.isEmpty()) {
            chatClient.addTools(List.copyOf(this.mcpTools));
        }
        toolCallingManager = ToolCallingManager.builder().build();
        this.createChatCompletionContract = hasCreateChatCompletionTool(builtinTools);
    }

    /** Instructions for {@link com.wish.models.session.PromptRunSummarizer} when persisting this agent's prompt to session memory. */
    public String sessionSummarySystemPrompt() {
        return sessionSummarySystemPrompt;
    }

    public boolean think(ToolCallUserContext context) {
        List<String> followUp = buildEphemeralFollowUp(context);

        // Optimization: nextStepPrompt / reminders are ephemeral (persistNewMessages=false) so they are not
        // summarized into session memory and are not mistaken for user messages on the next -c request.
        Pair<ChatResponse, Prompt> response =
                chatClient.askWithTools(context, followUp, Collections.emptyList(), false);
        AssistantMessage assistantMessage = response.getKey().getResult().getOutput();
        String content = assistantMessage.getText();
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls() != null
                ? assistantMessage.getToolCalls()
                : List.of();

        context.setCurrentChatResponse(response.getLeft());
        context.setCurrentChatPrompt(response.getRight());
        context.setCurrentToolCalls(toolCalls);
        context.setLastThinkResult(content != null ? content : "");

        log.info("{}'s thoughts: {}", name, context.getLastThinkResult());
        log.info("{} selected {} tools to use", name, toolCalls.size());
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            log.info("Tool prepared: {} args={}", toolCall.name(), toolCall.arguments());
        }

        if (toolCalls.isEmpty()) {
            if (createChatCompletionContract && !context.getLastThinkResult().isBlank()) {
                // Optimization: do not persist assistant-only draft answers; force the tool contract
                // so session summarize can reliably extract the final answer from tool traces.
                context.incrementAssistantOnlyStepCount();
                log.warn(
                        "{} assistant-only reply (not stored); require create_chat_completion (count={})",
                        name,
                        context.getAssistantOnlyStepCount());
                return true;
            }
            context.addMemory(assistantMessage);
            return !context.getLastThinkResult().isBlank();
        }

        context.resetAssistantOnlyStepCount();
        context.addMemory(assistantMessage);
        return true;
    }

    public String act(ToolCallUserContext context) {
        List<AssistantMessage.ToolCall> toolCalls = context.getCurrentToolCalls();
        if (toolCalls.isEmpty()) {
            if (createChatCompletionContract && !context.getLastThinkResult().isBlank()) {
                return "Answer must be delivered via create_chat_completion, not assistant text.";
            }
            return context.getLastThinkResult().isBlank()
                    ? "No content or commands to execute"
                    : context.getLastThinkResult();
        }

        ToolExecutionResult result =
                toolCallingManager.executeToolCalls(context.getCurrentChatPrompt(), context.getCurrentChatResponse());
        List<Message> fullHistory = result.conversationHistory();
        context.replaceMemory(fullHistory);
        String output = extractActResult(context, fullHistory, toolCalls);
        context.clearStepState();
        return output;
    }

    @Override
    public BaseUserContext createUserContext(String conversationId, ChatMemory chatMemory) {
        return new ToolCallUserContext(conversationId, chatMemory);
    }

    @Override
    public boolean think(BaseUserContext userContext) {
        return think(asToolCallContext(userContext));
    }

    @Override
    public String act(BaseUserContext userContext) {
        return act(asToolCallContext(userContext));
    }

    protected boolean usesCreateChatCompletionContract() {
        return createChatCompletionContract;
    }

    private List<String> buildEphemeralFollowUp(ToolCallUserContext context) {
        List<String> followUp = new ArrayList<>();
        if (nextStepPrompt != null && !nextStepPrompt.isBlank()) {
            followUp.add(nextStepPrompt);
        }
        if (createChatCompletionContract && context.getAssistantOnlyStepCount() > 0) {
            // Optimization: adaptive reminder nudges the model back to tool-based finalization
            // after assistant-only drift, without polluting persisted memory.
            followUp.add(CREATE_CHAT_COMPLETION_REMINDER);
        }
        return followUp;
    }

    private static boolean hasCreateChatCompletionTool(List<Object> builtinTools) {
        if (builtinTools == null) {
            return false;
        }
        for (Object tool : builtinTools) {
            if (tool instanceof CreateChatCompletionTool) {
                return true;
            }
        }
        return false;
    }

    private ToolCallUserContext asToolCallContext(BaseUserContext userContext) {
        if (userContext instanceof ToolCallUserContext toolCallContext) {
            return toolCallContext;
        }
        throw new IllegalArgumentException(
                "Agent %s requires ToolCallUserContext but got %s"
                        .formatted(name, userContext.getClass().getName()));
    }

    private String extractActResult(
            ToolCallUserContext context, List<Message> history, List<AssistantMessage.ToolCall> stepToolCalls) {
        StringBuilder output = new StringBuilder();

        for (AssistantMessage.ToolCall toolCall : stepToolCalls) {
            ToolResponseMessage.ToolResponse response = findToolResponse(history, toolCall);
            if (response == null) {
                log.warn("No tool response for {} (id={})", toolCall.name(), toolCall.id());
                continue;
            }

            String toolName = response.name();
            if (TERMINATE_TOOL_NAME.equals(toolName)) {
                transitState(AgentState.FINISHED);
            }

            Object resultData = response.responseData();
            log.info("Tool {} result: {}", toolName, resultData);
            if (resultData == null) {
                continue;
            }
            String text = resultData.toString();
            if (text.isBlank()) {
                continue;
            }
            if (!output.isEmpty()) {
                output.append("\n\n");
            }
            output.append(text);
        }

        if (!output.isEmpty()) {
            return output.toString();
        }
        if (agentState == AgentState.FINISHED) {
            return "Task completed.";
        }
        return context.getLastThinkResult();
    }

    private static ToolResponseMessage.ToolResponse findToolResponse(
            List<Message> history, AssistantMessage.ToolCall toolCall) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        String callId = toolCall.id();
        String callName = toolCall.name();

        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                if (callId != null && !callId.isBlank() && callId.equals(response.id())) {
                    return response;
                }
            }
        }

        if (callName == null || callName.isBlank()) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                if (callName.equals(response.name())) {
                    return response;
                }
            }
        }
        return null;
    }
}
