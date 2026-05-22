package com.wish.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** OpenManus ToolCallAgent defaults: CreateChatCompletion + Terminate (app/agent/toolcall.py). */
public class ChatLifecycleTool {

    @Tool(
            name = "create_chat_completion",
            description = "Deliver the complete user-visible answer. Required for every substantive reply; "
                    + "do not duplicate this content in assistant message text.")
    public String createChatCompletion(
            @ToolParam(
                    description = "Full answer text shown to the user (same language as the user).",
                    required = true)
            String response) {
        return response;
    }

    @Tool(
            name = "terminate",
            description = "End the session after the answer was already sent via create_chat_completion. "
                    + "Call alone in a separate turn with status success or failed; never use for the answer body.")
    public String terminate(
            @ToolParam(description = "The finish status of the interaction.", required = true) String status) {
        return "The interaction has been completed with status: " + status;
    }
}
