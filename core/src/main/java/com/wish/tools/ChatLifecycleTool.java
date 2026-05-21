package com.wish.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** OpenManus ToolCallAgent defaults: CreateChatCompletion + Terminate (app/agent/toolcall.py). */
public class ChatLifecycleTool {

    @Tool(
            name = "create_chat_completion",
            description = "Creates a structured completion with specified output formatting.")
    public String createChatCompletion(
            @ToolParam(description = "The response text that should be delivered to the user.", required = true)
            String response) {
        return response;
    }

    @Tool(
            name = "terminate",
            description = "Terminate the interaction when the request is met OR if the assistant cannot proceed further "
                    + "with the task. When you have finished all the tasks, call this tool to end the work.")
    public String terminate(
            @ToolParam(description = "The finish status of the interaction.", required = true) String status) {
        return "The interaction has been completed with status: " + status;
    }
}
