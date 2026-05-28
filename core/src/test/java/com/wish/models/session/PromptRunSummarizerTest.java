package com.wish.models.session;

import com.wish.agent.base.BaseAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptRunSummarizerTest {

    private final PromptRunSummarizer summarizer = new PromptRunSummarizer(null, null);

    @Test
    void usesLastCreateChatCompletionResponse() {
        var messages = new ArrayList<org.springframework.ai.chat.messages.Message>();
        messages.add(new UserMessage("hello"));
        messages.add(toolResponse(BaseAgent.CREATE_CHAT_COMPLETION_TOOL_NAME, "Final answer for user"));

        PromptRunSummary summary = summarizer.summarize("hello", messages, null);

        assertEquals("hello", summary.userRequest());
        assertEquals("Final answer for user", summary.agentResult());
    }

    @Test
    void fallsBackToRunResultWhenTraceIsEmpty() {
        PromptRunSummary summary = summarizer.summarize("task", List.of(), "Step 1: did work");

        assertEquals("task", summary.userRequest());
        assertTrue(summary.agentResult().contains("did work"));
    }

    @Test
    void prefersCreateChatCompletionOverLaterAssistantNoise() {
        var messages = new ArrayList<org.springframework.ai.chat.messages.Message>();
        messages.add(toolResponse(BaseAgent.CREATE_CHAT_COMPLETION_TOOL_NAME, "canonical answer"));
        messages.add(new AssistantMessage("incidental text"));

        PromptRunSummary summary = summarizer.summarize("q", messages, null);

        assertEquals("canonical answer", summary.agentResult());
    }

    private static ToolResponseMessage toolResponse(String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id", name, data)))
                .build();
    }
}
