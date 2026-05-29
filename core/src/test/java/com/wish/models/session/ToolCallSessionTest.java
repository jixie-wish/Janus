package com.wish.models.session;

import com.wish.models.context.ToolCallUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallSessionTest {

    @Test
    void persistentSession_storesRequestAndResultPairPerPrompt() {
        var chatMemory = MessageWindowChatMemory.builder().build();
        ToolCallSession session = new ToolCallSession("demo", chatMemory, "test summary prompt");

        ToolCallUserContext first = session.beginPrompt("first task");
        first.addUserMemory("first task");
        first.addMemory(new AssistantMessage("done one"));
        session.endPrompt("Step 1: done one");

        assertTrue(first.getAllMessages().isEmpty());

        ToolCallUserContext second = session.beginPrompt("second task");
        assertEquals("demo", second.getSessionId());
        assertNotEquals(first.getConversation(), second.getConversation());
        assertEquals(3, second.getAllMessages().size());
        assertEquals("first task", second.getAllMessages().get(0).getText());
        assertEquals("done one", second.getAllMessages().get(1).getText());
        assertInstanceOf(SystemMessage.class, second.getAllMessages().get(2));
        assertTrue(second.getAllMessages().get(2).getText().contains("background"));
        assertEquals(2, second.getSessionHydrationMessageCount());

        second.addUserMemory("second task");
        second.addMemory(new AssistantMessage("done two"));
        session.endPrompt("Step 1: done two");

        var sessionMessages = chatMemory.get(session.getSessionMemoryKey());
        assertEquals(4, sessionMessages.size());
        assertInstanceOf(UserMessage.class, sessionMessages.get(0));
        assertInstanceOf(AssistantMessage.class, sessionMessages.get(1));
        assertInstanceOf(UserMessage.class, sessionMessages.get(2));
        assertInstanceOf(AssistantMessage.class, sessionMessages.get(3));
        assertEquals("second task", sessionMessages.get(2).getText());
        assertEquals("done two", sessionMessages.get(3).getText());
    }

    @Test
    void clear_removesSessionAndContextPartitions() {
        var chatMemory = MessageWindowChatMemory.builder().build();
        ToolCallSession session = new ToolCallSession("x", chatMemory, "test summary prompt");

        ToolCallUserContext context = session.beginPrompt("hello");
        String contextKey = context.getConversation();
        context.addUserMemory("hello");
        context.addMemory(new AssistantMessage("hi"));
        session.endPrompt("Step 1: hi");

        session.clear();

        assertTrue(chatMemory.get(session.getSessionMemoryKey()).isEmpty());
        assertTrue(chatMemory.get(contextKey).isEmpty());
    }

    @Test
    void initializeContext_copiesPriorSessionSummaryPairs() {
        var chatMemory = MessageWindowChatMemory.builder().build();
        chatMemory.add("session:demo", new UserMessage("prior request"));
        chatMemory.add("session:demo", new AssistantMessage("prior result"));

        ToolCallSession session = new ToolCallSession("demo", chatMemory, "test summary prompt");
        ToolCallUserContext context = session.beginPrompt("new");
        assertEquals(3, context.getAllMessages().size());
        assertEquals("prior request", context.getAllMessages().get(0).getText());
        assertInstanceOf(SystemMessage.class, context.getAllMessages().get(2));
        assertEquals(2, context.getSessionHydrationMessageCount());
        session.endPrompt();
    }

    @Test
    void endPrompt_passesRunResultToSummary() {
        var chatMemory = MessageWindowChatMemory.builder().build();
        ToolCallSession session = new ToolCallSession("demo", chatMemory, "test summary prompt");

        ToolCallUserContext context = session.beginPrompt("analyze data");
        session.endPrompt("Step 1: wrote report\nStep 2: terminate");

        Message last = chatMemory.get(session.getSessionMemoryKey()).get(1);
        assertInstanceOf(AssistantMessage.class, last);
        assertTrue(last.getText().contains("wrote report"));
    }
}
