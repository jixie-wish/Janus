package com.wish.models.session;

import com.wish.models.context.PlanningFlowUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningFlowSessionTest {

    @Test
    void initializeContext_hydratesInitializePartitionAtBeginPrompt() {
        var chatMemory = MessageWindowChatMemory.builder().build();
        chatMemory.add("session:demo", new UserMessage("prior request"));
        chatMemory.add("session:demo", new AssistantMessage("prior result"));

        PlanningFlowSession session = new PlanningFlowSession("demo", chatMemory, null, "test summary prompt");
        PlanningFlowUserContext context = session.beginPrompt("new task");

        var planningMessages = context.getInitializeContext().getAllMessages();
        assertEquals(3, planningMessages.size());
        assertEquals("prior request", planningMessages.get(0).getText());
        assertEquals("prior result", planningMessages.get(1).getText());
        assertInstanceOf(SystemMessage.class, planningMessages.get(2));
        assertTrue(planningMessages.get(2).getText().contains("background"));
        assertEquals(2, context.getInitializeContext().getSessionHydrationMessageCount());

        session.endPrompt();
    }
}
