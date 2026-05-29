package com.wish.flow;

import com.wish.agent.base.BaseAgent;
import com.wish.llm.LLMChatClient;
import com.wish.tools.plan.PlanTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class PlanningFlowPlanToolTest {

    @Test
    void reusesPlanToolAlreadyOnSharedClient() {
        ChatModel chatModel = mock(ChatModel.class);
        PlanTool shared = new PlanTool();
        LLMChatClient client = new LLMChatClient(chatModel, List.of(shared));
        BaseAgent agent = mock(BaseAgent.class);

        PlanningFlow flow = new PlanningFlow(client, Map.of("janus", agent), "janus");

        assertSame(shared, flow.getPlanTool());
        assertEquals(0, client.getExtraTools().size());
    }
}
