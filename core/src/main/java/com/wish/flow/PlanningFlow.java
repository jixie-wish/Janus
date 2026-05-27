package com.wish.flow;

import com.wish.agent.BaseAgent;
import com.wish.models.AgentState;
import com.wish.models.context.BaseFlowUserContext;
import com.wish.models.context.BaseUserContext;
import com.wish.models.context.PlanningFlowUserContext;
import com.wish.llm.LLMChatClient;
import com.wish.tools.plan.Plan;
import com.wish.tools.plan.PlanTool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.*;

/**
 * Planning and step execution flow aligned with OpenManus {@code app.flow.planning.PlanningFlow}.
 */
@Slf4j
public class PlanningFlow extends BaseFlow {

    private static final String INITIAL_PLAN_SYSTEM_PROMPT = """
            You are a planning assistant. Create a concise, actionable plan with clear steps.
            Focus on key milestones rather than detailed sub-steps.
            Optimize for clarity and efficiency.
            """;

    private static final String INITIAL_AGENT_DESC_PROMPT_FORMAT = """
            
            Now we have %d agents.
            The information of them are below: %s
            When creating steps in the planning tool, please specify the agent names using the format '[agent_name]'.
            """;

    private static final String INITIAL_REQUEST_PROMPT_FORMAT = """
            Create a reasonable plan with clear steps to accomplish the task: %s
            Use plan_id '%s' when calling the planning tool.
            """;

    private static final String FINALIZE_PLAN_SYSTEM_PROMPT = """
            You are a planning assistant. Your task is to summarize the completed plan.
            """;

    private static final String FINALIZE_PLAN_USER_PROMPT_FORMAT = """
            The plan has been completed. Here is the final plan status:
            
            %s
            
            Please provide a summary of what was accomplished and any final thoughts.
            """;

    private static final String STEP_EXECUTION_PROMPT_FORMAT = """
            CURRENT PLAN STATUS:
            %s
            
            YOUR CURRENT TASK:
            You are now working on step %d: "%s"
            
            Please only execute this current step using the appropriate tools. When you're done, provide a summary of what you accomplished.
            """;

    private final PlanTool planTool = new PlanTool();
    private final ToolCallingManager toolCallingManager;

    public PlanningFlow(LLMChatClient llmChatClient, Map<String, BaseAgent> agents, String primaryAgentName) {
        super(llmChatClient, agents, primaryAgentName);
        llmChatClient.addTools(List.of(planTool));
        toolCallingManager = ToolCallingManager.builder().build();
    }

    public PlanningFlow(
            LLMChatClient llmChatClient,
            Map<String, BaseAgent> agents,
            String primaryAgentName,
            List<String> executorKeys) {
        super(llmChatClient, agents, primaryAgentName, executorKeys);
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
    public String execute(BaseFlowUserContext context, String input) {
        return execute(requirePlanningFlowUserContext(context), input);
    }

    public String execute(PlanningFlowUserContext context, String input) {
        try {
            if (getPrimaryAgent().isEmpty()) {
                throw new IllegalStateException("No primary agent available");
            }

            if (!context.hasExecutorContexts()) {
                context.setupExecutors(new LinkedHashSet<>(getAgents().values()));
            }

            if (input != null && !input.isBlank()) {
                createInitialPlan(context, input);
                if (!planTool.hasPlan(context.getPlanId())) {
                    log.error("Plan creation failed. Plan ID {} not found.", context.getPlanId());
                    return "Failed to create plan for: " + input;
                }
                context.initializePlan();
            }

            StringBuilder result = new StringBuilder();
            while (true) {
                Pair<Integer, Map<String, String>> stepInfo = getCurrentStepInfo(context.getPlanId());
                int stepIndex = stepInfo.getLeft();
                if (stepIndex < 0) {
                    result.append(finalizePlan(context));
                    break;
                }

                Map<String, String> info = stepInfo.getRight();
                String stepType = info.get("type");
                BaseAgent executor = getExecutor(stepType);
                BaseUserContext executorContext = requireExecutorContext(context, executor);

                String stepResult = executeStep(context, executor, executorContext, stepIndex, info);
                result.append(stepResult).append('\n');

                if (executor.getAgentState() == AgentState.FINISHED) {
                    log.info("Executor {} finished early; stopping plan loop", executor.getName());
                    break;
                }
            }

            return result.toString().strip();
        } catch (Exception e) {
            log.error("Error in PlanningFlow", e);
            return "Execution failed: " + e.getMessage();
        }
    }

    private Pair<Integer, Map<String, String>> getCurrentStepInfo(String planId) {
        if (!planTool.hasPlan(planId)) {
            log.error("Plan with ID {} not found", planId);
            return Pair.of(-1, Map.of());
        }
        return planTool.getPlan(planId).getCurrentStepInfo();
    }

    private String executeStep(
            PlanningFlowUserContext flowContext,
            BaseAgent executor,
            BaseUserContext executorContext,
            int stepIndex,
            Map<String, String> stepInfo) {
        String planId = flowContext.getPlanId();
        try {
            String planStatus = planTool.getPlan(planId).format();
            String stepText = stepInfo.getOrDefault("text", "Step " + stepIndex);
            String stepPrompt = STEP_EXECUTION_PROMPT_FORMAT.formatted(planStatus, stepIndex, stepText);

            String stepResult = executor.run(executorContext, stepPrompt);

            planTool.markStep(planId, stepIndex, Plan.StepStatus.COMPLETED);
            log.info("Marked step {} as completed in plan {}", stepIndex, planId);
            return stepResult;
        } catch (Exception e) {
            log.error("Error executing step {} for plan {}", stepIndex, planId, e);
            return "Error executing step %d: %s".formatted(stepIndex, e.getMessage());
        }
    }

    private void createInitialPlan(PlanningFlowUserContext context, String request) {
        String planId = context.getPlanId();
        log.info("Creating initial plan with ID: {}", planId);

        String systemPrompt = buildPlanningSystemPrompt();
        BaseUserContext planningContext = context.getInitializeContext();
        planningContext.addMemory(new SystemMessage(systemPrompt));
        planningContext.addMemory(new UserMessage(INITIAL_REQUEST_PROMPT_FORMAT.formatted(request, planId)));

        Pair<ChatResponse, Prompt> response = llmChatClient.askWithTools(planningContext, List.of(), List.of());
        AssistantMessage assistantMessage = response.getKey().getResult().getOutput();
        planningContext.addMemory(assistantMessage);

        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls() != null
                ? assistantMessage.getToolCalls()
                : List.of();

        if (!toolCalls.isEmpty()) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(response.getRight(), response.getLeft());
            planningContext.replaceMemory(result.conversationHistory());
            if (planTool.hasPlan(planId)) {
                log.info("Plan {} created via planning tool", planId);
                return;
            }
        }

        createDefaultPlan(planId, request);
    }

