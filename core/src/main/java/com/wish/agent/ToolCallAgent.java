package com.wish.agent;

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

    private static final List<Object> DEFAULT_BUILTIN_TOOLS =
            List.of(new CreateChatCompletionTool(), new TerminateTool());

    private final List<Object> mcpTools = new java.util.ArrayList<>();
    private final ToolCallingManager toolCallingManager;

    public ToolCallAgent(LLMChatClient llmChatClient, int maxSteps) {
        this(llmChatClient, maxSteps, List.of());
    }

    public ToolCallAgent(LLMChatClient llmChatClient, int maxSteps, List<Object> mcpTools) {
        this(NAME, DESCRIPTION, SYSTEM_PROMPT, NEXT_STEP_PROMPT, llmChatClient, maxSteps, mcpTools, DEFAULT_BUILTIN_TOOLS);
    }

    protected ToolCallAgent(
            String name,
            String description,
            String systemPrompt,
            String nextStepPrompt,
            LLMChatClient llmChatClient,
            int maxSteps,
            List<Object> mcpTools,
            List<Object> builtinTools) {
        super(name, description, systemPrompt, nextStepPrompt, llmChatClient, maxSteps);
        if (mcpTools != null && !mcpTools.isEmpty()) {
            this.mcpTools.addAll(mcpTools);
        }
        chatClient.addTools(builtinTools);
        if (!this.mcpTools.isEmpty()) {
            chatClient.addTools(List.copyOf(this.mcpTools));
        }
        toolCallingManager = ToolCallingManager.builder().build();
    }

    public boolean think(ToolCallUserContext context) {
        List<String> followUp = (nextStepPrompt != null && !nextStepPrompt.isBlank())
                ? List.of(nextStepPrompt)
                : Collections.emptyList();

        Pair<ChatResponse, Prompt> response = chatClient.askWithTools(context, followUp, Collections.emptyList());
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

        context.addMemory(assistantMessage);

        if (toolCalls.isEmpty()) {
            return !context.getLastThinkResult().isBlank();
        }
        return true;
    }

    public String act(ToolCallUserContext context) {
        List<AssistantMessage.ToolCall> toolCalls = context.getCurrentToolCalls();
        if (toolCalls.isEmpty()) {
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
