package com.wish.tools.plan;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory planning tool (OpenManus {@code PlanningTool}).
 * Steps are plain strings; optional {@code [agent_name]} prefix selects the executor in {@link com.wish.flow.PlanningFlow}.
 * Every command that targets a plan requires an explicit {@code plan_id}.
 */
public class PlanTool {

    private static final String CMD_CREATE = "create";
    private static final String CMD_UPDATE = "update";
    private static final String CMD_LIST = "list";
    private static final String CMD_GET = "get";
    private static final String CMD_MARK_STEP = "mark_step";
    private static final String CMD_DELETE = "delete";

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();

    @Tool(
            name = "planning",
            description =
                    """
                    Create and manage plans: create, update, list, get, mark_step, delete.
                    Steps are short actionable strings; use [agent_name] prefix when multiple agents exist.
                    plan_id is required for all commands except list.
                    """)
    public String plan(
            @ToolParam(description = "create | update | list | get | mark_step | delete", required = true)
                    String command,
            @ToolParam(description = "Plan id (required except for list)", required = false) String planId,
            @ToolParam(description = "Plan title (required for create)", required = false) String title,
            @ToolParam(description = "Step descriptions (required for create)", required = false)
                    List<String> steps,
            @ToolParam(description = "0-based step index (required for mark_step)", required = false)
                    Integer stepIndex,
            @ToolParam(description = "not_started | in_progress | completed | blocked (mark_step)", required = false)
                    String stepStatus,
            @ToolParam(description = "Optional step notes (mark_step)", required = false) String stepNotes) {
        return switch (command) {
            case CMD_CREATE -> create(planId, title, steps);
            case CMD_UPDATE -> update(planId, title, steps);
            case CMD_LIST -> list();
            case CMD_GET -> format(requirePlan(planId, CMD_GET));
            case CMD_MARK_STEP -> markStep(planId, stepIndex, stepStatus, stepNotes);
            case CMD_DELETE -> delete(planId);
            default -> throw badCommand(command);
        };
    }

    public Plan createPlan(String planId, String title, List<String> steps) {
        requireId(planId, CMD_CREATE);
        requireTitle(title, CMD_CREATE);
        if (plans.containsKey(planId)) {
            throw new PlanToolException("Plan '%s' already exists; use update.".formatted(planId));
        }
        Plan plan = new Plan(planId, title, requireSteps(steps, CMD_CREATE));
        plans.put(planId, plan);
        return plan;
    }

    public boolean hasPlan(String planId) {
        return planId != null && plans.containsKey(planId);
    }

    public Plan getPlan(String planId) {
        return requirePlan(planId, "access");
    }

    public String markStep(String planId, int stepIndex, Plan.StepStatus status) {
        return markStep(planId, stepIndex, status != null ? status.id() : null, null);
    }

    public String markStep(String planId, Integer stepIndex, String stepStatus, String stepNotes) {
        Plan plan = requirePlan(planId, CMD_MARK_STEP);
        if (stepIndex == null) {
            throw new PlanToolException("`step_index` is required for mark_step");
        }
        if (stepIndex < 0 || stepIndex >= plan.stepCount()) {
            throw new PlanToolException(
                    "Invalid step_index %d (valid: 0..%d)".formatted(stepIndex, plan.stepCount() - 1));
        }
        Plan.StepStatus status = null;
        if (stepStatus != null && !stepStatus.isBlank()) {
            status = Plan.StepStatus.fromId(stepStatus);
            if (status == null) {
                throw new PlanToolException("Invalid step_status: " + stepStatus);
            }
        }
        plan.markStep(stepIndex, status, stepNotes);
        return reply("Step %d updated.".formatted(stepIndex), plan);
    }

    private String create(String planId, String title, List<String> steps) {
        Plan plan = createPlan(planId, title, steps);
        return reply("Plan created (id=%s).".formatted(plan.planId()), plan);
    }

    private String update(String planId, String title, List<String> steps) {
        Plan plan = requirePlan(planId, CMD_UPDATE);
        if (title != null && !title.isBlank()) {
            plan.setTitle(title);
        }
        if (steps != null && !steps.isEmpty()) {
            plan.updateSteps(requireSteps(steps, CMD_UPDATE));
        }
        return reply("Plan updated.", plan);
    }

    private String list() {
        if (plans.isEmpty()) {
            return "No plans. Use command=create.";
        }
        StringBuilder out = new StringBuilder("Plans:\n");
        plans.forEach((id, plan) -> out.append("• ")
                .append(id)
                .append(": ")
                .append(plan.title())
                .append(" (")
                .append(plan.completedStepCount())
                .append('/')
                .append(plan.stepCount())
                .append(" done)\n"));
        return out.toString();
    }

    private String delete(String planId) {
        requireId(planId, CMD_DELETE);
        if (plans.remove(planId) == null) {
            throw new PlanToolException("No plan: " + planId);
        }
        return "Plan '%s' deleted.".formatted(planId);
    }

    private Plan requirePlan(String planId, String command) {
        requireId(planId, command);
        Plan plan = plans.get(planId.trim());
        if (plan == null) {
            throw new PlanToolException("No plan: " + planId);
        }
        return plan;
    }

    private static String reply(String headline, Plan plan) {
        return headline + "\n\n" + plan.format();
    }

    private static String format(Plan plan) {
        return plan.format();
    }

    private static void requireId(String planId, String command) {
        if (planId == null || planId.isBlank()) {
            throw new PlanToolException("`plan_id` required for " + command);
        }
    }

    private static void requireTitle(String title, String command) {
        if (title == null || title.isBlank()) {
            throw new PlanToolException("`title` required for " + command);
        }
    }

    private static List<String> requireSteps(List<String> steps, String command) {
        List<String> parsed = Plan.parseSteps(steps);
        if (parsed.isEmpty()) {
            throw new PlanToolException("`steps` must be non-empty for " + command);
        }
        return parsed;
    }

    private static PlanToolException badCommand(String command) {
        return new PlanToolException("Unknown command: " + command);
    }

    public static final class PlanToolException extends RuntimeException {
        public PlanToolException(String message) {
            super(message);
        }
    }
}
