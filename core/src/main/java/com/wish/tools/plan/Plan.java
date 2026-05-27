package com.wish.tools.plan;

import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** In-memory plan state aligned with OpenManus {@code app.tool.planning.PlanningTool}. */
public class Plan {

    private static final Pattern STEP_TYPE_PATTERN = Pattern.compile("\\[([A-Za-z0-9_]+)\\]");

    public enum StepStatus {
        NOT_STARTED("not_started", "[ ]", true),
        IN_PROGRESS("in_progress", "[→]", true),
        COMPLETED("completed", "[✓]", false),
        BLOCKED("blocked", "[!]", false);

        private final String id;
        private final String symbol;
        private final boolean active;

        StepStatus(String id, String symbol, boolean active) {
            this.id = id;
            this.symbol = symbol;
            this.active = active;
        }

        public String id() {
            return id;
        }

        public String symbol() {
            return symbol;
        }

        public boolean isActive() {
            return active;
        }

        public static StepStatus fromId(String id) {
            if (id == null || id.isBlank()) {
                return null;
            }
            for (StepStatus status : values()) {
                if (status.id.equalsIgnoreCase(id)) {
                    return status;
                }
            }
            return null;
        }
    }

    private final String planId;
    private String title;
    private List<String> steps;
    private List<StepStatus> stepStatuses;
    private List<String> stepNotes;

    public Plan(String planId, String title, List<String> steps) {
        this.planId = planId;
        this.title = title;
        this.steps = new ArrayList<>(steps);
        int stepNum = steps.size();
        this.stepStatuses = new ArrayList<>(stepNum);
        this.stepNotes = new ArrayList<>(stepNum);
        for (int i = 0; i < stepNum; i++) {
            stepStatuses.add(StepStatus.NOT_STARTED);
            stepNotes.add("");
        }
    }

    public String planId() {
        return planId;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void updateSteps(List<String> newSteps) {
        List<StepStatus> oldStatuses = stepStatuses;
        List<String> oldNotes = stepNotes;
        List<String> oldSteps = steps;

        List<StepStatus> newStatuses = new ArrayList<>();
        List<String> newNotes = new ArrayList<>();
        for (int i = 0; i < newSteps.size(); i++) {
            String step = newSteps.get(i);
            if (i < oldSteps.size() && step.equals(oldSteps.get(i))) {
                newStatuses.add(oldStatuses.get(i));
                newNotes.add(oldNotes.get(i));
            } else {
                newStatuses.add(StepStatus.NOT_STARTED);
                newNotes.add("");
            }
        }
        this.steps = new ArrayList<>(newSteps);
        this.stepStatuses = newStatuses;
        this.stepNotes = newNotes;
    }

    public void markStep(int stepIndex, StepStatus status, String notes) {
        if (status != null) {
            stepStatuses.set(stepIndex, status);
        }
        if (notes != null) {
            stepNotes.set(stepIndex, notes);
        }
    }

    public int stepCount() {
        return steps.size();
    }

    public long completedStepCount() {
        return stepStatuses.stream().filter(s -> s == StepStatus.COMPLETED).count();
    }

    /** Extract executor hint from step text, e.g. {@code [SEARCH]} → {@code search}. */
    public static Optional<String> parseStepType(String step) {
        if (step == null || step.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = STEP_TYPE_PATTERN.matcher(step);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return format();
    }

    public String format() {
        String header = "Plan: " + title + " (ID: " + planId + ")";
        StringBuilder output = new StringBuilder(header).append('\n');
        output.append("=".repeat(header.length())).append("\n\n");

        int total = steps.size();
        long completed = stepStatuses.stream().filter(s -> s == StepStatus.COMPLETED).count();
        long inProgress = stepStatuses.stream().filter(s -> s == StepStatus.IN_PROGRESS).count();
        long blocked = stepStatuses.stream().filter(s -> s == StepStatus.BLOCKED).count();
        long notStarted = stepStatuses.stream().filter(s -> s == StepStatus.NOT_STARTED).count();

        output.append("Progress: ").append(completed).append('/').append(total).append(" steps completed ");
        if (total > 0) {
            double percentage = (completed * 100.0) / total;
            output.append(String.format("(%.1f%%)", percentage));
        } else {
            output.append("(0%)");
        }
        output.append('\n');
        output.append("Status: ")
                .append(completed)
                .append(" completed, ")
                .append(inProgress)
                .append(" in progress, ")
                .append(blocked)
                .append(" blocked, ")
                .append(notStarted)
                .append(" not started\n\n");
        output.append("Steps:\n");

        for (int i = 0; i < steps.size(); i++) {
            StepStatus status = stepStatuses.get(i);
            output.append(i).append(". ").append(status.symbol()).append(' ').append(steps.get(i)).append('\n');
            String notes = stepNotes.get(i);
            if (notes != null && !notes.isBlank()) {
                output.append("   Notes: ").append(notes).append('\n');
            }
        }
        return output.toString();
    }

    /** Parse steps from tool input: JSON list or comma-separated string. */
    public static List<String> parseSteps(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        if (steps.size() == 1 && steps.get(0).contains(",")) {
            return Arrays.stream(steps.get(0).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return steps.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * First active step (not_started / in_progress), marked in_progress.
     * Returns {@code (-1, empty)} when the plan is done.
     */
    public Pair<Integer, Map<String, String>> getCurrentStepInfo() {
        for (int i = 0; i < steps.size(); i++) {
            StepStatus status = i < stepStatuses.size() ? stepStatuses.get(i) : StepStatus.NOT_STARTED;
            if (!status.isActive()) {
                continue;
            }
            Map<String, String> stepInfo = new HashMap<>();
            String stepText = steps.get(i);
            stepInfo.put("text", stepText);
            parseStepType(stepText).ifPresent(type -> stepInfo.put("type", type));
            markStep(i, StepStatus.IN_PROGRESS, null);
            return Pair.of(i, stepInfo);
        }
        return Pair.of(-1, Map.of());
    }
}
