package com.wish.tools.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanToolTest {

    private PlanTool tool;

    @BeforeEach
    void setUp() {
        tool = new PlanTool();
    }

    @Test
    void create_list_get() {
        String created =
                tool.plan("create", "p1", "Trip", List.of("Book flight", "Book hotel"), null, null, null);

        assertTrue(created.contains("Plan created"));
        assertTrue(created.contains("Book flight"));

        String listed = tool.plan("list", null, null, null, null, null, null);
        assertTrue(listed.contains("p1"));

        String got = tool.plan("get", "p1", null, null, null, null, null);
        assertTrue(got.contains("[ ] Book flight"));
    }

    @Test
    void markStep_requiresPlanId() {
        tool.plan("create", "p1", "Task", List.of("Step A", "Step B"), null, null, null);

        assertThrows(
                PlanTool.PlanToolException.class,
                () -> tool.plan("mark_step", null, null, null, 0, "completed", "done"));

        String marked = tool.plan("mark_step", "p1", null, null, 0, "completed", "done");
        assertTrue(marked.contains("[✓] Step A"));
        assertTrue(marked.contains("Notes: done"));
    }

    @Test
    void update_preservesMatchingStepStatus() {
        tool.plan("create", "p1", "Task", List.of("A", "B"), null, null, null);
        tool.plan("mark_step", "p1", null, null, 0, "completed", null);

        String updated = tool.plan("update", "p1", null, List.of("A", "C"), null, null, null);

        assertTrue(updated.contains("[✓] A"));
        assertTrue(updated.contains("[ ] C"));
    }

    @Test
    void get_requiresPlanId() {
        tool.plan("create", "p1", "Task", List.of("A"), null, null, null);
        tool.plan("delete", "p1", null, null, null, null, null);

        assertThrows(PlanTool.PlanToolException.class, () -> tool.plan("get", null, null, null, null, null, null));
    }

    @Test
    void create_rejectsDuplicateId() {
        tool.plan("create", "p1", "One", List.of("x"), null, null, null);

        assertThrows(
                PlanTool.PlanToolException.class,
                () -> tool.plan("create", "p1", "Two", List.of("y"), null, null, null));
    }
}
