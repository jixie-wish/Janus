package com.wish.agent;

import com.wish.llm.LLMChatClient;
import com.wish.tools.ChatLifecycleTool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ToolCallAgent extends ReactAgent{
    private final static String name = "toolcall";
    private final static String description = "an agent that can execute tool calls";
    private final static String systemPrompt = "You are an agent that can execute tool calls";
    private final static String nextStepPrompt = "If you want to stop interation, use `terminate` tool/function call.";
    private final ToolCallingManager toolCallingManager;

    private ChatResponse currentChatResponse;
    private Prompt currentChatPrompt;

    private final static List<Object> defaultTools = new ArrayList<>();
    static {
        defaultTools.add(new ChatLifecycleTool());
    }
    public ToolCallAgent(LLMChatClient llmChatClient, int maxSteps) {
        super(name, description, systemPrompt, nextStepPrompt, llmChatClient, maxSteps);
        this.chatClient.addTools(defaultTools);
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    @Override
    public boolean think(String conversation) {
        List<String> user = new ArrayList<>();
        user.add(nextStepPrompt);

        Pair<ChatResponse, Prompt> response = this.chatClient.askWithTools(conversation, user, new ArrayList<>());
        AssistantMessage assistantMessage = response.getKey().getResult().getOutput();
        String content = assistantMessage.getText();
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        this.currentChatResponse = response.getKey();
        this.currentChatPrompt = response.getValue();
        log.info("{}'s thoughts: {}", name, content);
        log.info("{} selected {} tools to use", name, toolCalls.size());
        for(AssistantMessage.ToolCall toolCall: toolCalls) {
            log.info("Tools being prepared {}", toolCall.name());
            log.info("Tool arguments {}", toolCall.arguments());
        }
        this.chatClient.addMemory(assistantMessage,conversation);
        return !toolCalls.isEmpty();
    }

    @Override
    public String act(String conversation) {
        ToolExecutionResult result = toolCallingManager.executeToolCalls(
                currentChatPrompt,
                currentChatResponse
        );
        List<Message> fullHistory = result.conversationHistory();
        this.chatClient.addMemory(fullHistory, conversation);
        int toolCount = this.currentChatResponse.getResult().getOutput().getToolCalls().size();
        for (int i = fullHistory.size() - 1; i >= 0; i--) {
            Message msg = fullHistory.get(i);
            if (msg instanceof ToolResponseMessage) {
                ToolResponseMessage toolMsg = (ToolResponseMessage) msg;
                for (ToolResponseMessage.ToolResponse resp : toolMsg.getResponses()) {
                    String toolId = resp.id();
                    Object resultData = resp.responseData();
                    log.info("Tool {} completed its mission! Result: {}", toolId, resultData);
                }
                toolCount -= 1;
                if(toolCount <= 0){
                    break;
                }
            }
        }
        return "";
    }
}
