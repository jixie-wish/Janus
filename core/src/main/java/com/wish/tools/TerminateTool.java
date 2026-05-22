package com.wish.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** OpenManus {@code app.tool.terminate.Terminate}. */
public class TerminateTool {

    @Tool(
            name = "terminate",
            description = "Terminate the interaction when the request is met OR if the assistant cannot proceed further. "
                    + "When you have finished all tasks, call this tool to end the work.")
    public String terminate(
            @ToolParam(description = "The finish status of the interaction.", required = true) String status) {
        return "The interaction has been completed with status: " + status;
    }
}
