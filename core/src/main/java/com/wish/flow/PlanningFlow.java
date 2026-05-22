package com.wish.flow;

import com.wish.agent.BaseAgent;
import com.wish.llm.LLMChatClient;
import com.wish.tools.plan.PlanTool;
import jdk.jfr.Frequency;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.*;

@Slf4j
public class PlanningFlow extends BaseFlow {

    private final String INITIAL_PLAN_SYSTEM_PROMPT = """
            You are a planning assistant. Create a concise, actionable plan with clear steps.
            Focus on key milestones rather than detailed sub-steps.
            Optimize for clarity and efficiency.
            """;
    private final String INITIAL_AGENT_DESC_PROMPT_FORMAT = """
            Now we have %d agents.
            The infomation of them are below: %s
            
            When creating steps in the planning tool, please specify the agent names using the format '[agent_name]'.
            """;
    private final String INTIAL_REQUEST_PROMPT_FORMAT = """
            Create a reasonable plan with clear steps to accomplish the task: %s
            Remember use %s as the plan_id
            """;

    private final String activePlanId = "plan_" + System.currentTimeMillis();
    private final PlanTool planTool = new PlanTool();
    private final ToolCallingManager toolCallingManager;

    public PlanningFlow(LLMChatClient llmChatClient, Map<String, BaseAgent> agents, String primaryAgentName) {
        super(llmChatClient, agents, primaryAgentName);
        llmChatClient.addTools(List.of(planTool));
        toolCallingManager = ToolCallingManager.builder().build();
    }

    public PlanningFlow(LLMChatClient llmChatClient, BaseAgent agent) {
        super(llmChatClient, agent);
        llmChatClient.addTools(List.of(planTool));
        toolCallingManager = ToolCallingManager.builder().build();
    }

    public PlanningFlow(LLMChatClient llmChatClient, List<BaseAgent> agents) {
        super(llmChatClient, agents);
        llmChatClient.addTools(List.of(planTool));
        toolCallingManager = ToolCallingManager.builder().build();
    }


    @Override
    public String execute(String conversation, String input) {
        Optional<BaseAgent> baseAgentOpt = getPrimaryAgent();
        if(baseAgentOpt.isEmpty()) {
            throw new RuntimeException("No primary agent available");
        }
        createInitialPlan(conversation, input);

        return "";
    }

    private boolean createInitialPlan(String conversation, String request) {
        log.info("Creating initial plan with ID: {}", activePlanId);
        List<Map<String, String>> agentDescs = new ArrayList<>();
        Map<String, BaseAgent> agents = getAgents();
        for(Map.Entry<String, BaseAgent> agentEntry: agents.entrySet()) {
            String key = agentEntry.getKey();
            BaseAgent agent = agentEntry.getValue();
            Map<String, String> desc = new HashMap<>();
            desc.put("name", key);
            desc.put("description", agent.getDescription());
            agentDescs.add(desc);
        }
        String systemPrompt = INITIAL_PLAN_SYSTEM_PROMPT;
        if(agentDescs.size() > 1) {
            String agentDesc = INITIAL_AGENT_DESC_PROMPT_FORMAT.formatted(agentDescs.size(), agentDescs);
            systemPrompt += "\n";
            systemPrompt += agentDesc;
        }
        SystemMessage systemMessage = new SystemMessage(systemPrompt);
        //OpenManus set active plan id as tool args, we choose to specify it in the promt as spring ai framework executes the tool automatically
        UserMessage userMessage = new UserMessage(INTIAL_REQUEST_PROMPT_FORMAT.formatted(request, activePlanId));
        llmChatClient.addMemory(systemMessage, conversation);
        llmChatClient.addMemory(userMessage, conversation);
        Pair<ChatResponse, Prompt> response = llmChatClient.askWithTools(conversation, List.of(), List.of());
        AssistantMessage assistantMessage = response.getKey().getResult().getOutput();
        llmChatClient.addMemory(assistantMessage, conversation);
        List<AssistantMessage.ToolCall> currentToolCalls = assistantMessage.getToolCalls() != null
                ? assistantMessage.getToolCalls()
                : List.of();
        if(!currentToolCalls.isEmpty()) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(response.getRight(), response.getLeft());
            List<Message> fullHistory = result.conversationHistory();
            llmChatClient.replaceMemory(fullHistory, conversation);
            return true;
        } else {
            planTool.createPlan(
                    activePlanId,
                    "Plan for " + request.substring(0, 50),
                    List.of("Analyze request", "Execute task", "Verify results")
            );
            return false;
        }

    }
}
