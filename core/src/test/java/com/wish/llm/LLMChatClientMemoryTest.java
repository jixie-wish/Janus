package com.wish.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class LLMChatClientMemoryTest {

    @Test
    void addMemoryAppendsSingleMessageWithoutDuplicatingHistory() {
        LLMChatClient client = new LLMChatClient(mock(ChatModel.class), List.of());
        String conversation = "conv-1";

        client.addUserMemory("hello", conversation);
        client.addMemory(new AssistantMessage("hi"), conversation);

        assertEquals(2, client.getChatMemory().get(conversation).size());

        client.addMemory(new UserMessage("again"), conversation);
        assertEquals(3, client.getChatMemory().get(conversation).size());
    }

    @Test
    void replaceMemoryOverwritesConversation() {
        LLMChatClient client = new LLMChatClient(mock(ChatModel.class), List.of());
        String conversation = "conv-2";

        client.addUserMemory("old", conversation);
        client.replaceMemory(List.of(new UserMessage("new"), new AssistantMessage("reply")), conversation);

        assertEquals(2, client.getChatMemory().get(conversation).size());
        assertEquals("new", client.getChatMemory().get(conversation).getFirst().getText());
    }
}
