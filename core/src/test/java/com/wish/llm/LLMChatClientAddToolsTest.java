package com.wish.llm;

import com.wish.tools.TerminateTool;
import com.wish.tools.plan.PlanTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class LLMChatClientAddToolsTest {

    @Test
    void addTools_skipsDuplicateToolNames() {
        ChatModel chatModel = mock(ChatModel.class);
        LLMChatClient client = new LLMChatClient(chatModel, List.of(new PlanTool(), new TerminateTool()));

        client.addTools(List.of(new PlanTool()));

        assertEquals(2, client.getDefaultTools().size());
        assertEquals(0, client.getExtraTools().size());
    }

    @Test
    void resolveToolName_readsSpringToolAnnotation() {
        assertEquals("planning", LLMChatClient.resolveToolName(new PlanTool()));
        assertEquals("terminate", LLMChatClient.resolveToolName(new TerminateTool()));
    }
}
