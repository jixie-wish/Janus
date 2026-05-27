package com.wish.llm;

import com.wish.models.context.BaseUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LLMChatClientMemoryTest {

    @Test
    void addMemoryAppendsSingleMessageWithoutDuplicatingHistory() {
        var chatMemory = MessageWindowChatMemory.builder().build();
        BaseUserContext context = new BaseUserContext("conv-1", chatMemory);

        context.addUserMemory("hello");
        context.addMemory(new AssistantMessage("hi"));

        assertEquals(2, context.getAllMessages().size());

        context.addMemory(new UserMessage("again"));
        assertEquals(3, context.getAllMessages().size());
    }

    @Test
    void replaceMemoryOverwritesConversation() {
        var chatMemory = MessageWindowChatMemory.builder().build();
        BaseUserContext context = new BaseUserContext("conv-2", chatMemory);

        context.addUserMemory("old");
        context.replaceMemory(List.of(new UserMessage("new"), new AssistantMessage("reply")));

        assertEquals(2, context.getAllMessages().size());
        assertEquals("new", context.getAllMessages().get(0).getText());
    }
}
