package com.wish.agent;

import com.wish.llm.LLMChatClient;
import com.wish.models.AgentState;
import com.wish.tools.ChatLifecycleTool;
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
    private static final String SYSTEM_PROMPT = "You are an agent that can execute tool calls";
    private static final String NEXT_STEP_PROMPT =
            "If you want to stop interaction, use `terminate` tool/function call.";

    private static final List<Object> DEFAULT_TOOLS = List.of(new ChatLifecycleTool());
    private final List<Object> extraTools = new ArrayList<>();

    private final ToolCallingManager toolCallingManager;

    private ChatResponse currentChatResponse;
    private Prompt currentChatPrompt;
    private List<AssistantMessage.ToolCall> currentToolCalls = List.of();

    public ToolCallAgent(LLMChatClient llmChatClient, int maxSteps) {
        this(llmChatClient, maxSteps, List.of());
    }

    public ToolCallAgent(LLMChatClient llmChatClient, int maxSteps, List<Object> extraTools) {
        super(NAME, DESCRIPTION, SYSTEM_PROMPT, NEXT_STEP_PROMPT, llmChatClient, maxSteps);
        if (extraTools != null && !extraTools.isEmpty()) {
            this.extraTools.addAll(extraTools);
        }
        chatClient.addTools(DEFAULT_TOOLS);
        if (!this.extraTools.isEmpty()) {
            chatClient.addTools(List.copyOf(this.extraTools));
        }
        toolCallingManager = ToolCallingManager.builder().build();
    }

    @Override
    public boolean think(String conversation) {
        // OpenManus: every think() prepends next_step_prompt as a user message before ask_tool
        List<String> followUp = (nextStepPrompt != null && !nextStepPrompt.isBlank())
                ? List.of(nextStepPrompt)
                : Collections.emptyList();

        Pair<ChatResponse, Prompt> response = chatClient.askWithTools(conversation, followUp, Collections.emptyList());
        AssistantMessage assistantMessage = response.getKey().getResult().getOutput();
        String content = assistantMessage.getText();
        currentToolCalls = assistantMessage.getToolCalls() != null
                ? assistantMessage.getToolCalls()
                : List.of();

        currentChatResponse = response.getKey();
        currentChatPrompt = response.getValue();
        lastThinkResult = content != null ? content : "";

        log.info("{}'s thoughts: {}", name, lastThinkResult);
        log.info("{} selected {} tools to use", name, currentToolCalls.size());
        for (AssistantMessage.ToolCall toolCall : currentToolCalls) {
            log.info("Tool prepared: {} args={}", toolCall.name(), toolCall.arguments());
        }

        // OpenManus: always add assistant message to memory in think()
        chatClient.addMemory(assistantMessage, conversation);

        // tool_choice=AUTO: act when there are tool calls, or when there is text-only content
        if (currentToolCalls.isEmpty()) {
            return !lastThinkResult.isBlank();
        }
        return true;
    }

    @Override
    public String act(String conversation) {
        if (currentToolCalls.isEmpty()) {
            // OpenManus act(): return last assistant content when no tool calls
            return lastThinkResult.isBlank()
                    ? "No content or commands to execute"
                    : lastThinkResult;
        }

        ToolExecutionResult result = toolCallingManager.executeToolCalls(currentChatPrompt, currentChatResponse);
        List<Message> fullHistory = result.conversationHistory();
        chatClient.replaceMemory(fullHistory, conversation);
        return extractActResult(fullHistory);
    }

    private String extractActResult(List<Message> history) {
        StringBuilder output = new StringBuilder();

        for (Message message : history) {
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                String toolName = response.name();
                if (TERMINATE_TOOL_NAME.equals(toolName)) {
                    transitState(AgentState.FINISHED);
                }
                Object resultData = response.responseData();
                if (resultData != null) {
                    String text = resultData.toString();
                    if (!text.isBlank()) {
                        if (!output.isEmpty()) {
                            output.append("\n\n");
                        }
                        output.append(text);
                    }
                }
                log.info("Tool {} result: {}", toolName, resultData);
            }
        }

        if (!output.isEmpty()) {
            return output.toString();
        }
        if (agentState == AgentState.FINISHED) {
            return "Task completed.";
        }
        return lastThinkResult;
    }
}
