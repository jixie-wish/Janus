package com.wish.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class ChatLifecycleTool {

    @Tool(description = "Creates a structured completion with specified output formatting")
    public String createChatCompletion(
            @ToolParam(description = "The response text that should be delivered to the user", required = true) String response) {
        return response;
    }

    @Tool(description = "Terminate the interaction when the request is met OR if the assistant cannot proceed further " +
            "with the task. When you have finished all the tasks, call this tol to end the work")
    public String terminate(@ToolParam(description = "The finish status of the interaction, should be success or failure", required = true) String status) {
        return "The interaction has been completed with status: " + status;
    }
}
