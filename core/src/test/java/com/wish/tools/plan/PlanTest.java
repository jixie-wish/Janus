package com.wish.tools.plan;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTest {

    @Test
    void parseStepType_extractsBracketedAgentName() {
        assertEquals(Optional.of("search"), Plan.parseStepType("[SEARCH] Find docs"));
        assertEquals(Optional.of("code"), Plan.parseStepType("Run [code] step"));
        assertTrue(Plan.parseStepType("plain step").isEmpty());
    }

    @Test
    void getCurrentStepInfo_returnsActiveStepAndMarksInProgress() {
        Plan plan = new Plan("p1", "Task", List.of("[agent_0] Step A", "Step B"));

        Pair<Integer, Map<String, String>> first = plan.getCurrentStepInfo();
        assertEquals(0, first.getLeft().intValue());
        assertEquals("[agent_0] Step A", first.getRight().get("text"));
        assertEquals("agent_0", first.getRight().get("type"));

        plan.markStep(0, Plan.StepStatus.COMPLETED, null);
        Pair<Integer, Map<String, String>> second = plan.getCurrentStepInfo();
        assertEquals(1, second.getLeft().intValue());
        assertEquals("Step B", second.getRight().get("text"));

        plan.markStep(1, Plan.StepStatus.COMPLETED, null);
        assertEquals(-1, plan.getCurrentStepInfo().getLeft().intValue());
    }
}