    private void createDefaultPlan(String planId, String request) {
        log.warn("Creating default plan for {}", planId);
        String title = "Plan for: " + request;
        if (title.length() > 53) {
            title = title.substring(0, 50) + "...";
        }
        planTool.createPlan(
                planId,
                title,
                List.of("Analyze request", "Execute task", "Verify results"));
    }

    private String buildPlanningSystemPrompt() {
        StringBuilder systemPrompt = new StringBuilder(INITIAL_PLAN_SYSTEM_PROMPT);
        List<Map<String, String>> agentDescs = new ArrayList<>();
        for (String key : getExecutorKeys()) {
            BaseAgent agent = getAgents().get(key);
            if (agent == null) {
                continue;
            }
            Map<String, String> desc = new HashMap<>();
            desc.put("name", key.toUpperCase(Locale.ROOT));
            desc.put("description", agent.getDescription());
            agentDescs.add(desc);
        }
        if (agentDescs.size() > 1) {
            systemPrompt.append(INITIAL_AGENT_DESC_PROMPT_FORMAT.formatted(agentDescs.size(), agentDescs));
        }
        return systemPrompt.toString();
    }

    private String finalizePlan(PlanningFlowUserContext context) {
        String planId = context.getPlanId();
        String planText = planTool.hasPlan(planId) ? planTool.getPlan(planId).format() : "Plan " + planId + " not found.";

        try {
            BaseUserContext planningContext = context.getFinalizeContext();
            planningContext.addMemory(new SystemMessage(FINALIZE_PLAN_SYSTEM_PROMPT));
            planningContext.addMemory(new UserMessage(FINALIZE_PLAN_USER_PROMPT_FORMAT.formatted(planText)));
            Pair<ChatResponse, Prompt> response = llmChatClient.askWithTools(planningContext, List.of(), List.of());
            String summary = response.getKey().getResult().getOutput().getText();
            context.finalizePlan();
            return "Plan completed:\n\n" + summary;
        } catch (Exception e) {
            log.error("Error finalizing plan with LLM, falling back to primary agent", e);
            return finalizePlanWithAgent(context, planText);
        }
    }

    private String finalizePlanWithAgent(PlanningFlowUserContext context, String planText) {
        try {
            BaseAgent agent = getPrimaryAgent().orElseThrow();
            BaseUserContext agentContext = requireExecutorContext(context, agent);
            String summaryPrompt = FINALIZE_PLAN_USER_PROMPT_FORMAT.formatted(planText);
            String summary = agent.run(agentContext, summaryPrompt);
            context.finalizePlan();
            return "Plan completed:\n\n" + summary;
        } catch (Exception e) {
            log.error("Error finalizing plan with agent", e);
            context.finalizePlan();
            return "Plan completed. Error generating summary.";
        }
    }

    private static BaseUserContext requireExecutorContext(PlanningFlowUserContext context, BaseAgent executor) {
        BaseUserContext executorContext = context.getExecutorContext(executor);
        if (executorContext == null) {
            throw new IllegalStateException("No executor context for agent: " + executor.getName());
        }
        return executorContext;
    }



    private static PlanningFlowUserContext requirePlanningFlowUserContext(BaseFlowUserContext baseFlowUserContext) {
        if (baseFlowUserContext instanceof PlanningFlowUserContext planningFlowUserContext) {
            return planningFlowUserContext;
        }
        throw new IllegalArgumentException(
                "Expected PlanningFlowUserContext but got " + baseFlowUserContext.getClass().getName());
    }

}
