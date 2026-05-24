package com.wish.tools.plan;

import org.apache.commons.lang3.tuple.Pair;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenManus {@code app.tool.planning.PlanningTool}: create and manage in-memory plans.
 */
public class PlanTool {

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private String currentPlanId;

    @Tool(
            name = "planning",
            description =
                    """
                    A planning tool that allows the agent to create and manage plans for solving complex tasks.
                    The tool provides functionality for creating plans, updating plan steps, and tracking progress.
                    """)
    public String plan(
            @ToolParam(
                            description =
                                    "The command to execute. Available commands: create, update, list, get, set_active, mark_step, delete.",
                            required = true)
                    String command,
            @ToolParam(
                            description =
                                    "Unique identifier for the plan. Required for create, update, set_active, and delete. Optional for get and mark_step (uses active plan if omitted).",
                            required = false)
                    String planId,
            @ToolParam(description = "Title for the plan. Required for create, optional for update.", required = false)
                    String title,
            @ToolParam(
                            description = "List of plan steps. Required for create, optional for update.",
                            required = false)
                    List<String> steps,
            @ToolParam(description = "Index of the step to update (0-based). Required for mark_step.", required = false)
                    Integer stepIndex,
            @ToolParam(
                            description = "Status for mark_step: not_started, in_progress, completed, blocked.",
                            required = false)
                    String stepStatus,
            @ToolParam(description = "Additional notes for a step (mark_step).", required = false)
                    String stepNotes) {
        return switch (command) {
            case "create" -> createPlan(planId, title, steps);
            case "update" -> updatePlan(planId, title, steps);
            case "list" -> listPlans();
            case "get" -> getPlan(planId);
            case "set_active" -> setActivePlan(planId);
            case "mark_step" -> markStep(planId, stepIndex, stepStatus, stepNotes);
            case "delete" -> deletePlan(planId);
            default ->
                    throw new PlanToolException(
                            "Unrecognized command: %s. Allowed: create, update, list, get, set_active, mark_step, delete"
                                    .formatted(command));
        };
    }

    public String createPlan(String planId, String title, List<String> steps) {
        if (planId == null || planId.isBlank()) {
            throw new PlanToolException("Parameter `plan_id` is required for command: create");
        }
        if (plans.containsKey(planId)) {
            throw new PlanToolException(
                    "A plan with ID '%s' already exists. Use 'update' to modify existing plans.".formatted(planId));
        }
        if (title == null || title.isBlank()) {
            throw new PlanToolException("Parameter `title` is required for command: create");
        }
        List<String> parsedSteps = Plan.parseSteps(steps);
        if (parsedSteps.isEmpty()) {
            throw new PlanToolException(
                    "Parameter `steps` must be a non-empty list of strings for command: create");
        }

        Plan plan = new Plan(planId, title, parsedSteps);
        plans.put(planId, plan);
        currentPlanId = planId;
        return "Plan created successfully with ID: " + planId + "\n\n" + plan.format();
    }

    private String updatePlan(String planId, String title, List<String> steps) {
        if (planId == null || planId.isBlank()) {
            throw new PlanToolException("Parameter `plan_id` is required for command: update");
        }
        Plan plan = requirePlan(planId);

        if (title != null && !title.isBlank()) {
            plan.setTitle(title);
        }
        if (steps != null && !steps.isEmpty()) {
            List<String> parsedSteps = Plan.parseSteps(steps);
            if (parsedSteps.isEmpty()) {
                throw new PlanToolException("Parameter `steps` must be a non-empty list of strings for command: update");
            }
            plan.updateSteps(parsedSteps);
        }

        return "Plan updated successfully: " + planId + "\n\n" + plan.format();
    }

    private String listPlans() {
        if (plans.isEmpty()) {
            return "No plans available. Create a plan with the 'create' command.";
        }
        StringBuilder output = new StringBuilder("Available plans:\n");
        for (Map.Entry<String, Plan> entry : plans.entrySet()) {
            String id = entry.getKey();
            Plan plan = entry.getValue();
            String active = id.equals(currentPlanId) ? " (active)" : "";
            long completed = plan.completedStepCount();
            int total = plan.stepCount();
            output.append("• ")
                    .append(id)
                    .append(active)
                    .append(": ")
                    .append(plan.title())
                    .append(" - ")
                    .append(completed)
                    .append('/')
                    .append(total)
                    .append(" steps completed\n");
        }
        return output.toString();
    }

    private String getPlan(String planId) {
        String resolvedId = resolvePlanId(planId, true);
        return requirePlan(resolvedId).format();
    }

    public Plan getPlanObj(String planId) {
        String resolvedId = resolvePlanId(planId, true);
        return plans.get(planId);
    }

    private String setActivePlan(String planId) {
        if (planId == null || planId.isBlank()) {
            throw new PlanToolException("Parameter `plan_id` is required for command: set_active");
        }
        Plan plan = requirePlan(planId);
        currentPlanId = planId;
        return "Plan '%s' is now the active plan.\n\n%s".formatted(planId, plan.format());
    }

    private String markStep(String planId, Integer stepIndex, String stepStatus, String stepNotes) {
        String resolvedId = resolvePlanId(planId, true);
        Plan plan = requirePlan(resolvedId);

        if (stepIndex == null) {
            throw new PlanToolException("Parameter `step_index` is required for command: mark_step");
        }
        if (stepIndex < 0 || stepIndex >= plan.stepCount()) {
            throw new PlanToolException(
                    "Invalid step_index: %d. Valid indices range from 0 to %d."
                            .formatted(stepIndex, plan.stepCount() - 1));
        }

        Plan.StepStatus status = null;
        if (stepStatus != null && !stepStatus.isBlank()) {
            status = Plan.StepStatus.fromId(stepStatus);
            if (status == null) {
                throw new PlanToolException(
                        "Invalid step_status: %s. Valid: not_started, in_progress, completed, blocked"
                                .formatted(stepStatus));
            }
        }

        plan.markStep(stepIndex, status, stepNotes);
        return "Step %d updated in plan '%s'.\n\n%s".formatted(stepIndex, resolvedId, plan.format());
    }

    private String deletePlan(String planId) {
        if (planId == null || planId.isBlank()) {
            throw new PlanToolException("Parameter `plan_id` is required for command: delete");
        }
        if (!plans.containsKey(planId)) {
            throw new PlanToolException("No plan found with ID: " + planId);
        }
        plans.remove(planId);
        if (planId.equals(currentPlanId)) {
            currentPlanId = null;
        }
        return "Plan '%s' has been deleted.".formatted(planId);
    }

    private String resolvePlanId(String planId, boolean allowActiveFallback) {
        if (planId != null && !planId.isBlank()) {
            return planId;
        }
        if (allowActiveFallback && currentPlanId != null) {
            return currentPlanId;
        }
        throw new PlanToolException("No active plan. Please specify a plan_id or set an active plan.");
    }

    private Plan requirePlan(String planId) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            throw new PlanToolException("No plan found with ID: " + planId);
        }
        return plan;
    }

    /** Exposed for flows that need direct plan storage access (OpenManus PlanningFlow). */
    public Map<String, Plan> plans() {
        return plans;
    }

    public String currentPlanId() {
        return currentPlanId;
    }

    public static class PlanToolException extends RuntimeException {
        public PlanToolException(String message) {
            super(message);
        }
    }
}
