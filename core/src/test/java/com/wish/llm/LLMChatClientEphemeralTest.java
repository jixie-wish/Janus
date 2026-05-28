package com.wish.llm;

import com.wish.models.context.BaseUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LLMChatClientEphemeralTest {

    @Test
    void ephemeralFollowUpIsNotStoredInMemory() {
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage assistant = new AssistantMessage("ok");
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistant)));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        var chatMemory = MessageWindowChatMemory.builder().build();
        BaseUserContext context = new BaseUserContext("conv-ephemeral", chatMemory);
        context.addUserMemory("hello");

        LLMChatClient client = new LLMChatClient(chatModel, List.of());
        client.askWithTools(context, List.of("internal next-step guidance"), List.of(), false);

        assertEquals(1, context.getAllMessages().size());
        assertEquals("hello", context.getAllMessages().get(0).getText());
    }
}
